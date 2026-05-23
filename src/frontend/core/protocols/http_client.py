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
        1. connect() starts an HTTPServer on callback_host:callback_http_port
        2. Creates SUBs with nu=[callback_http_url]
        3. CSE POSTs notifications (JSON) to callback_http_url
        4. The embedded server dispatches to telemetry_cb / ack_cb

    The CSE Docker container must have a route to callback_host — use the
    host's LAN IP in .env (CALLBACK_HOST), not 127.0.0.1.
    """

    def __init__(self, config: Config) -> None:
        self._config = config
        self._session = requests.Session()
        self._telemetry_cb: Optional[Callable[[dict], None]] = None
        self._ack_cb: Optional[Callable[[dict], None]] = None
        self._callback_server: Optional[HTTPServer] = None
        self._callback_thread: Optional[threading.Thread] = None

        # Last raw response for header-size measurement.
        self._last_request_headers_bytes = 0
        self._last_response_headers_bytes = 0

    # ------------------------------------------------------------------
    # ProtocolClient interface
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Start the callback HTTP server and create CSE subscriptions."""
        self._start_callback_server()
        self._ensure_subscription(
            f"{self._config.cse_http_base}/cse-in/uxv/telemetry",
            _SUB_TEL_RN,
        )
        self._ensure_subscription(
            f"{self._config.cse_http_base}/cse-in/uxv/ack",
            _SUB_ACK_RN,
        )

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
        """Stop the callback server."""
        if self._callback_server:
            self._callback_server.shutdown()
        if self._callback_thread:
            self._callback_thread.join(timeout=3.0)
        self._session.close()

    def get_header_bytes(self, payload_size: int) -> int:
        """Return the sum of request + response header bytes for the last send_command call.

        HTTP header overhead includes the request headers sent and the
        response headers received (both are protocol overhead for the exchange).
        """
        return self._last_request_headers_bytes + self._last_response_headers_bytes

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

    def _ensure_subscription(self, container_url: str, rn: str) -> None:
        """Delete existing subscription and create a fresh one."""
        sub_url = f"{container_url}/{rn}"
        # Try to delete (ignore errors — may not exist).
        rqi = str(uuid.uuid4())
        self._session.delete(
            sub_url,
            headers=self._m2m_headers(rqi),
            timeout=5.0,
        )

        # Create subscription pointing to our callback server.
        rqi = str(uuid.uuid4())
        body = {
            "m2m:sub": {
                "rn": rn,
                # nu: the CSE will POST notifications to this URL.
                # The callback URL must be reachable from the CSE Docker container.
                "nu": [self._config.callback_http_url],
                "enc": {"net": [3]},  # notify on resource creation
            }
        }
        try:
            self._session.post(
                container_url,
                json=body,
                headers=self._m2m_headers(rqi, content_type="application/json;ty=23"),
                timeout=_REQUEST_TIMEOUT_S,
            )
        except requests.RequestException:
            pass

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
        """Parse a CSE notification POST and call the appropriate callback."""
        # Standard oneM2M HTTP notification body: {"m2m:sgn": {...}}
        sgn = body.get("m2m:sgn", {})

        # Subscription verification request (CSE sends this once after SUB creation).
        if sgn.get("vrq"):
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

        if "telemetry" in sur and self._telemetry_cb:
            self._telemetry_cb(con)
        elif "ack" in sur and self._ack_cb:
            self._ack_cb(con)

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
