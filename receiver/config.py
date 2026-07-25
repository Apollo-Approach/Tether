"""
receiver/config.py
Configuration management for Tether Receiver.

Handles loading and saving user preferences to a config.json file
stored next to the executable (frozen) or next to receiver.py (dev).
"""

import json
import os
import sys

DEFAULTS = {
    "dev_directory": "C:\\Development"
}


def _config_path() -> str:
    """Determine the config file path based on runtime context."""
    if getattr(sys, 'frozen', False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, "config.json")


def load() -> dict:
    """Load configuration from disk, returning defaults for any missing keys."""
    path = _config_path()
    config = dict(DEFAULTS)
    if os.path.exists(path):
        try:
            with open(path, 'r', encoding='utf-8') as f:
                saved = json.load(f)
            if isinstance(saved, dict):
                config.update(saved)
        except Exception as e:
            print(f"[CONFIG] Error loading {path}: {e}")
    return config


def save(config: dict):
    """Save configuration to disk."""
    path = _config_path()
    try:
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(config, f, indent=2)
        print(f"[CONFIG] Saved to {path}")
    except Exception as e:
        print(f"[CONFIG] Error saving {path}: {e}")


def get_dev_directory() -> str:
    """Get the configured development directory."""
    return load().get("dev_directory", DEFAULTS["dev_directory"])


def set_dev_directory(path: str):
    """Set and persist the development directory."""
    config = load()
    config["dev_directory"] = path
    save(config)
