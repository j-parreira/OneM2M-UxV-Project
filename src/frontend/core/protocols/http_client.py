"""OneM2M HTTP protocol client for ACME CSE v2025.11.

Uses the standard HTTP REST binding. Notifications are received via an
embedded HTTP server (thread-based) that the CSE POSTs to. The CSE container
must be able to reach this server's IP — use the LAN IP, not 127.0.0.1.

Resource paths follow the /cse-in/ prefix (verified against ACME CSE v2025.11).
See docs/ai-context/cse-dev.md § URL Structure.

HTTP headers per OneM2M TS-0009:
  X-M2M-Origin: CAdmin    (admin originator — no AE registration needed)
  X-M2M-RI: <request-id>
  X-M2M-RVI: 3
  Content-Type: application/json;ty=<resource-type>

Authors: João Parreira, Pedro Barbeiro
"""
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Callable, Optional

import requests

from .base import ProtocolClient
from ..config import Config

_ORIGINATOR = "CAdmin"
_SUB_TEL_RN = "sub-http-streamlit-tel"
_SUB_ACK_RN = "sub-http-streamlit-ack"

_REQUEST_TIMEOUT_S = 10.0


class HttpClient(ProtocolClient):
    """HTTP OneM2M client with embedded push notification server.

    Notification flow:
        1. connect() starts an HTTPServer on 0.0.0.0:callback_http_port
        2. Creates SUBs with nu=[callback_http_docker_url]  (host.docker.internal)
        3. CSE POSTs notifications (JSON) to that URL
        4. The embedded server dispatches to telemetry_cb / ack_cb

    The nu URL must use docker_callback_host (host.docker.internal) so the
    CSE container can reach the callback server via TCP. The LAN IP
    (CALLBACK_HOST) is not reachable from Docker Desktop containers.
    """

    def __init__(self, config: Config) -> None:
        self._config = config
        self._session = requests.Session()
        self._telemetry_cb: Optional[Callable[[dict], None]] = None
        self._ack_cb: Optional[Callable[[dict], None]] = None
        self._callback_server: Optional[HTTPServer] = None
        self._callback_thread: Optional[threading.Thread] = None

        # Header-size tracking for get_header_bytes().
        # S2 (send_command): sum of request + response headers, updated each call.
        self._last_request_headers_bytes = 0
        self._last_response_headers_bytes = 0
        # S1 (telemetry notifications): incoming HTTP POST header size.
        # Captured once on first notification (ACME CSE notification headers are
        # structurally constant between messages), then reused for all records.
        self._notif_headers_bytes: int = 0

        # ri of each subscription — ACME CSE puts the ri (e.g. /id-in/subXXX),
        # not the human-readable path, in the `sur` field of notifications.
        self._tel_sub_ri: Optional[str] = None
        self._ack_sub_ri: Optional[str] = None

    # ------------------------------------------------------------------
    # ProtocolClient interface
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Start the callback HTTP server and create CSE subscriptions."""
        self._start_callback_server()
        self._tel_sub_ri = self._ensure_subscription(
            f"{self._config.cse_http_base}/cse-in/uxv/telemetry",
            _SUB_TEL_RN,
        )
        self._ack_sub_ri = self._ensure_subscription(
            f"{self._config.cse_http_base}/cse-in/uxv/ack",
            _SUB_ACK_RN,
        )
        if self._tel_sub_ri is None:
            print(f"[{time.strftime('%H:%M:%S')}][HTTP] WARNING: telemetry subscription ri not captured", flush=True)
        if self._ack_sub_ri is None:
            print(f"[{time.strftime('%H:%M:%S')}][HTTP] WARNING: ack subscription ri not captured", flush=True)
        print(f"[{time.strftime('%H:%M:%S')}][HTTP] tel_sub_ri={self._tel_sub_ri}  ack_sub_ri={self._ack_sub_ri}", flush=True)

    def send_command(self, payload: dict) -> tuple[float | None, bool]:
        """POST a command CIN to /cse-in/uxv/commands."""
        con_str = json.dumps(payload)
        body = {"m2m:cin": {"con": con_str}}
        url = f"{self._config.cse_http_base}/cse-in/uxv/commands"
        rqi = str(uuid.uuid4())

        t_start = time.monotonic_ns()
        try:
            resp = self._session.post(
                url,
                json=body,
                headers=self._m2m_headers(rqi, content_type="application/json;ty=4"),
                timeout=_REQUEST_TIMEOUT_S,
            )
            t_end = time.monotonic_ns()
            latency_ms = (t_end - t_start) / 1_000_000
            # Capture header sizes for overhead metric.
            self._last_request_headers_bytes = self._measure_request_headers(resp.request)
            self._last_response_headers_bytes = self._measure_response_headers(resp)
            delivered = resp.status_code == 201
            return latency_ms, delivered
        except requests.Timeout:
            return None, False
        except requests.RequestException:
            return None, False

    def subscribe_telemetry(self, callback: Callable[[dict], None]) -> None:
        self._telemetry_cb = callback

    def subscribe_ack(self, callback: Callable[[dict], None]) -> None:
        self._ack_cb = callback

    def disconnect(self) -> None:
        """Delete CSE subscriptions, then stop the callback server.

        Deleting subscriptions prevents stale deliveries to port 8090 when
        switching to another protocol client that uses the same callback URL.
        Must be done before stopping the callback server so the CSE doesn't
        retry delivery after a partial teardown.
        """
        for sub_url in [
            f"{self._config.cse_http_base}/cse-in/uxv/telemetry/{_SUB_TEL_RN}",
            f"{self._config.cse_http_base}/cse-in/uxv/ack/{_SUB_ACK_RN}",
        ]:
            try:
                self._session.delete(
                    sub_url,
                    headers=self._m2m_headers(str(uuid.uuid4())),
                    timeout=3.0,
                )
            except Exception:
                pass

        if self._callback_server:
            self._callback_server.shutdown()
        if self._callback_thread:
            self._callback_thread.join(timeout=3.0)
        self._session.close()

    def get_header_bytes(self, payload_size: int) -> int:
        """Return HTTP header overhead in bytes.

        S2 (send_command path): sum of outgoing request headers + incoming
        response headers, updated after every send_command call.

        S1 (notification path): incoming notification POST headers, captured
        once on first delivery and reused thereafter. Returns 0 until the
        first notification arrives.

        Both are correct per-message overheads for their respective directions.

        Parameters
        ----------
        payload_size:
            Unused. Kept for interface compatibility.

        Returns
        -------
        int
            Header overhead in bytes for the most recent exchange.
        """
        # Prefer outgoing command headers if available (S2), else notification headers (S1).
        cmd_headers = self._last_request_headers_bytes + self._last_response_headers_bytes
        if cmd_headers > 0:
            return cmd_headers
        return self._notif_headers_bytes

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _m2m_headers(self, rqi: str, content_type: str = "application/json") -> dict:
        """Build the mandatory OneM2M HTTP headers."""
        return {
            "X-M2M-Origin": _ORIGINATOR,
            "X-M2M-RI": rqi,
            "X-M2M-RVI": "3",
            "Content-Type": content_type,
            "Accept": "application/json",
        }

    def _ensure_subscription(self, container_url: str, rn: str) -> Optional[str]:
        """Delete existing subscription and create a fresh one.

        Returns:
            The ``ri`` of the created subscription, used to match the ``sur``
            field in incoming notifications (ACME CSE puts ri, not path, in sur).
            Returns None on failure.
        """
        sub_url = f"{container_url}/{rn}"
        # Try to delete (ignore errors — may not exist).
        rqi = str(uuid.uuid4())
        try:
            self._session.delete(
                sub_url,
                headers=self._m2m_headers(rqi),
                timeout=5.0,
            )
        except requests.RequestException:
            pass

        # Create subscription pointing to our callback server.
        rqi = str(uuid.uuid4())
        body = {
            "m2m:sub": {
                "rn": rn,
                # nu must be reachable from INSIDE the CSE Docker container.
                # callback_http_docker_url uses host.docker.internal (TCP, reachable).
                # callback_http_url uses the LAN IP, which Docker Desktop blocks.
                "nu": [self._config.callback_http_docker_url],
                "enc": {"net": [3]},  # notify on resource creation
            }
        }
        try:
            resp = self._session.post(
                container_url,
                json=body,
                headers=self._m2m_headers(rqi, content_type="application/json;ty=23"),
                timeout=_REQUEST_TIMEOUT_S,
            )
            rsc = resp.headers.get("X-M2M-RSC")
            print(f"[{time.strftime('%H:%M:%S')}][HTTP] subscribe {container_url}/{rn} status={resp.status_code} rsc={rsc}", flush=True)
            if resp.status_code == 201:
                ri = resp.json().get("m2m:sub", {}).get("ri")
                return ri
        except requests.RequestException as exc:
            print(f"[{time.strftime('%H:%M:%S')}][HTTP] subscribe {container_url}/{rn} error: {exc!r}", flush=True)
        return None

    def _start_callback_server(self) -> None:
        """Start the HTTP callback server in a daemon thread."""
        # Pass callbacks to the handler via closure.
        client = self

        class _NotifyHandler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                body_bytes = self.rfile.read(length)
                self.send_response(200)
                self.end_headers()

                # Capture incoming notification header size once (first delivery).
                # ACME CSE notification headers are structurally identical between
                # messages, so a single measurement is representative.
                if client._notif_headers_bytes == 0:
                    client._notif_headers_bytes = client._measure_notification_headers(self)

                try:
                    body = json.loads(body_bytes.decode())
                except (json.JSONDecodeError, UnicodeDecodeError):
                    return
                client._dispatch_notification(body)

            def log_message(self, fmt, *args):
                pass  # suppress access log noise in the Streamlit terminal

        self._callback_server = HTTPServer(
            ("0.0.0.0", self._config.callback_http_port),
            _NotifyHandler,
        )
        self._callback_thread = threading.Thread(
            target=self._callback_server.serve_forever,
            daemon=True,
            name="http-callback",
        )
        self._callback_thread.start()

    def _dispatch_notification(self, body: dict) -> None:
        """Parse a CSE notification POST and call the appropriate callback.

        ACME CSE puts the subscription ri (e.g. /id-in/subXXX) in the ``sur``
        field, NOT the human-readable resource path. We match against the ri
        captured at connect time, consistent with WS and MQTT clients.
        """
        # Standard oneM2M HTTP notification body: {"m2m:sgn": {...}}
        sgn = body.get("m2m:sgn", {})
        sur = sgn.get("sur", "")

        # Subscription verification request — HTTP 200 already sent by do_POST; nothing else needed.
        if sgn.get("vrq"):
            print(f"[{time.strftime('%H:%M:%S')}][HTTP] vrq sur={sur!r} — 200 already sent", flush=True)
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
            print(f"[{time.strftime('%H:%M:%S')}][HTTP] notify sur={sur!r} is_tel={is_tel} is_ack={is_ack}", flush=True)

        if is_tel and self._telemetry_cb:
            try:
                self._telemetry_cb(con)
            except Exception as exc:
                print(f"[{time.strftime('%H:%M:%S')}][HTTP] telemetry_cb raised: {exc!r}", flush=True)
        elif is_ack and self._ack_cb:
            try:
                self._ack_cb(con)
            except Exception as exc:
                print(f"[{time.strftime('%H:%M:%S')}][HTTP] ack_cb raised: {exc!r}", flush=True)

    @staticmethod
    def _measure_notification_headers(handler: BaseHTTPRequestHandler) -> int:
        """Estimate incoming HTTP notification POST header bytes.

        Reconstructs the raw HTTP request line + headers from the BaseHTTPRequestHandler
        so the header overhead reflects the actual notification delivery (S1 path),
        not the outgoing command request (S2 path).

        Parameters
        ----------
        handler:
            The BaseHTTPRequestHandler instance for the incoming POST.

        Returns
        -------
        int
            Estimated header bytes (request line + all header fields + CRLF terminators).
        """
        try:
            lines = [f"POST {handler.path} HTTP/1.1"]
            for k, v in handler.headers.items():
                lines.append(f"{k}: {v}")
            return sum(len((line + "\r\n").encode()) for line in lines) + 2
        except Exception:
            return 0

    @staticmethod
    def _measure_request_headers(prepared_request) -> int:
        """Estimate HTTP request header bytes from a PreparedRequest."""
        try:
            # Reconstruct the raw request line + headers.
            lines = [f"{prepared_request.method} {prepared_request.path_url} HTTP/1.1"]
            for k, v in prepared_request.headers.items():
                lines.append(f"{k}: {v}")
            return sum(len((line + "\r\n").encode()) for line in lines) + 2  # trailing CRLF
        except Exception:
            return 0

    @staticmethod
    def _measure_response_headers(response) -> int:
        """Estimate HTTP response header bytes from a requests.Response."""
        try:
            lines = [f"HTTP/1.1 {response.status_code} {response.reason}"]
            for k, v in response.headers.items():
                lines.append(f"{k}: {v}")
            return sum(len((line + "\r\n").encode()) for line in lines) + 2
        except Exception:
            return 0
