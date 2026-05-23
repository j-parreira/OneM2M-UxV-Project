"""Abstract base class for all four OneM2M protocol clients.

Each concrete client (WebSocket, MQTT, HTTP, CoAP) implements this interface
so the orchestrator is fully protocol-agnostic.

Thread-safety contract:
  connect() and disconnect() are called from the main/orchestrator thread.
  subscribe_telemetry() and subscribe_ack() register callbacks that will be
  invoked from a background thread — callbacks must be thread-safe.
"""
from abc import ABC, abstractmethod
from typing import Callable


class ProtocolClient(ABC):
    """Protocol-agnostic OneM2M client interface."""

    @abstractmethod
    def connect(self) -> None:
        """Establish the protocol connection to the ACME CSE.

        For WebSocket: upgrade + AE registration + subscription creation.
        For MQTT: MQTT connect + subscribe to response/notification topics.
        For HTTP: start callback HTTP server + create subscriptions.
        For CoAP: start callback CoAP server + create subscriptions.
        """

    @abstractmethod
    def send_command(self, payload: dict) -> tuple[float | None, bool]:
        """Post a command CIN to /cse-in/uxv/commands.

        Measures CIN creation latency (not end-to-end ACK latency — that is
        computed separately from ACK timestamps).

        Parameters
        ----------
        payload : dict
            Command JSON to embed in CIN.con, e.g.
            {"command": "takeoff", "seq_cmd": 1, "t_cmd_ms": 1748000000000}

        Returns
        -------
        (latency_ms, delivered)
            latency_ms: time from send to CSE response, in milliseconds.
                        None if the CSE did not respond within the timeout.
            delivered:  True if CSE returned rsc=2001 (Created).
        """

    @abstractmethod
    def subscribe_telemetry(self, callback: Callable[[dict], None]) -> None:
        """Register a callback for incoming telemetry CINs.

        Parameters
        ----------
        callback : Callable[[dict], None]
            Receives the parsed con dict from each telemetry CIN.
            Called from a background thread — must be thread-safe.
        """

    @abstractmethod
    def subscribe_ack(self, callback: Callable[[dict], None]) -> None:
        """Register a callback for incoming ACK CINs.

        Parameters
        ----------
        callback : Callable[[dict], None]
            Receives the parsed con dict from each ACK CIN.
        """

    @abstractmethod
    def disconnect(self) -> None:
        """Gracefully close the protocol connection and release resources."""

    def get_header_bytes(self, payload_size: int) -> int:
        """Return the protocol overhead in bytes for a message of given payload size.

        Override in each concrete client with a protocol-specific formula.
        See docs/ai-context/frontend-dev.md § Data Logging for definitions.

        Parameters
        ----------
        payload_size : int — size of the JSON payload in bytes

        Returns
        -------
        int — estimated header / framing overhead in bytes
        """
        return 0
