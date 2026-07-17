#!/usr/bin/env python3
"""
receiver/receiver.py
WebSocket receiver script for Antigravity Remote Control.
"""

import asyncio
import json
import argparse
import sys
import websockets
import math
import socket
import struct
import base64
import subprocess
import threading
import platform
from io import BytesIO
try:
    import win32clipboard
except ImportError:
    win32clipboard = None
try:
    from PIL import Image
except ImportError:
    Image = None

import pyautogui
from functools import partial
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

import ast
import re
import os
import glob

def get_projects():
    projects_dir = os.path.expanduser(r"~/.gemini/config/projects")
    project_files = glob.glob(os.path.join(projects_dir, "*.json"))
    
    project_files.sort(key=lambda x: os.path.getmtime(x), reverse=True)
    
    projects = []
    for f in project_files:
        try:
            with open(f, 'r', encoding='utf-8') as file:
                data = json.load(file)
                if "name" in data:
                    projects.append(data["name"])
        except Exception:
            pass
    return projects

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0
pyautogui.MINIMUM_DURATION = 0
pyautogui.MINIMUM_SLEEP = 0

connected_clients = set()

def parse_args():
    parser = argparse.ArgumentParser(description="Antigravity Remote Control WebSocket Receiver")
    parser.add_argument('--host', default='0.0.0.0', help='Host address to bind to')
    parser.add_argument('--port', type=int, default=8080, help='Port to listen on')
    parser.add_argument('--mock', action='store_true', help='Disable OS-level emulation (dry-run mode)')
    return parser.parse_args()

async def broadcast(msg_dict):
    if not connected_clients:
        return
    payload = json.dumps(msg_dict)
    tasks = [asyncio.create_task(client.send(payload)) for client in connected_clients]
    await asyncio.gather(*tasks, return_exceptions=True)

def send_image_to_clipboard(base64_data):
    if not win32clipboard or not Image:
        print("Error: win32clipboard or PIL is not installed, cannot paste images.", file=sys.stderr)
        return False
    try:
        image_bytes = base64.b64decode(base64_data)
        image = Image.open(BytesIO(image_bytes))
        
        # Convert to DIB
        output = BytesIO()
        image.convert("RGB").save(output, "BMP")
        data = output.getvalue()[14:]  # Skip the BMP header
        
        win32clipboard.OpenClipboard()
        win32clipboard.EmptyClipboard()
        win32clipboard.SetClipboardData(win32clipboard.CF_DIB, data)
        win32clipboard.CloseClipboard()
        return True
    except Exception as e:
        print(f"Error putting image on clipboard: {e}", file=sys.stderr)
        return False

def focus_chat_window():
    if not win32clipboard:
        return False
    try:
        import win32gui
        import win32com.client
        import time
        hwnds = []
        def enum_cb(hwnd, results):
            if win32gui.IsWindowVisible(hwnd):
                title = win32gui.GetWindowText(hwnd).lower()
                if "antigravity" in title:
                    results.append(hwnd)
        win32gui.EnumWindows(enum_cb, hwnds)
        if hwnds:
            target = hwnds[0]
            shell = win32com.client.Dispatch("WScript.Shell")
            shell.SendKeys('%')
            win32gui.SetForegroundWindow(target)
            time.sleep(0.1)
            return True
    except Exception as e:
        print(f"Error focusing window: {e}", file=sys.stderr)
    return False

async def transcript_tailer():
    transcript_path = r"C:\Users\devon\.gemini\antigravity\brain\88eee36b-d43a-4a6a-af5b-c67ecab0dbc0\.system_generated\logs\transcript_full.jsonl"
    
    while not os.path.exists(transcript_path):
        await asyncio.sleep(1)

    with open(transcript_path, 'r', encoding='utf-8') as f:
        f.seek(0, os.SEEK_END)
        buffer = ""
        while True:
            chunk = f.readline()
            if not chunk:
                await asyncio.sleep(0.1)
                continue
            
            buffer += chunk
            if not buffer.endswith('\n'):
                # Wait for the rest of the line to be written
                continue
                
            line = buffer
            buffer = ""
            
            try:
                data = json.loads(line)
                step_type = data.get("type")
                
                if step_type == "USER_INPUT":
                    content = data.get("content", "")
                    match = re.search(r'<USER_REQUEST>(.*?)</USER_REQUEST>', content, re.DOTALL)
                    if match:
                        content = match.group(1).strip()
                    await broadcast({"type": "chat", "role": "user", "message": content})
                
                elif step_type == "PLANNER_RESPONSE":
                    content = data.get("content", "")
                    if content:
                        await broadcast({"type": "chat", "role": "assistant", "message": content})
                    
                    for call in data.get("tool_calls", []):
                        call_name = call.get("name")
                        args = call.get("args", {})
                        if isinstance(args, str):
                            try:
                                args = json.loads(args)
                            except Exception:
                                try:
                                    args = ast.literal_eval(args)
                                except Exception:
                                    pass
                                
                        if call_name == "write_to_file":
                            if isinstance(args, dict):
                                filename = args.get("TargetFile", "")
                                if filename.endswith(".md"):
                                    meta = args.get("ArtifactMetadata", {})
                                    title = meta.get("Summary", filename)
                                    code_content = args.get("CodeContent", "")
                                    await broadcast({"type": "artifact", "title": title, "content": code_content})
                        
                        elif call_name == "ask_permission":
                            if isinstance(args, dict):
                                action = args.get("Action", "Unknown Action")
                                target = args.get("Target", "Unknown Target")
                                title = f"Permission Request: {action} on {target}"
                                options = ["Approve", "Approve Once", "Approve (Project)", "Deny"]
                                await broadcast({
                                    "type": "approval_request",
                                    "title": title,
                                    "options": options
                                })
                        
                        elif call_name == "ask_question":
                            if isinstance(args, dict):
                                questions = args.get("questions", [])
                                if questions and isinstance(questions, list) and len(questions) > 0:
                                    q = questions[0]
                                    title = q.get("question", "Question")
                                    options = q.get("options", ["Yes", "No"])
                                    
                                    # Debug log to file
                                    try:
                                        with open(r'C:\Users\devon\.gemini\antigravity\brain\88eee36b-d43a-4a6a-af5b-c67ecab0dbc0\scratch\receiver_debug.log', 'a') as debug_f:
                                            debug_f.write(f"BROADCASTING APPROVAL: title={title}, options={options}\n")
                                    except Exception:
                                        pass
                                        
                                    await broadcast({
                                        "type": "approval_request",
                                        "title": title,
                                        "options": options
                                    })
            except Exception as e:
                print(f"Error parsing transcript line: {e}")

async def handle_client(websocket, *args, mock=False, **kwargs):
    """
    Handles incoming WebSocket connections and processes JSON control messages.
    """
    connected_clients.add(websocket)
    
    try:
        projects = get_projects()
        current_project = projects[0] if projects else ""
        handshake_data = {
            "projects": projects,
            "current_project": current_project
        }
        await websocket.send(json.dumps({"type": "handshake", "data": handshake_data}))
    except Exception as e:
        print(f"Error sending handshake: {e}", file=sys.stderr)
        
    rem_x = 0.0
    rem_y = 0.0
    try:
        async for message in websocket:
            try:
                try:
                    data = json.loads(message)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    print("Error: Malformed JSON payload received", file=sys.stderr)
                    continue
                
                if not isinstance(data, dict):
                    print("Error: Invalid payload format, expected JSON object", file=sys.stderr)
                    continue
                
                event = data.get("event")
                if not event:
                    print("Error: Missing event type in payload", file=sys.stderr)
                    continue
                
                if event == "mouse_move":
                    dx = data.get("dx")
                    dy = data.get("dy")
                    if dx is None or dy is None:
                        print("Error: Missing coordinates in mouse_move event", file=sys.stderr)
                        continue
                    if (not isinstance(dx, (int, float)) or isinstance(dx, bool) or
                        not isinstance(dy, (int, float)) or isinstance(dy, bool)):
                        print("Error: Invalid coordinates type in mouse_move event", file=sys.stderr)
                        continue
                    
                    try:
                        if not math.isfinite(dx) or not math.isfinite(dy):
                            print("Error: Invalid coordinates type in mouse_move event", file=sys.stderr)
                            continue
                        
                        # Clamp dx and dy to [-2000.0, 2000.0]
                        dx = max(-2000.0, min(2000.0, float(dx)))
                        dy = max(-2000.0, min(2000.0, float(dy)))
                    except (OverflowError, ValueError) as e:
                        print("Error: Invalid coordinates type in mouse_move event", file=sys.stderr)
                        continue
                    
                    # Print event to stdout for verification
                    print(f"[MOUSE_MOVE] dx: {dx}, dy: {dy}", flush=True)
                    if not mock:
                        try:
                            rem_x += dx
                            rem_y += dy
                            move_x = int(rem_x)
                            move_y = int(rem_y)
                            rem_x -= move_x
                            rem_y -= move_y
                            if move_x != 0 or move_y != 0:
                                pyautogui.moveRel(move_x, move_y)
                        except Exception as e:
                            print(f"Error moving mouse: {e}", file=sys.stderr)
                    
                elif event == "mouse_click":
                    button = data.get("button")
                    if button is None:
                        print("Error: Missing button in mouse_click event", file=sys.stderr)
                        continue
                    if not isinstance(button, str) or button not in ("left", "right", "middle"):
                        print("Error: Invalid button type or value in mouse_click event", file=sys.stderr)
                        continue
                    print(f"[MOUSE_CLICK] button: {button}", flush=True)
                    if not mock:
                        try:
                            pyautogui.click(button=button)
                        except Exception as e:
                            print(f"Error clicking mouse: {e}", file=sys.stderr)
                    
                elif event == "keyboard_input":
                    key = data.get("key")
                    if key is None:
                        print("Error: Missing key in keyboard_input event", file=sys.stderr)
                        continue
                    if not isinstance(key, str):
                        print("Error: Invalid key type in keyboard_input event", file=sys.stderr)
                        continue
                    if key == "" or len(key) > 100:
                        print("Error: Invalid key type or value in keyboard_input event", file=sys.stderr)
                        continue
                    print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
                    if not mock:
                        try:
                            key_lower = key.lower()
                            if key_lower in pyautogui.KEYBOARD_KEYS:
                                pyautogui.press(key_lower)
                            elif len(key) > 1 and "+" in key:
                                keys = key.lower().split("+")
                                pyautogui.hotkey(*keys)
                            else:
                                pyautogui.typewrite(key)
                        except Exception as e:
                            print(f"Error typing key: {e}", file=sys.stderr)
                    
                elif event == "chat":
                    message_text = data.get("message")
                    if message_text:
                        print(f"[CHAT] {message_text}", flush=True)
                        if not mock:
                            try:
                                import uuid
                                from datetime import datetime
                                msg_id = str(uuid.uuid4())
                                ts = datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%S.%fZ')
                                payload = {
                                    "id": msg_id,
                                    "recipient": "88eee36b-d43a-4a6a-af5b-c67ecab0dbc0",
                                    "sender": "ANDROID_REMOTE",
                                    "priority": "MESSAGE_PRIORITY_HIGH",
                                    "timestamp": ts,
                                    "renderDetails": {"messageTitle": "Remote Android App"},
                                    "hideFromUser": False,
                                    "content": message_text
                                }
                                out_dir = r"C:\Users\devon\.gemini\antigravity\brain\88eee36b-d43a-4a6a-af5b-c67ecab0dbc0\.system_generated\messages"
                                os.makedirs(out_dir, exist_ok=True)
                                with open(os.path.join(out_dir, f"{msg_id}.json"), "w", encoding="utf-8") as f:
                                    json.dump(payload, f)
                            except Exception as e:
                                print(f"Error injecting chat message: {e}", file=sys.stderr)
                                
                elif event == "image":
                    base64_data = data.get("data")
                    if base64_data:
                        print("[IMAGE] Received image payload", flush=True)
                        if not mock:
                            success = send_image_to_clipboard(base64_data)
                            if success:
                                try:
                                    pyautogui.hotkey('ctrl', 'v')
                                except Exception as e:
                                    print(f"Error pasting image: {e}", file=sys.stderr)
                                
                else:
                    print(f"Error: Unknown event type: {event}", file=sys.stderr)
            except Exception as e:
                print(f"Error: Unexpected exception in event processing: {e}", file=sys.stderr)
                continue
                
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        connected_clients.remove(websocket)

async def main():
    if sys.platform.startswith('win'):
        # Reconfigure standard output streams to use UTF-8 to prevent UnicodeEncodeError on emojis
        if hasattr(sys.stdout, 'reconfigure'):
            sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
        if hasattr(sys.stderr, 'reconfigure'):
            sys.stderr.reconfigure(encoding='utf-8', errors='backslashreplace')
    args = parse_args()
    
    # Try to launch the bundled Tailscale Go proxy
    proxy_path = os.path.join(os.path.dirname(__file__), "tsnet_proxy.exe")
    if os.path.exists(proxy_path):
        def run_proxy():
            try:
                proc = subprocess.Popen([proxy_path], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
                for line in proc.stdout:
                    line = line.strip()
                    if "TAILSCALE_AUTH_URL:" in line:
                        url = line.split("TAILSCALE_AUTH_URL:")[1].strip()
                        print("\n" + "="*60, flush=True)
                        print("🔑 ACTION REQUIRED: Tailscale Remote Access Authentication", flush=True)
                        print("Please visit the following URL to authenticate your PC:", flush=True)
                        print(url, flush=True)
                        print("="*60 + "\n", flush=True)
                    elif "TAILSCALE_IP:" in line:
                        ip = line.split("TAILSCALE_IP:")[1].strip()
                        print(f"✅ Tailscale proxy connected! IP: {ip}", flush=True)
            except Exception as e:
                print(f"Failed to run proxy: {e}", file=sys.stderr)
        
        threading.Thread(target=run_proxy, daemon=True).start()
    
    # OS-level Emulation initialization can go here if not in mock mode.
    if not args.mock:
        # e.g., import pyautogui
        pass

    async with websockets.serve(partial(handle_client, mock=args.mock), args.host, args.port) as server:
        # Retrieve the actual listening port from the websockets server
        actual_port = server.sockets[0].getsockname()[1]
        # Print server startup log to stdout
        print(f"Server listening on ws://{args.host}:{actual_port}", flush=True)
        
        # Start the background tailer
        asyncio.create_task(transcript_tailer())
        
        # Register mDNS service
        aiozc = AsyncZeroconf()
        hostname = socket.gethostname()
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(('10.255.255.255', 1))
            local_ip = s.getsockname()[0]
        except Exception:
            local_ip = '127.0.0.1'
        finally:
            s.close()
            
        info = ServiceInfo(
            "_antigravity._tcp.local.",
            f"{hostname}._antigravity._tcp.local.",
            addresses=[socket.inet_aton(local_ip)],
            port=actual_port,
            properties={
                'app': 'antigravity',
                'hostname': hostname,
                'os': platform.system(),
                'version': '1.0.0'
            },
            server=f"{hostname}.local.",
        )
        await aiozc.async_register_service(info)
        print(f"Registered mDNS service as {info.name} at {local_ip}:{actual_port}", flush=True)
        
        try:
            await asyncio.Future()  # run forever
        finally:
            await aiozc.async_unregister_service(info)
            await aiozc.async_close()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Server shut down.", file=sys.stderr)

