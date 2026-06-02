"""OneM2M CoAP protocol client for ACME CSE v2025.11.

Uses Confirmable (CON) messages via aiocoap. No DTLS — not supported in v2025.11.

Notification delivery — Docker Desktop Windows limitation:
    Docker Desktop does NOT route UDP from containers to the host (neither the
    host LAN IP nor host.docker.internal). Since CoAP uses UDP, the CSE container
    cannot deliver CoAP NOTIFY requests to an aiocoap callback server on the host.
    TCP to host.docker.internal works, so notifications are received via an embedded
    HTTP server (same port as http_client: CALLBACK_HTTP_PORT). The subscription nu
    is set to http://host.docker.internal:CALLBACK_HTTP_PORT/notify.

    This means CoAP notification latency includes HTTP TCP overhead instead of
    CoAP UDP overhead. Document in the benchmark report as a Docker Desktop
    lab environment constraint.

    Outgoing requests (send_command, subscribe) still use CoAP UDP to the CSE.

CoAP binding (TS-0010): oneM2M fields go in CoAP options, body = resource content.
  - Option 279 (oneM2M-FR)  = originator (STRING/bytes)
  - Option 283 (oneM2M-RQI) = request identifier (STRING/bytes)
  - Option 271 (oneM2M-RVI) = release version (STRING/bytes, value b"3")
  - Option 267 (oneM2M-TY)  = resource type (UINT, CREATE only)
  - Option 307 (oneM2M-RSC) = response status code (UINT, read from response)
Option numbers confirmed from ACME CSE v2025.11 CoAPthonTools.py source.

CoAP header overhead (fixed) per RFC 7252:
  4-byte fixed header + token (0-8 bytes) + options (variable) + payload marker

All async operations run in a private background event loop thread so
Streamlit's synchronous API is not affected.
"""
import asyncio
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Callable, Optional

import aiocoap
from aiocoap.optiontypes import OpaqueOption, UintOption

from .base import ProtocolClient
from ..config import Config

_ORIGINATOR = "CAdmin"
_SUB_TEL_RN = "sub-coap-streamlit-tel"
_SUB_ACK_RN = "sub-coap-streamlit-ack"

_REQUEST_TIMEOUT_S = 10.0

# oneM2M CoAP option numbers (ACME CSE v2025.11 — CoAPthonTools.py)
_OPT_TY  = 267   # oneM2M-TY  (UINT  — resource type, CREATE only)
_OPT_RVI = 271   # oneM2M-RVI (bytes — release version indicator)
_OPT_FR  = 279   # oneM2M-FR  (bytes — originator)
_OPT_RQI = 283   # oneM2M-RQI (bytes — request identifier)
_OPT_RSC = 307   # oneM2M-RSC (bytes → int — response status, read-only)


def _measure_notification_headers(handler: BaseHTTPRequestHandler) -> int:
    """Estimate incoming HTTP notification POST header bytes.

    Used to measure the actual overhead of notification delivery in S1
    (HTTP/TCP workaround — see module docstring). Reconstructs the raw
    HTTP request line + headers as they appear on the wire.

    Parameters
    ----------
    handler:
        BaseHTTPRequestHandler instance for the incoming POST.

    Returns
    -------
    int
        Estimated header bytes (request line + header fields + CRLF terminators).
    """
    try:
        lines = [f"POST {handler.path} HTTP/1.1"]
        for k, v in handler.headers.items():
            lines.append(f"{k}: {v}")
        return sum(len((line + "\r\n").encode()) for line in lines) + 2
    except Exception:
        return 0


class CoapClient(ProtocolClient):
    """CoAP OneM2M client with HTTP callback server for notifications.

    Notification flow (Docker Desktop TCP workaround):
        1. connect() starts an HTTPServer on 0.0.0.0:callback_http_port
        2. Creates SUBs with nu=[http://host.docker.internal:callback_http_port/notify]
        3. CSE POSTs HTTP notifications to that URL (TCP, routed via Docker)
        4. The HTTP callback server dispatches to telemetry_cb / ack_cb

    Outgoing CoAP requests (CIN create, subscribe, delete) use aiocoap over UDP.
    The aiocoap context is a client-only context (no server binding needed).

    The subscription ri is captured at subscribe time and used to match
    incoming notifications (ACME CSE puts ri, not path, in the sur field).
    """

    def __init__(self, config: Config) -> None:
        self._config = config
        self._telemetry_cb: Optional[Callable[[dict], None]] = None
        self._ack_cb: Optional[Callable[[dict], None]] = None

        # ri of each subscription — ACME CSE puts ri (e.g. /id-in/subXXX)
        # in the sur field of notifications, not the human-readable path.
        self._tel_sub_ri: Optional[str] = None
        self._ack_sub_ri: Optional[str] = None

        # Private asyncio event loop for outgoing CoAP requests.
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._loop_thread: Optional[threading.Thread] = None
        self._coap_context: Optional[aiocoap.Context] = None

        # HTTP callback server — receives CSE notifications via TCP.
        self._callback_server: Optional[HTTPServer] = None
        self._callback_thread: Optional[threading.Thread] = None

        # S1 notification header size (incoming HTTP POSTs from CSE).
        # Captured once on first notification; ACME CSE notification headers are
        # structurally constant between messages, so one sample is representative.
        self._notif_headers_bytes: int = 0

    # ------------------------------------------------------------------
    # ProtocolClient interface
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Start the aiocoap client context, HTTP callback server, and subscriptions."""
        # Clean up any previous instance to free ports and sockets.
        self.disconnect()

        # Start HTTP server first — must be listening before creating SUBs.
        self._start_callback_server()

        # Start the background asyncio loop for outgoing CoAP requests.
        self._loop = asyncio.new_event_loop()
        self._loop_thread = threading.Thread(
            target=self._run_loop, daemon=True, name="coap-loop"
        )
        self._loop_thread.start()

        # Initialise aiocoap client context inside the loop.
        future = asyncio.run_coroutine_threadsafe(self._async_connect(), self._loop)
        future.result(timeout=_REQUEST_TIMEOUT_S)

        self._tel_sub_ri = self._ensure_subscription("cse-in/uxv/telemetry", _SUB_TEL_RN)
        self._ack_sub_ri = self._ensure_subscription("cse-in/uxv/ack", _SUB_ACK_RN)
        if self._tel_sub_ri is None:
            print(f"[{time.strftime('%H:%M:%S')}][CoAP] WARNING: telemetry subscription ri not captured", flush=True)
        if self._ack_sub_ri is None:
            print(f"[{time.strftime('%H:%M:%S')}][CoAP] WARNING: ack subscription ri not captured", flush=True)
        print(f"[{time.strftime('%H:%M:%S')}][CoAP] tel_sub_ri={self._tel_sub_ri}  ack_sub_ri={self._ack_sub_ri}", flush=True)

    def send_command(self, payload: dict) -> tuple[float | None, bool]:
        """POST a command CIN to /cse-in/uxv/commands via CoAP CON."""
        con_str = json.dumps(payload)
        body = json.dumps({"m2m:cin": {"con": con_str}}).encode()
        uri = f"{self._config.cse_coap_base}/cse-in/uxv/commands"

        t_start = time.monotonic_ns()
        future = asyncio.run_coroutine_threadsafe(
            self._async_post(uri, body, ty=4),  # ty=4 = m2m:cin
            self._loop,
        )
        try:
            rsc, _ = future.result(timeout=_REQUEST_TIMEOUT_S + 1)
            t_end = time.monotonic_ns()
            latency_ms = (t_end - t_start) / 1_000_000
            delivered = rsc == 2001  # oneM2M CREATED
            print(f"[{time.strftime('%H:%M:%S')}][CoAP] send_command rsc={rsc} latency={latency_ms:.1f}ms delivered={delivered}", flush=True)
            return latency_ms, delivered
        except Exception as exc:
            print(f"[{time.strftime('%H:%M:%S')}][CoAP] send_command FAILED: {exc!r}", flush=True)
            return None, False

    def subscribe_telemetry(self, callback: Callable[[dict], None]) -> None:
        self._telemetry_cb = callback

    def subscribe_ack(self, callback: Callable[[dict], None]) -> None:
        self._ack_cb = callback

    def disconnect(self) -> None:
        """Shut down the aiocoap context, HTTP callback server, and event loop.

        Deletes CSE subscriptions before stopping to prevent stale deliveries
        to port 8090 from interfering with the next protocol client that uses
        the same callback URL (e.g. switching from CoAP to HTTP and back).
        Also makes reconnect faster: DELETE in _ensure_subscription gets 404
        immediately instead of waiting for the CSE to confirm deletion.
        """
        # Delete subscriptions in the CSE before shutting down the context.
        # Must be done while the loop and context are still running.
        if self._loop and self._coap_context:
            for container_path, rn in [
                ("cse-in/uxv/telemetry", _SUB_TEL_RN),
                ("cse-in/uxv/ack", _SUB_ACK_RN),
            ]:
                uri = f"{self._config.cse_coap_base}/{container_path}/{rn}"
                del_fut = asyncio.run_coroutine_threadsafe(
                    self._async_delete(uri), self._loop
                )
                try:
                    del_fut.result(timeout=5.0)
                except Exception:
                    pass

        if self._loop and self._coap_context:
            try:
                asyncio.run_coroutine_threadsafe(
                    self._coap_context.shutdown(), self._loop
                ).result(timeout=5.0)
            except Exception:
                pass
        if self._loop:
            try:
                self._loop.call_soon_threadsafe(self._loop.stop)
            except Exception:
                pass
        if self._loop_thread:
            self._loop_thread.join(timeout=5.0)
        if self._callback_server:
            try:
                self._callback_server.shutdown()
            except Exception:
                pass
        if self._callback_thread:
            self._callback_thread.join(timeout=3.0)
        # Clear all references so connect() can re-initialise cleanly.
        self._coap_context = None
        self._loop = None
        self._loop_thread = None
        self._callback_server = None
        self._callback_thread = None
        self._tel_sub_ri = None
        self._ack_sub_ri = None

    def get_header_bytes(self, payload_size: int) -> int:
        """Return header overhead in bytes for the current scenario.

        S2 (send_command path, outgoing CoAP CON POST):
            Static CoAP header estimate per RFC 7252 —
            4-byte fixed header + 8-byte token + Uri-Host option (2+len(host))
            + Uri-Port/Uri-Path/Content-Format options (~10 bytes) + payload marker (1 byte).
            This is the correct overhead for the command CIN create request.

        S1 (telemetry notification path):
            Notifications arrive via HTTP/TCP (Docker Desktop blocks CoAP/UDP from
            containers — see module docstring). Returns the measured incoming HTTP
            notification header size captured on the first delivery, or falls back
            to the CoAP static estimate if no notification has arrived yet.

        Parameters
        ----------
        payload_size:
            Size of the CIN payload in bytes (unused in static estimate path).

        Returns
        -------
        int
            Header overhead in bytes.
        """
        # If notification headers have been captured (S1 path), return those —
        # they represent the actual protocol overhead for telemetry delivery.
        if self._notif_headers_bytes > 0:
            return self._notif_headers_bytes
        # Fallback: static CoAP outgoing request estimate (S2 path / pre-first-notification).
        host_len = len(self._config.cse_host)
        return 4 + 8 + (2 + host_len) + 10 + 1

    # ------------------------------------------------------------------
    # Async internals (outgoing CoAP requests)
    # ------------------------------------------------------------------

    def _run_loop(self) -> None:
        """Entry point for the background asyncio thread."""
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()

    async def _async_connect(self) -> None:
        """Initialise aiocoap client context for outgoing requests only.

        No server binding — notifications arrive via the HTTP callback server.
        """
        self._coap_context = await aiocoap.Context.create_client_context()

    async def _async_post(
        self, uri: str, payload: bytes, ty: int = 0
    ) -> tuple[int, bytes]:
        """Send a CON POST with oneM2M CoAP options.

        Parameters
        ----------
        uri:
            Full CoAP URI including path.
        payload:
            Resource representation body (e.g. b'{"m2m:cin": {...}}').
        ty:
            oneM2M resource type (option 267). Pass 0 to omit.

        Returns
        -------
        tuple[int, bytes]
            (oneM2M RSC from option 307, raw response payload bytes).
            RSC = 0 if option 307 not present.
        """
        rqi = str(uuid.uuid4())[:8]
        request = aiocoap.Message(code=aiocoap.POST, uri=uri, payload=payload)
        request.opt.content_format = 50  # application/json

        # Mandatory oneM2M CoAP options (TS-0010 binding)
        request.opt.add_option(OpaqueOption(_OPT_FR,  _ORIGINATOR.encode()))
        request.opt.add_option(OpaqueOption(_OPT_RQI, rqi.encode()))
        request.opt.add_option(OpaqueOption(_OPT_RVI, b"3"))
        if ty > 0:
            request.opt.add_option(UintOption(_OPT_TY, ty))

        try:
            response = await asyncio.wait_for(
                self._coap_context.request(request).response,
                timeout=_REQUEST_TIMEOUT_S,
            )
            rsc_opts = response.opt.get_option(_OPT_RSC)
            rsc = 0
            if rsc_opts:
                raw = rsc_opts[0].value  # OpaqueOption → bytes
                rsc = int.from_bytes(raw, "big") if raw else 0
            return rsc, response.payload
        except asyncio.TimeoutError:
            raise TimeoutError("CoAP request timeout")

    def _ensure_subscription(self, container_path: str, rn: str) -> Optional[str]:
        """Delete existing SUB + create fresh one via CoAP; return subscription ri.

        The ri is captured from the CSE response body and used later to match
        the sur field in incoming HTTP notifications.

        Parameters
        ----------
        container_path:
            CSE-relative path to the container (e.g. "cse-in/uxv/telemetry").
        rn:
            Resource name for the subscription.

        Returns
        -------
        Optional[str]
            The ri of the created subscription, or None on failure.
        """
        # Delete existing (best-effort — may not exist).
        del_uri = f"{self._config.cse_coap_base}/{container_path}/{rn}"
        del_future = asyncio.run_coroutine_threadsafe(
            self._async_delete(del_uri), self._loop
        )
        try:
            del_future.result(timeout=5.0)
        except Exception:
            pass

        # Create subscription with HTTP callback nu (CSE container can reach via TCP).
        body = json.dumps({
            "m2m:sub": {
                "rn": rn,
                # nu uses docker_callback_host (host.docker.internal) because
                # Docker Desktop blocks UDP but routes TCP to the host.
                # CoAP notifications arrive via HTTP (lab constraint — see module docstring).
                "nu": [self._config.callback_http_docker_url],
                "enc": {"net": [3]},
            }
        }).encode()
        create_uri = f"{self._config.cse_coap_base}/{container_path}"
        future = asyncio.run_coroutine_threadsafe(
            self._async_post(create_uri, body, ty=23),  # ty=23 = m2m:sub
            self._loop,
        )
        try:
            rsc, resp_payload = future.result(timeout=_REQUEST_TIMEOUT_S)
            print(
                f"[{time.strftime('%H:%M:%S')}][CoAP] subscribe {container_path}/{rn} rsc={rsc} "
                f"nu={self._config.callback_http_docker_url}",
                flush=True,
            )
            if rsc == 2001 and resp_payload:
                try:
                    body_dict = json.loads(resp_payload.decode())
                    ri = body_dict.get("m2m:sub", {}).get("ri")
                    return ri
                except (json.JSONDecodeError, UnicodeDecodeError):
                    pass
            elif rsc in (4000, 4001, 4005):
                # Conflict — DELETE timed out, old subscription still exists.
                # GET it to retrieve its ri so notifications can be matched.
                print(
                    f"[{time.strftime('%H:%M:%S')}][CoAP] subscribe conflict rsc={rsc} — retrieving existing ri",
                    flush=True,
                )
                return self._get_sub_ri(container_path, rn)
        except Exception as exc:
            print(f"[{time.strftime('%H:%M:%S')}][CoAP] subscribe {container_path}/{rn} error: {exc!r}", flush=True)
        return None

    def _get_sub_ri(self, container_path: str, rn: str) -> Optional[str]:
        """GET an existing subscription by path and return its ri.

        Used as a fallback when CREATE returns conflict (4000/4001/4005),
        meaning the previous DELETE timed out and the subscription still exists.
        We reuse the existing subscription's ri so notification matching works.

        Parameters
        ----------
        container_path:
            CSE-relative container path (e.g. "cse-in/uxv/telemetry").
        rn:
            Subscription resource name.

        Returns
        -------
        Optional[str]
            The ri from the existing subscription, or None on failure.
        """
        uri = f"{self._config.cse_coap_base}/{container_path}/{rn}"
        future = asyncio.run_coroutine_threadsafe(
            self._async_get(uri), self._loop
        )
        try:
            rsc, payload = future.result(timeout=_REQUEST_TIMEOUT_S)
            if rsc in (2000, 2001) and payload:
                body = json.loads(payload.decode())
                ri = body.get("m2m:sub", {}).get("ri")
                print(f"[{time.strftime('%H:%M:%S')}][CoAP] existing sub ri={ri}", flush=True)
                return ri
        except Exception as exc:
            print(f"[{time.strftime('%H:%M:%S')}][CoAP] get existing sub error: {exc!r}", flush=True)
        return None

    async def _async_get(self, uri: str) -> tuple[int, bytes]:
        """Send a CON GET with oneM2M options; return (rsc, payload)."""
        rqi = str(uuid.uuid4())[:8]
        request = aiocoap.Message(code=aiocoap.GET, uri=uri)
        request.opt.add_option(OpaqueOption(_OPT_FR,  _ORIGINATOR.encode()))
        request.opt.add_option(OpaqueOption(_OPT_RQI, rqi.encode()))
        request.opt.add_option(OpaqueOption(_OPT_RVI, b"3"))
        try:
            response = await asyncio.wait_for(
                self._coap_context.request(request).response,
                timeout=_REQUEST_TIMEOUT_S,
            )
            rsc_opts = response.opt.get_option(_OPT_RSC)
            rsc = 0
            if rsc_opts:
                raw = rsc_opts[0].value
                rsc = int.from_bytes(raw, "big") if raw else 0
            return rsc, response.payload
        except asyncio.TimeoutError:
            raise TimeoutError("CoAP GET timeout")

    async def _async_delete(self, uri: str) -> None:
        """Send a CON DELETE with oneM2M CoAP options (best-effort)."""
        rqi = str(uuid.uuid4())[:8]
        request = aiocoap.Message(code=aiocoap.DELETE, uri=uri)
        request.opt.add_option(OpaqueOption(_OPT_FR,  _ORIGINATOR.encode()))
        request.opt.add_option(OpaqueOption(_OPT_RQI, rqi.encode()))
        request.opt.add_option(OpaqueOption(_OPT_RVI, b"3"))
        try:
            await asyncio.wait_for(
                self._coap_context.request(request).response,
                timeout=3.0,
            )
        except Exception:
            pass

    # ------------------------------------------------------------------
    # HTTP callback server (receives CSE notifications)
    # ------------------------------------------------------------------

    def _start_callback_server(self) -> None:
        """Start the HTTP callback server on 0.0.0.0:callback_http_port."""
        client = self

        class _NotifyHandler(BaseHTTPRequestHandler):
            """Handles CSE notification POSTs delivered via HTTP."""
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                body_bytes = self.rfile.read(length)
                self.send_response(200)
                self.end_headers()

                # Capture notification header size once on first delivery.
                # These represent the actual overhead for notifications in S1
                # (HTTP/TCP used because Docker Desktop blocks CoAP/UDP).
                if client._notif_headers_bytes == 0:
                    client._notif_headers_bytes = _measure_notification_headers(self)

                try:
                    body = json.loads(body_bytes.decode())
                except (json.JSONDecodeError, UnicodeDecodeError):
                    return
                client._dispatch_notification(body)

            def log_message(self, fmt, *args):
                pass  # suppress access log noise in Streamlit terminal

        self._callback_server = HTTPServer(
            ("0.0.0.0", self._config.callback_http_port),
            _NotifyHandler,
        )
        self._callback_thread = threading.Thread(
            target=self._callback_server.serve_forever,
            daemon=True,
            name="coap-http-callback",
        )
        self._callback_thread.start()

    def _dispatch_notification(self, body: dict) -> None:
        """Parse a CSE notification POST and call the appropriate callback.

        ACME CSE puts the subscription ri (e.g. /id-in/subXXX) in the sur
        field, not the human-readable path. Match against ri captured at
        connect time.

        Parameters
        ----------
        body:
            Parsed JSON body of the notification POST.
        """
        sgn = body.get("m2m:sgn", {})
        sur = sgn.get("sur", "")

        # Subscription verification request — HTTP 200 already sent; skip.
        if sgn.get("vrq"):
            print(f"[{time.strftime('%H:%M:%S')}][CoAP] vrq sur={sur!r} — 200 already sent", flush=True)
            return

        nev = sgn.get("nev", {})
        rep = nev.get("rep", {})
        cin = rep.get("m2m:cin", {})
        con_raw = cin.get("con", "")

        try:
            con = json.loads(con_raw) if isinstance(con_raw, str) else con_raw
        except (json.JSONDecodeError, TypeError):
            return

        is_tel = self._tel_sub_ri is not None and self._tel_sub_ri in sur
        is_ack = self._ack_sub_ri is not None and self._ack_sub_ri in sur
        if is_ack or (not is_tel and not is_ack):
            print(f"[{time.strftime('%H:%M:%S')}][CoAP] notify sur={sur!r} is_tel={is_tel} is_ack={is_ack}", flush=True)

        if is_tel and self._telemetry_cb:
            try:
                self._telemetry_cb(con)
            except Exception as exc:
                print(f"[{time.strftime('%H:%M:%S')}][CoAP] telemetry_cb raised: {exc!r}", flush=True)
        elif is_ack and self._ack_cb:
            try:
                self._ack_cb(con)
            except Exception as exc:
                print(f"[{time.strftime('%H:%M:%S')}][CoAP] ack_cb raised: {exc!r}", flush=True)
