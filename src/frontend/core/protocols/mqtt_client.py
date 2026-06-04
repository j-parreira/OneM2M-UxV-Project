"""OneM2M MQTT protocol client for ACME CSE v2025.11.

MQTT topic structure (empirically verified — originator FIRST in resp topic):
  Requests:      /oneM2M/req/{originator}/{cseID}/json
  Responses:     /oneM2M/resp/{originator}/{cseID}/json
  Notifications: /oneM2M/req/{cseID}/{originator}/json

The ACME CSE is an MQTT *client* to Mosquitto, not the broker.
The CSE receives requests via Mosquitto and delivers responses/notifications
the same way. See docs/ai-context/cse-dev.md § MQTT Topic Structure.

Message body: same flat JSON as WebSocket (no m2m:rqp wrapper).
Library: paho-mqtt 2.1.0 (MQTTv5 available; using v3.1.1 for compatibility).

Authors: João Parreira, Pedro Barbeiro
"""
import json
import threading
import time
import uuid
from typing import Callable, Optional

import paho.mqtt.client as mqtt

from .base import ProtocolClient
from ..config import Config

_ORIGINATOR = "CStreamlit"
_CSE_ID = "id-in"   # matches acme.ini cseID

_TOPIC_REQ = f"/oneM2M/req/{_ORIGINATOR}/{_CSE_ID}/json"
_TOPIC_RESP = f"/oneM2M/resp/{_ORIGINATOR}/{_CSE_ID}/json"
_TOPIC_NOTIF = f"/oneM2M/req/{_CSE_ID}/{_ORIGINATOR}/json"

_SUB_TEL_RN = "sub-streamlit-tel"
_SUB_ACK_RN = "sub-streamlit-ack"
_AE_RN = "streamlit"

_REQUEST_TIMEOUT_S = 10.0
_CONNECT_TIMEOUT_S = 15.0


class MqttClient(ProtocolClient):
    """MQTT OneM2M client.

    All OneM2M requests are published as flat JSON on _TOPIC_REQ.
    Responses arrive on _TOPIC_RESP; notifications on _TOPIC_NOTIF.

    Lifecycle:
        client = MqttClient(config)
        client.subscribe_telemetry(cb)
        client.subscribe_ack(cb)
        client.connect()
        client.send_command({...})
        client.disconnect()
    """

    def __init__(self, config: Config) -> None:
        self._config = config
        self._telemetry_cb: Optional[Callable[[dict], None]] = None
        self._ack_cb: Optional[Callable[[dict], None]] = None

        self._lock = threading.Lock()
        self._pending: dict[str, threading.Event] = {}
        self._responses: dict[str, Optional[dict]] = {}

        self._connected_event = threading.Event()
        self._seq = 0

        self._client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION1,
            client_id=f"streamlit-{uuid.uuid4().hex[:8]}",
            protocol=mqtt.MQTTv311,
        )
        self._client.on_connect = self._on_connect
        self._client.on_message = self._on_message
        self._client.on_disconnect = self._on_disconnect

        # Track last publish payload size for header overhead calculation.
        self._last_payload_bytes = 0

        # ri of each subscription — matched against `sur` in notifications.
        # ACME CSE puts the auto-generated ri, not the human-readable path, in `sur`.
        self._tel_sub_ri: Optional[str] = None
        self._ack_sub_ri: Optional[str] = None

    # ------------------------------------------------------------------
    # ProtocolClient interface
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Connect to Mosquitto and set up the OneM2M session."""
        self._client.connect(
            host=self._config.cse_host,
            port=self._config.cse_mqtt_port,
            keepalive=60,
        )
        self._client.loop_start()   # background network thread

        if not self._connected_event.wait(timeout=_CONNECT_TIMEOUT_S):
            raise ConnectionError("MQTT connect timeout")

        # Subscribe to response and notification topics before sending any requests.
        self._client.subscribe(_TOPIC_RESP, qos=1)
        self._client.subscribe(_TOPIC_NOTIF, qos=1)

        print(f"[{time.strftime('%H:%M:%S')}][MQTT] connected — registering AE and subscriptions", flush=True)
        self._register_ae()
        self._tel_sub_ri = self._ensure_subscription("cse-in/uxv/telemetry", _SUB_TEL_RN)
        self._ack_sub_ri = self._ensure_subscription("cse-in/uxv/ack", _SUB_ACK_RN)
        if self._tel_sub_ri is None:
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] WARNING: telemetry subscription ri not captured", flush=True)
        if self._ack_sub_ri is None:
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] WARNING: ack subscription ri not captured", flush=True)
        print(f"[{time.strftime('%H:%M:%S')}][MQTT] tel_sub_ri={self._tel_sub_ri}  ack_sub_ri={self._ack_sub_ri}", flush=True)

    def send_command(self, payload: dict) -> tuple[float | None, bool]:
        con_str = json.dumps(payload)
        rqi = self._next_rqi()
        req = {
            "op": 1,
            "to": "cse-in/uxv/commands",
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
            "ty": 4,
            "pc": {"m2m:cin": {"con": con_str}},
        }
        t_start = time.monotonic_ns()
        try:
            resp = self._publish_request(req, timeout=_REQUEST_TIMEOUT_S)
            t_end = time.monotonic_ns()
            latency_ms = (t_end - t_start) / 1_000_000
            delivered = resp.get("rsc") == 2001 if resp else False
            return latency_ms, delivered
        except TimeoutError:
            return None, False

    def subscribe_telemetry(self, callback: Callable[[dict], None]) -> None:
        self._telemetry_cb = callback

    def subscribe_ack(self, callback: Callable[[dict], None]) -> None:
        self._ack_cb = callback

    def disconnect(self) -> None:
        """Delete CSE subscriptions, then stop the MQTT client.

        Deleting subscriptions before disconnecting prevents a 4005 CONFLICT
        on the next connect() when _ensure_subscription() tries to create a
        subscription with the same rn that's still in the CSE.
        """
        for sub_path in [
            f"cse-in/uxv/telemetry/{_SUB_TEL_RN}",
            f"cse-in/uxv/ack/{_SUB_ACK_RN}",
        ]:
            try:
                self._delete_resource(sub_path)
            except Exception:
                pass
        self._client.loop_stop()
        self._client.disconnect()

    def get_header_bytes(self, payload_size: int) -> int:
        """MQTT fixed header + variable-length remaining length + topic.

        Fixed header: 2 bytes minimum (packet type + remaining length byte).
        Remaining length field: 1–4 bytes (varint encoding).
        Topic: 2-byte length prefix + topic string.
        QoS 1 adds a 2-byte packet identifier.
        """
        topic_len = len(_TOPIC_REQ.encode())
        # Remaining length = topic_len_field(2) + topic_bytes + pkt_id(2) + payload
        remaining = 2 + topic_len + 2 + payload_size
        # Encode remaining length as MQTT varint (1–4 bytes)
        varint_bytes = 1
        val = remaining
        while val > 127:
            val >>= 7
            varint_bytes += 1
        return 1 + varint_bytes + 2 + topic_len + 2   # fixed_byte + varint + topic_field + pkt_id

    # ------------------------------------------------------------------
    # MQTT callbacks
    # ------------------------------------------------------------------

    def _on_connect(self, client, userdata, flags, rc):
        print(f"[{time.strftime('%H:%M:%S')}][MQTT] on_connect rc={rc}", flush=True)
        if rc == 0:
            self._connected_event.set()

    def _on_disconnect(self, client, userdata, rc):
        self._connected_event.clear()

    def _on_message(self, client, userdata, message):
        try:
            body = json.loads(message.payload.decode())
        except (json.JSONDecodeError, UnicodeDecodeError):
            return

        topic = message.topic

        if topic == _TOPIC_RESP:
            # Response to one of our requests
            rqi = body.get("rqi")
            with self._lock:
                if rqi in self._pending:
                    self._responses[rqi] = body
                    self._pending[rqi].set()

        elif topic == _TOPIC_NOTIF:
            # Notification from CSE (subscription event or verification request)
            self._handle_notify(body)

    def _handle_notify(self, msg: dict) -> None:
        """Dispatch subscription notification to the registered callback."""
        pc = msg.get("pc", {})
        sgn = pc.get("m2m:sgn", {})

        # Verification request — ACK via MQTT response topic.
        if sgn.get("vrq"):
            rqi = msg.get("rqi", "")
            sur = sgn.get("sur", "")
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] vrq sur={sur!r} — ACKing on {_TOPIC_RESP}", flush=True)
            ack = {"rsc": 2000, "rqi": rqi, "to": _ORIGINATOR, "fr": _ORIGINATOR, "rvi": "3"}
            self._client.publish(_TOPIC_RESP, json.dumps(ack), qos=1)
            return

        nev = sgn.get("nev", {})
        rep = nev.get("rep", {})
        cin = rep.get("m2m:cin", {})
        con_raw = cin.get("con", "")
        sur = sgn.get("sur", "")

        try:
            con = json.loads(con_raw) if isinstance(con_raw, str) else con_raw
        except (json.JSONDecodeError, TypeError):
            return

        # ACME CSE puts the subscription ri (e.g. /id-in/subXXX) in `sur`, not the path.
        is_tel = self._tel_sub_ri is not None and self._tel_sub_ri in sur
        is_ack = self._ack_sub_ri is not None and self._ack_sub_ri in sur
        if is_ack or (not is_tel and not is_ack):
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] notify sur={sur!r} is_tel={is_tel} is_ack={is_ack}", flush=True)
        if is_tel and self._telemetry_cb:
            try:
                self._telemetry_cb(con)
            except Exception as exc:
                print(f"[{time.strftime('%H:%M:%S')}][MQTT] telemetry_cb raised: {exc!r}", flush=True)
        elif is_ack and self._ack_cb:
            try:
                self._ack_cb(con)
            except Exception as exc:
                print(f"[{time.strftime('%H:%M:%S')}][MQTT] ack_cb raised: {exc!r}", flush=True)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _next_rqi(self) -> str:
        with self._lock:
            self._seq += 1
            return f"sl-mqtt-{self._seq}-{uuid.uuid4().hex[:6]}"

    def _publish_request(self, request: dict, timeout: float) -> Optional[dict]:
        """Publish a request and wait for the matching response."""
        rqi = request["rqi"]
        event = threading.Event()
        with self._lock:
            self._pending[rqi] = event
            self._responses[rqi] = None

        payload = json.dumps(request)
        self._client.publish(_TOPIC_REQ, payload, qos=1)

        ok = event.wait(timeout=timeout)
        with self._lock:
            resp = self._responses.pop(rqi, None)
            self._pending.pop(rqi, None)

        if not ok:
            raise TimeoutError(f"No response for rqi={rqi}")
        return resp

    def _register_ae(self) -> None:
        """Register Streamlit as an AE (idempotent — 4105 Conflict is OK)."""
        rqi = self._next_rqi()
        req = {
            "op": 1,
            "to": "id-in",
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
            "ty": 2,
            "pc": {
                "m2m:ae": {
                    "rn": _AE_RN,
                    "api": "N.com.uxv.benchmark.streamlit",
                    "srv": ["3"],
                    "rr": True,
                    # poa for MQTT: the broker address the CSE will connect to when delivering
                    # subscription notifications. Must use mqtt_broker_poa_host ("mosquitto"
                    # by default) — NOT cse_host (127.0.0.1) — because the CSE resolves this
                    # address from inside its Docker container where 127.0.0.1 is the container's
                    # own loopback, not the host's Mosquitto broker.
                    "poa": [f"mqtt://{self._config.mqtt_broker_poa_host}:{self._config.cse_mqtt_port}"],
                }
            },
        }
        try:
            resp = self._publish_request(req, timeout=_REQUEST_TIMEOUT_S)
            rsc = resp.get("rsc") if resp else None
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] AE register rsc={rsc}", flush=True)
            # 4117 = ACME CSE v2025.11 "originator already registered" — treat as 4105.
            if rsc == 4105 or rsc == 4117:
                # AE exists from a previous session — update poa to current transport.
                self._update_ae_poa()
            elif rsc != 2001:
                raise RuntimeError(f"AE registration failed: rsc={rsc}")
        except TimeoutError:
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] AE register timed out — proceeding optimistically", flush=True)

    def _ensure_subscription(self, container_path: str, rn: str) -> Optional[str]:
        """Delete existing subscription (if any), create a fresh one, return its ri.

        Returns:
            The `ri` of the newly created subscription, or None on failure.
        """
        self._delete_resource(f"{container_path}/{rn}")

        rqi = self._next_rqi()
        req = {
            "op": 1,
            "to": container_path,
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
            "ty": 23,
            "pc": {
                "m2m:sub": {
                    "rn": rn,
                    "nu": [_ORIGINATOR],
                    "enc": {"net": [3]},
                }
            },
        }
        try:
            resp = self._publish_request(req, timeout=_REQUEST_TIMEOUT_S)
            rsc = resp.get("rsc") if resp else None
            ri = resp.get("pc", {}).get("m2m:sub", {}).get("ri") if resp else None
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] subscribe {container_path}/{rn} rsc={rsc} ri={ri}", flush=True)
            return ri if rsc == 2001 else None
        except TimeoutError:
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] subscribe {container_path}/{rn} timed out", flush=True)
            return None

    def _update_ae_poa(self) -> None:
        """UPDATE the AE's poa to MQTT transport.

        Called when the AE already exists (rsc=4105/4117) from a previous session
        that may have used a different transport (e.g. WebSocket). Without this,
        the CSE would deliver notifications via the old transport → KeyError.
        """
        rqi = self._next_rqi()
        req = {
            "op": 3,  # UPDATE
            "to": f"cse-in/{_AE_RN}",
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
            "pc": {
                "m2m:ae": {
                    "poa": [f"mqtt://{self._config.mqtt_broker_poa_host}:{self._config.cse_mqtt_port}"],
                }
            },
        }
        try:
            resp = self._publish_request(req, timeout=_REQUEST_TIMEOUT_S)
            rsc = resp.get("rsc") if resp else None
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] AE poa update rsc={rsc}", flush=True)
        except TimeoutError:
            print(f"[{time.strftime('%H:%M:%S')}][MQTT] AE poa update timed out", flush=True)

    def _delete_resource(self, resource_path: str) -> None:
        rqi = self._next_rqi()
        req = {
            "op": 4,
            "to": resource_path,
            "fr": _ORIGINATOR,
            "rqi": rqi,
            "rvi": "3",
        }
        try:
            self._publish_request(req, timeout=3.0)
        except (TimeoutError, Exception):
            pass
