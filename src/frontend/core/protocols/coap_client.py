"""OneM2M CoAP protocol client for ACME CSE v2025.11.

Uses Confirmable (CON) messages via aiocoap. No DTLS — not supported in v2025.11.

Notifications are received via an embedded aiocoap server (CoAP callback server)
running in a background asyncio event loop. The CSE container must be able to
reach this server at callback_host:callback_coap_port.

CoAP header overhead (fixed) per RFC 7252:
  4-byte fixed header + token (0–8 bytes) + options (variable) + payload marker

This module runs a private asyncio event loop in a background thread to
avoid conflicts with Streamlit's own event loop (if any).
"""
import asyncio
import json
import threading
import time
import uuid
from typing import Callable, Optional

import aiocoap
import aiocoap.resource as resource

from .base import ProtocolClient
from ..config import Config

_ORIGINATOR = "CAdmin"
_CSE_ID = "id-in"
_SUB_TEL_RN = "sub-coap-streamlit-tel"
_SUB_ACK_RN = "sub-coap-streamlit-ack"

_REQUEST_TIMEOUT_S = 10.0


class CoapClient(ProtocolClient):
    """CoAP OneM2M client with embedded aiocoap callback server.

    Notification flow:
        1. connect() starts an aiocoap server on callback_host:callback_coap_port
        2. Creates SUBs with nu=[coap://callback_host:callback_coap_port/notify]
        3. CSE sends CON POST to that URI
        4. The callback server dispatches to telemetry_cb / ack_cb

    The CoAP client uses Confirmable (CON) messages for all requests to
    ensure delivery confirmation. ACK latency measures the time until the
    CSE sends its CoAP ACK (not the oneM2M-level command ACK).

    All async operations run in a private background event loop thread so
    Streamlit's synchronous API is not affected.
    """

    def __init__(self, config: Config) -> None:
        self._config = config
        self._telemetry_cb: Optional[Callable[[dict], None]] = None
        self._ack_cb: Optional[Callable[[dict], None]] = None

        # Private asyncio event loop running in background thread.
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._loop_thread: Optional[threading.Thread] = None
        self._coap_context: Optional[aiocoap.Context] = None
        self._server_site: Optional[resource.Site] = None

    # ------------------------------------------------------------------
    # ProtocolClient interface
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """Start the asyncio event loop, CoAP context, and callback server."""
        self._loop = asyncio.new_event_loop()
        self._loop_thread = threading.Thread(
            target=self._run_loop, daemon=True, name="coap-loop"
        )
        self._loop_thread.start()

        # Initialise aiocoap context and callback server inside the loop.
        future = asyncio.run_coroutine_threadsafe(self._async_connect(), self._loop)
        future.result(timeout=_REQUEST_TIMEOUT_S)  # block until setup is done

        self._ensure_subscription(
            f"cse-in/uxv/telemetry", _SUB_TEL_RN
        )
        self._ensure_subscription(
            f"cse-in/uxv/ack", _SUB_ACK_RN
        )

    def send_command(self, payload: dict) -> tuple[float | None, bool]:
        """POST a command CIN to /cse-in/uxv/commands via CoAP CON."""
        con_str = json.dumps(payload)
        body = json.dumps({"m2m:cin": {"con": con_str}}).encode()
        uri = f"{self._config.cse_coap_base}/cse-in/uxv/commands"

        t_start = time.monotonic_ns()
        future = asyncio.run_coroutine_threadsafe(
            self._async_post(uri, body), self._loop
        )
        try:
            rsc = future.result(timeout=_REQUEST_TIMEOUT_S + 1)
            t_end = time.monotonic_ns()
            latency_ms = (t_end - t_start) / 1_000_000
            delivered = rsc == aiocoap.CREATED
            return latency_ms, delivered
        except (TimeoutError, asyncio.TimeoutError, Exception):
            return None, False

    def subscribe_telemetry(self, callback: Callable[[dict], None]) -> None:
        self._telemetry_cb = callback

    def subscribe_ack(self, callback: Callable[[dict], None]) -> None:
        self._ack_cb = callback

    def disconnect(self) -> None:
        """Shut down the aiocoap context and stop the event loop."""
        if self._loop and self._coap_context:
            asyncio.run_coroutine_threadsafe(
                self._coap_context.shutdown(), self._loop
            ).result(timeout=5.0)
        if self._loop:
            self._loop.call_soon_threadsafe(self._loop.stop)
        if self._loop_thread:
            self._loop_thread.join(timeout=5.0)

    def get_header_bytes(self, payload_size: int) -> int:
        """Estimate CoAP header overhead for a request of given payload size.

        Fixed header: 4 bytes (version, type, TKL, code, message ID).
        Token: 8 bytes (typical for random tokens).
        Uri-Host option: 1 option header + host bytes (est. 2 + len(host)).
        Uri-Port option: 1 option header + 2 bytes.
        Uri-Path option: 1 option header + path bytes (est. per segment).
        Content-Format option: 1 option header + 1 byte.
        Payload marker: 1 byte (0xFF).
        """
        host_len = len(self._config.cse_host)
        # Approximate: 4 fixed + 8 token + 10 uri-host/port options + 4 uri-path + 2 cf + 1 marker
        return 4 + 8 + (2 + host_len) + 4 + 2 + 1

    # ------------------------------------------------------------------
    # Async internals
    # ------------------------------------------------------------------

    def _run_loop(self) -> None:
        """Entry point for the background asyncio thread."""
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()

    async def _async_connect(self) -> None:
        """Initialise aiocoap context and start the callback server."""
        client = self

        class _NotifyResource(resource.Resource):
            """CoAP resource that receives CSE push notifications."""
            async def render_post(self, request):
                try:
                    body = json.loads(request.payload.decode())
                    client._dispatch_notification(body)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    pass
                return aiocoap.Message(code=aiocoap.CHANGED)

        self._server_site = resource.Site()
        self._server_site.add_resource(("notify",), _NotifyResource())

        # Bind the callback server on all interfaces so Docker containers can reach it.
        self._coap_context = await aiocoap.Context.create_server_context(
            self._server_site,
            bind=(("0.0.0.0", self._config.callback_coap_port)),
        )

    async def _async_post(self, uri: str, payload: bytes) -> aiocoap.numbers.codes.Code:
        """Send a CON POST and return the response code."""
        request = aiocoap.Message(
            code=aiocoap.POST,
            uri=uri,
            payload=payload,
        )
        # Content-Format 50 = application/json
        request.opt.content_format = 50
        try:
            response = await asyncio.wait_for(
                self._coap_context.request(request).response,
                timeout=_REQUEST_TIMEOUT_S,
            )
            return response.code
        except asyncio.TimeoutError:
            raise TimeoutError("CoAP request timeout")

    def _ensure_subscription(self, container_path: str, rn: str) -> None:
        """Synchronously delete + re-create a SUB resource via CoAP."""
        # Delete existing subscription (best-effort).
        del_uri = f"{self._config.cse_coap_base}/{container_path}/{rn}"
        del_future = asyncio.run_coroutine_threadsafe(
            self._async_delete(del_uri), self._loop
        )
        try:
            del_future.result(timeout=5.0)
        except Exception:
            pass

        # Create subscription with our CoAP callback URI.
        body = json.dumps({
            "m2m:sub": {
                "rn": rn,
                "nu": [self._config.callback_coap_url],
                "enc": {"net": [3]},
            }
        }).encode()
        create_uri = f"{self._config.cse_coap_base}/{container_path}"
        future = asyncio.run_coroutine_threadsafe(
            self._async_post(create_uri, body), self._loop
        )
        try:
            future.result(timeout=_REQUEST_TIMEOUT_S)
        except Exception:
            pass

    async def _async_delete(self, uri: str) -> None:
        request = aiocoap.Message(code=aiocoap.DELETE, uri=uri)
        try:
            await asyncio.wait_for(
                self._coap_context.request(request).response,
                timeout=3.0,
            )
        except Exception:
            pass

    def _dispatch_notification(self, body: dict) -> None:
        """Parse a CSE notification and call the appropriate callback."""
        sgn = body.get("m2m:sgn", {})
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
