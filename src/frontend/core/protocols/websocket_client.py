"""OneM2M WebSocket protocol client for ACME CSE v2025.11.

All format details (flat JSON, no m2m:rqp wrapper, no leading slash in `to`,
`rvi` mandatory, `poa` required, `nct` omitted) are empirically verified
against ACME CSE v2025.11. See docs/ai-context/cse-dev.md § WebSocket Binding.

Transport: websockets 13.1 sync API (websockets.sync.client), which is
thread-safe for concurrent send/recv.
"""
import json
import logging
import threading
import time
import uuid
from typing import Callable, Optional

_log = logging.getLogger(__name__)

from websockets.sync.client import connect as ws_connect
from websockets.exceptions import ConnectionClosed

from .base import ProtocolClient
from ..config import Config

# Originator used for all requests from Streamlit.
# Must start with 'C' to match allowedAEOriginators = C* in acme.ini.
_ORIGINATOR = "CStreamlit"

# SUB resource names for Streamlit's subscriptions.
# Fixed names: old resources are deleted and re-created on reconnect.
_SUB_TEL_RN = "sub-streamlit-tel"
_SUB_ACK_RN = "sub-streamlit-ack"

# AE resource name registered under the CSE-Base.
_AE_RN = "streamlit"

# Timeout for waiting for a CSE response to a request (seconds).
_REQUEST_TIMEOUT_S = 10.0


class WebSocketClient(ProtocolClient):
    """WebSocket OneM2M client using flat-JSON ACME CSE v2025.11 format.

    Lifecycle:
        client = WebSocketClient(config)
        client.subscribe_telemetry(my_tel_cb)
        client.subscribe_ack(my_ack_cb)
        client.connect()        # blocks until AE + subscriptions are set up
        client.send_command({"command": "takeoff", ...})
        client.disconnect()
    """

    def __init__(self, config: Config) -> None:
        self._config = config
        self._ws = None

        # Background receive thread
        self._recv_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()

        # Registered callbacks (set before connect())
        self._telemetry_cb: Optional[Callable[[dict], None]] = None
        self._ack_cb: Optional[Callable[[dict], None]] = None

        # Pending request tracking: rqi -> (response_dict_or_None, event)
        self._lock = threading.Lock()
        self._pending: dict[str, threading.Event] = {}
        self._responses: dict[str, Optional[dict]] = {}

        # Monotonic sequence counter for rqi generation
        self._seq = 0

        # ri (resource identifier) of each subscription — set by _ensure_subscription.
        # ACME CSE puts the ri in `sur` of notifications, not the human-readable path.
        self._tel_sub_ri: Optional[str] = None
        self._ack_sub_ri: Optional[str] = None

    # ------------------------------------------------------------------
    # ProtocolClient interface
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Connect to the CSE WebSocket endpoint and set up the session.

        Steps:
        1. WebSocket upgrade (subprotocol=oneM2M.json, X-M2M-Origin header)
        2. Register as AE with poa=[ws://host:port] — required for notifications
        3. Create SUB on /cse-in/uxv/telemetry (delete+create if exists)
        4. Create SUB on /cse-in/uxv/ack
        """
        uri = self._config.cse_ws_url
        self._ws = ws_connect(
            uri,
            subprotocols=["oneM2M.json"],
            additional_headers={"X-M2M-Origin": _ORIGINATOR},
        )
        self._stop_event.clear()
        self._recv_thread = threading.Thread(target=self._recv_loop, daemon=True, name="ws-recv")
        self._recv_thread.start()

        self._register_ae()
        self._tel_sub_ri = self._ensure_subscription("cse-in/uxv/telemetry", _SUB_TEL_RN)
        self._ack_sub_ri = self._ensure_subscription("cse-in/uxv/ack", _SUB_ACK_RN)
        if self._tel_sub_ri is None:
            print(f"[{time.strftime('%H:%M:%S')}][WS] WARNING: telemetry subscription failed — Android container may not exist yet. Reconnect after Android registers.", flush=True)
        if self._ack_sub_ri is None:
            print(f"[{time.strftime('%H:%M:%S')}][WS] WARNING: ack subscription failed — Android container may not exist yet.", flush=True)
        print(f"[{time.strftime('%H:%M:%S')}][WS] tel_sub_ri={self._tel_sub_ri}  ack_sub_ri={self._ack_sub_ri}", flush=True)

    def send_command(self, payload: dict) -> tuple[float | None, bool]:
        """Post a command CIN to /cse-in/uxv/commands.

        Returns
        -------
        (latency_ms, delivered)
        """
        con_str = json.dumps(payload)
        rqi = self._next_rqi()
        req = {
            "op": 1,   # CREATE
            "to": "cse-in/uxv/commands",
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
            "ty": 4,   # CIN
            "pc": {"m2m:cin": {"con": con_str}},
        }
        t_start = time.monotonic_ns()
        try:
            resp = self._send_request(req, timeout=_REQUEST_TIMEOUT_S)
            t_end = time.monotonic_ns()
            latency_ms = (t_end - t_start) / 1_000_000
            delivered = resp.get("rsc") == 2001 if resp else False
            return latency_ms, delivered
        except TimeoutError:
            return None, False

    def subscribe_telemetry(self, callback: Callable[[dict], None]) -> None:
        """Register telemetry notification callback (called before connect())."""
        self._telemetry_cb = callback

    def subscribe_ack(self, callback: Callable[[dict], None]) -> None:
        """Register ACK notification callback (called before connect())."""
        self._ack_cb = callback

    def disconnect(self) -> None:
        """Delete CSE subscriptions, then close the WebSocket connection.

        Deleting subscriptions before closing prevents stale delivery on the
        next connect() and ensures _ensure_subscription() can re-create them
        cleanly (no 4005 CONFLICT from a leftover sub with the same rn).
        Must be done before stopping the recv loop so responses can still arrive.
        """
        for sub_path in [
            f"cse-in/uxv/telemetry/{_SUB_TEL_RN}",
            f"cse-in/uxv/ack/{_SUB_ACK_RN}",
        ]:
            try:
                self._delete_resource(sub_path)
            except Exception:
                pass
        self._stop_event.set()
        if self._ws:
            try:
                self._ws.close()
            except Exception:
                pass
        if self._recv_thread:
            self._recv_thread.join(timeout=3.0)

    def get_header_bytes(self, payload_size: int) -> int:
        """WebSocket frame overhead: 2 bytes base + 4-byte masking key.

        For payloads ≤125 bytes the extended length field is 0 extra bytes.
        For 126–65535 bytes: +2. For >65535 bytes: +6. Masking key always 4 bytes.
        """
        if payload_size <= 125:
            return 6   # 1 (opcode+fin) + 1 (mask+len) + 4 (masking key)
        elif payload_size <= 65535:
            return 8   # +2 extended 16-bit length
        else:
            return 14  # +8 extended 64-bit length

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _next_rqi(self) -> str:
        with self._lock:
            self._seq += 1
            return f"sl-{self._seq}-{uuid.uuid4().hex[:6]}"

    def _send_request(self, request: dict, timeout: float = _REQUEST_TIMEOUT_S) -> Optional[dict]:
        """Send a flat-JSON request and block until the matching response arrives.

        Parameters
        ----------
        request : dict — flat OneM2M JSON request
        timeout : float — seconds to wait for response

        Returns
        -------
        dict response or None if timeout
        """
        rqi = request["rqi"]
        event = threading.Event()
        with self._lock:
            self._pending[rqi] = event
            self._responses[rqi] = None

        self._ws.send(json.dumps(request))

        ok = event.wait(timeout=timeout)
        with self._lock:
            resp = self._responses.pop(rqi, None)
            self._pending.pop(rqi, None)

        if not ok:
            raise TimeoutError(f"No response for rqi={rqi}")
        return resp

    def _recv_loop(self) -> None:
        """Background thread: receive messages and dispatch to handlers."""
        while not self._stop_event.is_set():
            try:
                raw = self._ws.recv(timeout=1.0)
            except TimeoutError:
                continue
            except ConnectionClosed:
                break
            except Exception:
                if not self._stop_event.is_set():
                    continue
                break

            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue

            if msg.get("op") == 5:
                # NOTIFY from CSE — subscription notification or verification request
                self._handle_notify(msg)
            elif "rsc" in msg:
                # Response to one of our requests
                rqi = msg.get("rqi")
                with self._lock:
                    if rqi in self._pending:
                        self._responses[rqi] = msg
                        self._pending[rqi].set()

    def _handle_notify(self, msg: dict) -> None:
        """Process an incoming op=5 NOTIFY message.

        Sends back a flat rsc=2000 acknowledgment (required by ACME CSE).
        Dispatches CIN.con to the appropriate callback based on the SUB URI.
        """
        rqi = msg.get("rqi", "")
        # ACK the notification — flat format required (no m2m:rsp wrapper).
        ack = {
            "rsc": 2000,
            "rqi": rqi,
            "to": _ORIGINATOR,
            "fr": _ORIGINATOR,
            "rvi": "3",
        }
        try:
            self._ws.send(json.dumps(ack))
        except Exception:
            pass

        pc = msg.get("pc", {})
        sgn = pc.get("m2m:sgn", {})

        # Verification request: CSE sends vrq=true after SUB creation — just ACK.
        if sgn.get("vrq"):
            return

        nev = sgn.get("nev", {})
        rep = nev.get("rep", {})
        cin = rep.get("m2m:cin", {})
        con_raw = cin.get("con", "")
        sur = sgn.get("sur", "")  # subscribed resource URI

        # ACME CSE puts the subscription ri (not the human-readable path) in `sur`,
        # e.g. '/id-in/subBPiTR1sRvs'. Match against the ri captured at connect time.
        is_tel = self._tel_sub_ri is not None and self._tel_sub_ri in sur
        is_ack = self._ack_sub_ri is not None and self._ack_sub_ri in sur
        # Only log ACK notifications and unmatched ones — telemetry is too frequent to log every CIN.
        if is_ack or (not is_tel and not is_ack):
            print(f"[{time.strftime('%H:%M:%S')}][WS] notify sur={sur!r} is_tel={is_tel} is_ack={is_ack}", flush=True)

        try:
            con = json.loads(con_raw) if isinstance(con_raw, str) else con_raw
        except (json.JSONDecodeError, TypeError):
            return

        if is_tel and self._telemetry_cb:
            try:
                self._telemetry_cb(con)
            except Exception as exc:
                # Catch exceptions from callbacks (e.g. Streamlit session state
                # accessed from a non-main thread) to prevent recv_loop from dying.
                print(f"[{time.strftime('%H:%M:%S')}][WS] telemetry_cb raised: {exc!r}", flush=True)
        elif is_ack and self._ack_cb:
            try:
                self._ack_cb(con)
            except Exception as exc:
                print(f"[{time.strftime('%H:%M:%S')}][WS] ack_cb raised: {exc!r}", flush=True)

    def _register_ae(self) -> None:
        """Register Streamlit as an AE under the CSE-Base.

        poa is required — without it, the CSE discards subscription notifications.
        On 4105 CONFLICT (AE already exists from a previous session), proceed.
        """
        rqi = self._next_rqi()
        req = {
            "op": 1,
            "to": "id-in",          # CSE-Base ri; NO leading slash
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
            "ty": 2,                # AE
            "pc": {
                "m2m:ae": {
                    "rn": _AE_RN,
                    "api": "N.com.uxv.benchmark.streamlit",
                    "srv": ["3"],
                    "rr": True,
                    "poa": [self._config.cse_ws_url.rstrip("/")],
                }
            },
        }
        try:
            resp = self._send_request(req)
        except TimeoutError:
            return  # If CSE doesn't respond, proceed optimistically.

        rsc = resp.get("rsc") if resp else None
        # 2001 = Created, 4105 = Conflict (already exists),
        # 4117 = ACME CSE v2025.11 "originator already registered on active WS" — all OK.
        print(f"[{time.strftime('%H:%M:%S')}][WS] AE register rsc={rsc}", flush=True)
        if rsc not in (2001, 4105, 4117):
            raise RuntimeError(f"AE registration failed: rsc={rsc}, resp={resp}")
        # 4105/4117: AE already exists from a prior session (e.g. previous MQTT run).
        # Its poa may be stale (mqtt://...). Update it so the CSE uses the active WS
        # connection (associatedConnections) instead of attempting MQTT delivery.
        if rsc in (4105, 4117):
            self._update_ae_poa()

    def _update_ae_poa(self) -> None:
        """UPDATE the AE poa to the current WS URL.

        Called on 4105/4117 during AE registration. Without this, a stale poa from a
        prior MQTT session (mqtt://mosquitto:1883) causes the CSE to attempt MQTT delivery
        instead of using the active WS connection (associatedConnections).
        """
        rqi = self._next_rqi()
        req = {
            "op": 3,   # UPDATE
            "to": f"cse-in/{_AE_RN}",
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
            "pc": {
                "m2m:ae": {
                    "poa": [self._config.cse_ws_url.rstrip("/")],
                }
            },
        }
        try:
            resp = self._send_request(req, timeout=5.0)
            rsc = resp.get("rsc") if resp else None
            if rsc == 2004:
                print(f"[{time.strftime('%H:%M:%S')}][WS] AE poa updated to {self._config.cse_ws_url}", flush=True)
            else:
                print(f"[{time.strftime('%H:%M:%S')}][WS] AE poa update returned rsc={rsc}", flush=True)
        except TimeoutError:
            print(f"[{time.strftime('%H:%M:%S')}][WS] AE poa update timed out", flush=True)

    def _ensure_subscription(self, container_path: str, rn: str) -> Optional[str]:
        """Create a SUB resource; delete and re-create if it already exists.

        Returns the `ri` of the created subscription, used to match `sur` in
        incoming notifications (ACME CSE puts ri, not rn, in the `sur` field).

        Parameters
        ----------
        container_path : str — e.g. 'cse-in/uxv/telemetry' (no leading slash)
        rn : str — subscription resource name, e.g. 'sub-streamlit-tel'
        """
        sub_path = f"{container_path}/{rn}"

        # Attempt to delete an existing subscription from a previous session.
        self._delete_resource(sub_path)

        rqi = self._next_rqi()
        req = {
            "op": 1,
            "to": container_path,
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
            "ty": 23,              # SUB
            "pc": {
                "m2m:sub": {
                    "rn": rn,
                    # nu: notify the AE originator — CSE looks up its poa.
                    # nct MUST be omitted (nct=2 + net=[3] invalid in v2025.11).
                    "nu": [_ORIGINATOR],
                    "enc": {"net": [3]},  # net=3: notify on resource creation (new CIN)
                }
            },
        }
        try:
            resp = self._send_request(req)
        except TimeoutError:
            return None

        rsc = resp.get("rsc") if resp else None
        print(f"[{time.strftime('%H:%M:%S')}][WS] subscribe {container_path}/{rn} rsc={rsc}", flush=True)
        if rsc == 2001:
            # Extract the auto-generated ri so we can match it in notification `sur`.
            return resp.get("pc", {}).get("m2m:sub", {}).get("ri")
        elif rsc == 4005:
            # DELETE timed out and old sub is still there — GET its ri instead of failing.
            # The AE poa has already been updated to ws:// so notifications will be delivered
            # correctly via the active WS connection.
            return self._get_sub_ri(container_path, rn)
        return None

    def _get_sub_ri(self, container_path: str, rn: str) -> Optional[str]:
        """RETRIEVE an existing subscription to get its ri (fallback for 4005 conflict).

        Parameters
        ----------
        container_path : str — e.g. 'cse-in/uxv/telemetry'
        rn : str — subscription resource name
        """
        rqi = self._next_rqi()
        req = {
            "op": 2,   # RETRIEVE
            "to": f"{container_path}/{rn}",
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
        }
        try:
            resp = self._send_request(req, timeout=3.0)
            rsc = resp.get("rsc") if resp else None
            if rsc == 2000:
                return resp.get("pc", {}).get("m2m:sub", {}).get("ri")
        except TimeoutError:
            pass
        return None

    def _delete_resource(self, resource_path: str) -> None:
        """DELETE a CSE resource (best-effort; ignore errors)."""
        rqi = self._next_rqi()
        req = {
            "op": 4,   # DELETE
            "to": resource_path,
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
        }
        try:
            self._send_request(req, timeout=3.0)
        except (TimeoutError, Exception):
            pass
