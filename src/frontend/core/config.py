"""Configuration loader for the Streamlit benchmark dashboard.

All connection parameters come from environment variables (or a .env file).
Never hardcode IPs, ports, or keys in this file.

Usage:
    from core.config import load_config
    cfg = load_config()   # call once per Streamlit session
"""
from dataclasses import dataclass
from pathlib import Path
import os

from dotenv import load_dotenv


@dataclass
class Config:
    """Typed configuration loaded from environment."""
    cse_host: str
    cse_http_port: int
    cse_mqtt_port: int
    cse_ws_port: int
    cse_coap_port: int
    data_raw_dir: Path

    # For HTTP and CoAP protocol clients:
    # This machine's IP as reachable by the CSE Docker container.
    # The CSE POSTs/CONs notifications to callback_host:callback_*_port.
    # On Docker Desktop for Windows, use the LAN IP, NOT 127.0.0.1.
    callback_host: str
    callback_http_port: int
    callback_coap_port: int

    # MQTT poa host — the broker address the CSE uses when delivering notifications.
    # Must be reachable FROM INSIDE the CSE Docker container, not from the host.
    # Default: "mosquitto" (Docker Compose service name, resolved via Docker DNS).
    # Using CSE_HOST (e.g. 127.0.0.1) here will FAIL: 127.0.0.1 inside the CSE
    # container is the container's own loopback, NOT the host's Mosquitto broker.
    mqtt_broker_poa_host: str

    @property
    def cse_http_base(self) -> str:
        return f"http://{self.cse_host}:{self.cse_http_port}"

    @property
    def cse_ws_url(self) -> str:
        return f"ws://{self.cse_host}:{self.cse_ws_port}/"

    @property
    def cse_coap_base(self) -> str:
        return f"coap://{self.cse_host}:{self.cse_coap_port}"

    @property
    def callback_http_url(self) -> str:
        return f"http://{self.callback_host}:{self.callback_http_port}/notify"

    @property
    def callback_coap_url(self) -> str:
        return f"coap://{self.callback_host}:{self.callback_coap_port}/notify"


def load_config() -> Config:
    """Load configuration from .env file and/or environment variables.

    Returns
    -------
    Config
        Fully populated configuration dataclass.
    """
    load_dotenv()

    raw_dir = os.getenv("DATA_RAW_DIR", "../../data/raw")
    return Config(
        cse_host=os.getenv("CSE_HOST", "127.0.0.1"),
        cse_http_port=int(os.getenv("CSE_HTTP_PORT", "8080")),
        cse_mqtt_port=int(os.getenv("CSE_MQTT_PORT", "1883")),
        cse_ws_port=int(os.getenv("CSE_WS_PORT", "8180")),
        cse_coap_port=int(os.getenv("CSE_COAP_PORT", "5683")),
        data_raw_dir=Path(raw_dir).resolve(),
        callback_host=os.getenv("CALLBACK_HOST", "127.0.0.1"),
        callback_http_port=int(os.getenv("CALLBACK_HTTP_PORT", "8090")),
        callback_coap_port=int(os.getenv("CALLBACK_COAP_PORT", "5684")),
        mqtt_broker_poa_host=os.getenv("MQTT_BROKER_POA_HOST", "mosquitto"),
    )
