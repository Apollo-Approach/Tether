"""
receiver/config.py
Configuration management and centralized networking constants for Tether Receiver.

Handles loading and saving user preferences to a config.json file
stored next to the executable (frozen) or next to receiver.py (dev),
and exposes system-wide networking defaults and constants.
"""

import json
import os
import sys

from typing import TypedDict

# ─── Centralized Networking Constants ───

# Ports
DEFAULT_WEBSOCKET_PORT = 8080
DEFAULT_RECEIVER_PORT = 8080
TSNET_PROXY_PORT = 8765
SOCKS_PROXY_PORT = 1080
UDP_DISCOVERY_PORT = 42839
DISCOVERY_UDP_PORT = 42839
DEFAULT_CDP_PORT = 9222

# Hosts & IP Addresses
DEFAULT_HOST = "0.0.0.0"
DEFAULT_BIND_HOST = "0.0.0.0"
LOOPBACK_IP = "127.0.0.1"
BROADCAST_IP = "255.255.255.255"

# Protocols, Schemes & Service Types
MDNS_SERVICE_TYPE = "_rover._tcp.local."
MDNS_SERVICE_TYPE_BASE = "_rover._tcp."
UDP_DISCOVERY_PAYLOAD = "ROVER_DISCOVER"
UDP_DISCOVERY_MAGIC = "ROVER_DISCOVER"
APP_NAME = "rover"
WS_SCHEME = "ws://"
HTTP_SCHEME = "http://"

# System Paths
BRAIN_DIR = os.path.expanduser(r"~/.gemini/antigravity/brain")
PROJECTS_DIR = os.path.expanduser(r"~/.gemini/config/projects")
CONVERSATIONS_DIR = os.path.expanduser(r"~/.gemini/antigravity/conversations")
TAILSCALE_PROXY_EXE = "tsnet_proxy.exe"

# CDP Endpoints
CDP_JSON_VERSION_ENDPOINT = "/json/version"
CDP_JSON_ENDPOINT = "/json"

# Timeouts & Limits
CDP_SESSION_TIMEOUT_SEC = 5.0
CDP_HTTP_TIMEOUT_SEC = 2.0
WS_MAX_SIZE_BYTES = 50 * 1024 * 1024  # 50 MB
WS_CLOSE_TIMEOUT_SEC = 5
BROADCAST_TIMEOUT_SEC = 5.0
PROXY_RESTART_DELAY_SEC = 5
TRANSCRIPT_SCAN_LINES = 30


# ─── Dynamic Config File Management ───

class ConfigDict(TypedDict, total=False):
    dev_directory: str
    host: str
    port: int
    tsnet_port: int
    udp_port: int


DEFAULTS: ConfigDict = {
    "dev_directory": r"C:\Development",
    "host": DEFAULT_HOST,
    "port": DEFAULT_WEBSOCKET_PORT,
    "tsnet_port": TSNET_PROXY_PORT,
    "udp_port": UDP_DISCOVERY_PORT,
}


def _config_path() -> str:
    """Determine the config file path based on runtime context."""
    if getattr(sys, 'frozen', False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, "config.json")


def load() -> ConfigDict:
    """Load configuration from disk, returning defaults for any missing keys."""
    path = _config_path()
    cfg: ConfigDict = ConfigDict(
        dev_directory=DEFAULTS.get("dev_directory", r"C:\Development"),
        host=DEFAULTS.get("host", DEFAULT_HOST),
        port=DEFAULTS.get("port", DEFAULT_WEBSOCKET_PORT),
        tsnet_port=DEFAULTS.get("tsnet_port", TSNET_PROXY_PORT),
        udp_port=DEFAULTS.get("udp_port", UDP_DISCOVERY_PORT),
    )
    if os.path.exists(path):
        try:
            with open(path, 'r', encoding='utf-8') as f:
                saved = json.load(f)
            if isinstance(saved, dict):
                if "dev_directory" in saved and isinstance(saved["dev_directory"], str):
                    cfg["dev_directory"] = saved["dev_directory"]
                if "host" in saved and isinstance(saved["host"], str):
                    cfg["host"] = saved["host"]
                if "port" in saved and isinstance(saved["port"], int):
                    cfg["port"] = saved["port"]
                if "tsnet_port" in saved and isinstance(saved["tsnet_port"], int):
                    cfg["tsnet_port"] = saved["tsnet_port"]
                if "udp_port" in saved and isinstance(saved["udp_port"], int):
                    cfg["udp_port"] = saved["udp_port"]
        except Exception as e:
            print(f"[CONFIG] Error loading {path}: {e}")
    return cfg


def save(cfg: ConfigDict) -> None:
    """Save configuration to disk."""
    path = _config_path()
    try:
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(cfg, f, indent=2)
        print(f"[CONFIG] Saved to {path}")
    except Exception as e:
        print(f"[CONFIG] Error saving {path}: {e}")


def get_dev_directory() -> str:
    """Get the configured development directory."""
    val = load().get("dev_directory", DEFAULTS["dev_directory"])
    return str(val) if val is not None else r"C:\Development"


def set_dev_directory(path: str) -> None:
    """Set and persist the development directory."""
    cfg = load()
    cfg["dev_directory"] = path
    save(cfg)

