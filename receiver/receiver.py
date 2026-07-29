#!/usr/bin/env python3
"""
receiver/receiver.py
Tether - Rover Remote Control WebSocket Receiver.

Bridges the Android app to Rover conversations via Chrome DevTools Protocol.
Dynamically discovers conversations by scanning transcript files.
Injects chat messages directly into the Rover UI via CDP.
"""

import asyncio
import json
import argparse
import sys
import websockets
import flet as ft
import tkinter as tk
from tkinter import filedialog
try:
    import websockets.asyncio.server
except ImportError:
    pass
try:
    import websockets.legacy.server
except ImportError:
    pass
try:
    import websockets.asyncio.client
except ImportError:
    pass
try:
    import websockets.legacy.client
except ImportError:
    pass
import socket
import subprocess
import threading
import platform
import os
from functools import partial
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf
import ctypes

def disable_quickedit():
    """Disable Windows Console QuickEdit mode to prevent the process from pausing when clicked."""
    if os.name == 'nt':
        try:
            kernel32 = ctypes.windll.kernel32
            hStdIn = kernel32.GetStdHandle(-10)
            mode = ctypes.c_uint32()
            kernel32.GetConsoleMode(hStdIn, ctypes.byref(mode))
            new_mode = (mode.value & ~0x0040) | 0x0080
            kernel32.SetConsoleMode(hStdIn, new_mode)
        except Exception:
            pass

disable_quickedit()

# ─── Local Modules ───
from project_manager import ProjectManager
from cdp_client import RoverCDPClient
from ws_server import WebSocketServer
import config

# ─── Constants ───
BRAIN_DIR = os.path.expanduser(r"~/.gemini/antigravity/brain")
PROJECTS_DIR = os.path.expanduser(r"~/.gemini/config/projects")
CONVERSATIONS_DIR = os.path.expanduser(r"~/.gemini/antigravity/conversations")
TRANSCRIPT_SCAN_LINES = 30  # Lines to read per transcript for workspace matching


# --- Global State ---

class AppState:
    def __init__(self):
        self.active_conversation_id = None
        self.active_project_name = None
        self.connected_clients = set()
        self.cdp_port = None
        self.on_change = None
        self.logs = []
        self.new_logs = []
        self.log_lock = threading.Lock()
        self.tailscale_auth_url = None
        self.tailscale_ip = None
        self.proxy_proc = None
        self.server_error = None

    def update(self):
        if self.on_change:
            self.on_change()

    def log(self, message):
        with self.log_lock:
            self.logs.append(message)
            self.new_logs.append(message)
            if len(self.logs) > 500:
                self.logs.pop(0)
        self.update()

state = AppState()

class OutputLogger:
    def __init__(self, stream, prefix=""):
        self.stream = stream
        self.prefix = prefix
        self._is_logging = False
    def write(self, message):
        if self.stream and hasattr(self.stream, 'write'):
            try:
                self.stream.write(message)
            except Exception:
                pass
        if message.strip() and not self._is_logging:
            self._is_logging = True
            try:
                state.log(f"{self.prefix}{message.strip()}")
            finally:
                self._is_logging = False
    def flush(self):
        if self.stream and hasattr(self.stream, 'flush'):
            try:
                self.stream.flush()
            except Exception:
                pass
    def reconfigure(self, **kwargs):
        if self.stream and hasattr(self.stream, 'reconfigure'):
            try:
                self.stream.reconfigure(**kwargs)
            except Exception:
                pass

sys.stderr = OutputLogger(sys.stderr, "ERROR: ")
sys.stdout = OutputLogger(sys.stdout, "")

def handle_exception(exc_type, exc_value, exc_traceback):
    import traceback
    msg = "".join(traceback.format_exception(exc_type, exc_value, exc_traceback))
    state.log(f"CRASH: {msg}")
    sys.__excepthook__(exc_type, exc_value, exc_traceback)
sys.excepthook = handle_exception


# ─── Module Instances ───
project_manager = ProjectManager(PROJECTS_DIR, CONVERSATIONS_DIR, BRAIN_DIR, TRANSCRIPT_SCAN_LINES)
cdp_client = RoverCDPClient()
ws_server = WebSocketServer(state, project_manager, cdp_client, BRAIN_DIR)


# ─── CLI Args ───
def parse_args():
    parser = argparse.ArgumentParser(description="Tether - Rover Remote Control Receiver")
    parser.add_argument('--host', default='0.0.0.0', help='Host address to bind to')
    parser.add_argument('--port', type=int, default=8080, help='Port to listen on')
    parser.add_argument('--mock', action='store_true', help='Disable message injection (dry-run mode)')
    return parser.parse_args()


# ─── Main ───

async def run_server(args):
    # Try to launch the bundled Tailscale Go proxy
    if getattr(sys, 'frozen', False):
        base_dir = os.path.dirname(sys.executable)
    else:
        base_dir = os.path.dirname(os.path.abspath(sys.argv[0]))

    proxy_path = os.path.join(base_dir, "tsnet_proxy.exe")
    print(f"Looking for Tailscale proxy at: {proxy_path}", flush=True)
    if os.path.exists(proxy_path):
        def run_proxy():
            while True:
                try:
                    creationflags = 0x08000000 if sys.platform == 'win32' else 0
                    state.proxy_proc = subprocess.Popen(
                        [proxy_path],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, bufsize=1, creationflags=creationflags
                    )
                    for line in state.proxy_proc.stdout:
                        line = line.strip()
                        if "TAILSCALE_AUTH_URL:" in line:
                            url = line.split("TAILSCALE_AUTH_URL:")[1].strip()
                            state.tailscale_auth_url = url
                            state.update()
                            print("\\n" + "=" * 60, flush=True)
                            print("🔑 ACTION REQUIRED: Tailscale Remote Access Authentication", flush=True)
                            print("Please visit the following URL to authenticate your PC:", flush=True)
                            print(url, flush=True)
                            print("=" * 60 + "\\n", flush=True)
                        elif "TAILSCALE_IP:" in line:
                            ip = line.split("TAILSCALE_IP:")[1].strip()
                            state.tailscale_ip = ip
                            state.tailscale_auth_url = None
                            state.update()
                            print(f"✅ Tailscale proxy connected! IP: {ip}", flush=True)
                    state.proxy_proc.wait()
                    print(f"Proxy exited with code {state.proxy_proc.returncode}. Restarting in 5s...", file=sys.stderr)
                except Exception as e:
                    print(f"Failed to run proxy: {e}. Restarting in 5s...", file=sys.stderr)
                import time
                time.sleep(5)

        threading.Thread(target=run_proxy, daemon=True).start()

    try:
        async with websockets.serve(
            partial(ws_server.handle_client, mock=args.mock), args.host, args.port
        ) as server:
            actual_port = server.sockets[0].getsockname()[1]
            print(f"Server listening on ws://{args.host}:{actual_port}", flush=True)

            # Register mDNS service
            aiozc = AsyncZeroconf()
            hostname = socket.gethostname()
            try:
                local_ips = [
                    ip for ip in socket.gethostbyname_ex(hostname)[2]
                    if not ip.startswith("127.") and not ip.startswith("169.254.")
                ]
                if not local_ips:
                    local_ips = ['127.0.0.1']
            except Exception:
                local_ips = ['127.0.0.1']

            info = ServiceInfo(
                "_rover._tcp.local.",
                f"{hostname}._rover._tcp.local.",
                addresses=[socket.inet_aton(ip) for ip in local_ips],
                port=actual_port,
                properties={
                    'app': 'rover',
                    'hostname': hostname,
                    'os': platform.system(),
                    'version': '2.0.0'
                },
                server=f"{hostname}.local.",
            )
            await aiozc.async_register_service(info)
            print(f"Registered mDNS service as {info.name} at {local_ips[0]}:{actual_port}", flush=True)

            class UdpDiscoveryProtocol(asyncio.DatagramProtocol):
                def connection_made(self, transport):
                    self.transport = transport
                def datagram_received(self, data, addr):
                    try:
                        message = data.decode('utf-8')
                        if message == "ROVER_DISCOVER":
                            response = json.dumps({
                                "app": "rover",
                                "hostname": hostname,
                                "port": actual_port
                            }).encode('utf-8')
                            self.transport.sendto(response, addr)
                            print(f"[UDP] Replied to discovery from {addr}", flush=True)
                    except Exception:
                        pass

            loop = asyncio.get_running_loop()
            try:
                udp_transport, udp_protocol = await loop.create_datagram_endpoint(
                    lambda: UdpDiscoveryProtocol(),
                    local_addr=('0.0.0.0', 42839)
                )
                print("[UDP] Listening for discovery broadcasts on port 42839", flush=True)
            except Exception as e:
                print(f"[UDP] Failed to start UDP listener: {e}", file=sys.stderr)
                udp_transport = None

            # Print startup summary
            projects = project_manager.get_projects_with_details()
            print(f"Discovered {len(projects)} projects", flush=True)
            print(f"Brain directory: {BRAIN_DIR}", flush=True)
            print("Waiting for client to select a project...", flush=True)

            try:
                await asyncio.Future()  # run forever
            finally:
                if udp_transport:
                    udp_transport.close()
                await aiozc.async_unregister_service(info)
                await aiozc.async_close()
    except OSError as e:
        state.server_error = str(e)
        print(f"Failed to start server: {e}", file=sys.stderr)
        state.update()


async def flet_main(page: ft.Page):
    loop = asyncio.get_running_loop()
    page.title = "Tether Receiver"
    page.theme_mode = ft.ThemeMode.DARK
    page.theme = ft.Theme(color_scheme_seed="#F59E0B")  # Warm Amber
    page.padding = 20
    page.window.width = 900
    page.window.height = 700

    # UI Components
    status_text = ft.Text(size=14, color=ft.Colors.AMBER)
    auth_url_banner = ft.Column(visible=False)
    
    clients_count = ft.Text("0", size=24, weight="bold")
    project_name = ft.Text("None", size=18, weight="bold")
    conversation_id = ft.Text("None", size=12, color=ft.Colors.ON_SURFACE_VARIANT)
    
    logs_view = ft.ListView(
        expand=True,
        spacing=5,
        auto_scroll=True,
    )

    # ─── Preferences Dialog ───
    current_config = config.load()
    dev_dir_field = ft.TextField(
        label="Development Directory",
        value=current_config.get("dev_directory", "C:\\Development"),
        read_only=True,
        expand=True,
    )

    async def open_dir_picker(e):
        def _ask_dir():
            import tkinter as tk
            from tkinter import filedialog
            root = tk.Tk()
            root.withdraw()
            root.attributes('-topmost', True)
            res = filedialog.askdirectory(initialdir=dev_dir_field.value, title="Select Development Directory")
            root.destroy()
            return res

        path = await asyncio.to_thread(_ask_dir)
        if path:
            dev_dir_field.value = path
            page.update()

    def save_preferences(e):
        config.set_dev_directory(dev_dir_field.value)
        page.pop_dialog()
        print(f"[PREFERENCES] Development directory set to: {dev_dir_field.value}", flush=True)

    def cancel_preferences(e):
        # Reset field to saved value
        dev_dir_field.value = config.get_dev_directory()
        page.pop_dialog()

    settings_dialog = ft.AlertDialog(
        modal=True,
        title=ft.Text("Preferences"),
        content=ft.Container(
            width=500,
            content=ft.Column([
                ft.Text("Default Development Location", weight="bold", size=14),
                ft.Text(
                    "This is the root directory where your projects live. "
                    "Used when creating new projects from the mobile app.",
                    size=12, color=ft.Colors.ON_SURFACE_VARIANT
                ),
                ft.Row([
                    dev_dir_field,
                    ft.IconButton(
                        ft.Icons.FOLDER_OPEN,
                        on_click=open_dir_picker,
                        tooltip="Browse"
                    )
                ]),
            ], tight=True, spacing=10),
        ),
        actions=[
            ft.TextButton("Cancel", on_click=cancel_preferences),
            ft.Button("Save", on_click=save_preferences),
        ],
        actions_alignment=ft.MainAxisAlignment.END,
    )

    def open_settings(e):
        dev_dir_field.value = config.get_dev_directory()
        page.show_dialog(settings_dialog)

    def on_state_change():
        def do_update():
            # Update Status
            if getattr(state, 'server_error', None):
                status_text.value = f"Startup Failed: {state.server_error}"
                status_text.color = ft.Colors.ERROR
                auth_url_banner.visible = False
            elif state.tailscale_ip:
                status_text.value = f"Tailscale IP: {state.tailscale_ip}"
                status_text.color = ft.Colors.AMBER
                auth_url_banner.visible = False
            elif state.tailscale_auth_url:
                status_text.value = "Tailscale Auth Required"
                status_text.color = ft.Colors.AMBER
                auth_url_banner.controls = [
                    ft.Text("Action Required: Tailscale Authentication", color=ft.Colors.ERROR, weight="bold"),
                    ft.Text(state.tailscale_auth_url, selectable=True)
                ]
                auth_url_banner.visible = True
            else:
                status_text.value = "Starting..."
                status_text.color = ft.Colors.AMBER
                auth_url_banner.visible = False

            # Update Active Context
            clients_count.value = str(len(state.connected_clients))
            project_name.value = state.active_project_name or "None"
            conversation_id.value = state.active_conversation_id or "None"
            
            # Update Logs
            with state.log_lock:
                for msg in state.new_logs:
                    logs_view.controls.append(ft.Text(msg, size=12, font_family="Consolas"))
                state.new_logs.clear()
                if len(logs_view.controls) > 50:
                    del logs_view.controls[:-50]
                
            page.update()
        
        try:
            loop.call_soon_threadsafe(do_update)
        except RuntimeError:
            pass

    state.on_change = on_state_change

    header = ft.Row([
        ft.Icon(ft.Icons.CELL_TOWER, color=ft.Colors.AMBER, size=30),
        ft.Text("Tether Receiver", size=28, weight="bold", color=ft.Colors.AMBER),
        ft.Container(expand=True),
        status_text,
        ft.IconButton(
            ft.Icons.SETTINGS,
            on_click=open_settings,
            tooltip="Preferences",
            icon_color=ft.Colors.ON_SURFACE_VARIANT,
        )
    ])

    info_cards = ft.Row([
        ft.Card(
            content=ft.Container(
                content=ft.Column([
                    ft.Text("Connected Clients", color=ft.Colors.ON_SURFACE_VARIANT),
                    clients_count
                ]),
                padding=15,
            ),
            expand=1,
            ),
        ft.Card(
            content=ft.Container(
                content=ft.Column([
                    ft.Text("Active Project", color=ft.Colors.ON_SURFACE_VARIANT),
                    project_name,
                    conversation_id
                ]),
                padding=15,
            ),
            expand=2,
            )
    ])

    page.add(
        header,
        ft.Divider(),
        auth_url_banner,
        info_cards,
        ft.Container(height=10),
        ft.Text("Live Event Stream", size=16, weight="bold"),
        ft.Container(
            content=logs_view,
            expand=True,
            bgcolor=ft.Colors.SURFACE,
            border=ft.Border.all(1, ft.Colors.OUTLINE),
            border_radius=8,
            padding=10
        )
    )

    args = parse_args()
    state.server_task = asyncio.create_task(run_server(args))

    async def on_window_event(e):
        event_str = getattr(e, "data", "")
        event_type = getattr(e, "type", None)
        if event_str == "close" or event_type == "close" or (hasattr(ft, "WindowEventType") and event_type == ft.WindowEventType.CLOSE):
            if state.proxy_proc:
                try:
                    if sys.platform == 'win32' or platform.system() == 'Windows':
                        subprocess.call(['taskkill', '/F', '/T', '/PID', str(state.proxy_proc.pid)])
                    else:
                        state.proxy_proc.terminate()
                except:
                    pass
            if hasattr(page.window, "destroyAsync"):
                await page.window.destroyAsync()
            else:
                import inspect
                if inspect.iscoroutinefunction(page.window.destroy):
                    await page.window.destroy()
                else:
                    page.window.destroy()

    page.window.prevent_close = True
    page.window.on_event = on_window_event

    state.update()

if __name__ == "__main__":
    if sys.platform.startswith('win'):
        if hasattr(sys.stdout, 'reconfigure'):
            sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
        if hasattr(sys.stderr, 'reconfigure'):
            sys.stderr.reconfigure(encoding='utf-8', errors='backslashreplace')
    ft.run(main=flet_main)
