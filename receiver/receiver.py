#!/usr/bin/env python3
"""
receiver/receiver.py
Tether - Antigravity Remote Control WebSocket Receiver.

Bridges the Android app to Antigravity conversations via Chrome DevTools Protocol.
Dynamically discovers conversations by scanning transcript files.
Injects chat messages directly into the Antigravity UI via CDP.
"""

import asyncio
import json
import argparse
import sys
import websockets
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
import uuid
import re
import os
import glob
import time
import ast
import base64
import urllib.request
import urllib.parse
from datetime import datetime, timezone
from functools import partial
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

# ─── Constants ───
BRAIN_DIR = os.path.expanduser(r"~/.gemini/antigravity/brain")
PROJECTS_DIR = os.path.expanduser(r"~/.gemini/config/projects")
SCAN_DEPTH = 100  # How many recent conversations to check
TRANSCRIPT_SCAN_LINES = 30  # Lines to read per transcript for workspace matching


# --- Global State ---
active_conversation_id = None
active_project_name = None
tailer_task = None
_tailer_cancel = None
connected_clients = set()
_cdp_port = None  # Discovered Antigravity DevTools port

# --- Security ---
# Relying on Tailscale network for transport security and Android Biometrics for user authentication.


# ─── CDP (Chrome DevTools Protocol) ───
def discover_cdp_port():
    """Discover Antigravity's Chrome DevTools Protocol port by scanning its process."""
    global _cdp_port
    try:
        # Find Antigravity process and its listening port
        result = subprocess.run(
            ['powershell', '-Command',
             'Get-Process -Name Antigravity -ErrorAction SilentlyContinue | '
             'Where-Object {$_.Id -eq (Get-CimInstance Win32_Process -Filter '
             '"Name=\'Antigravity.exe\'" | Where-Object {$_.CommandLine -notlike '
             '"*--type=*"}).ProcessId} | ForEach-Object { '
             '(Get-NetTCPConnection -OwningProcess $_.Id -State Listen '
             '-ErrorAction SilentlyContinue).LocalPort }'],
            capture_output=True, text=True, timeout=10
        )
        ports = [int(p.strip()) for p in result.stdout.strip().split('\n') if p.strip().isdigit()]
        for port in ports:
            try:
                resp = urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=2)
                data = json.loads(resp.read())
                if 'Antigravity' in data.get('User-Agent', ''):
                    _cdp_port = port
                    print(f"[CDP] Discovered Antigravity DevTools on port {port}", flush=True)
                    return port
            except Exception:
                continue
    except Exception as e:
        print(f"[CDP] Discovery via process scan failed: {e}", file=sys.stderr)

    # Fallback: brute-scan likely port range
    try:
        result = subprocess.run(
            ['powershell', '-Command',
             'Get-NetTCPConnection -OwningProcess '
             '(Get-Process -Name Antigravity -ErrorAction SilentlyContinue).Id '
             '-State Listen -ErrorAction SilentlyContinue | '
             'Select-Object -ExpandProperty LocalPort'],
            capture_output=True, text=True, timeout=10
        )
        ports = [int(p.strip()) for p in result.stdout.strip().split('\n') if p.strip().isdigit()]
        for port in ports:
            try:
                resp = urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=2)
                data = json.loads(resp.read())
                if 'Antigravity' in data.get('User-Agent', '') or 'Electron' in data.get('User-Agent', ''):
                    _cdp_port = port
                    print(f"[CDP] Discovered Antigravity DevTools on port {port}", flush=True)
                    return port
            except Exception:
                continue
    except Exception as e:
        print(f"[CDP] Fallback discovery failed: {e}", file=sys.stderr)

    print("[CDP] Could not discover Antigravity DevTools port", file=sys.stderr, flush=True)
    return None


def get_cdp_page_ws(conversation_id=None):
    """Get the WebSocket URL for the Antigravity page target.
    If conversation_id is provided, prefer the page whose URL contains it."""
    global _cdp_port
    if not _cdp_port:
        discover_cdp_port()
    if not _cdp_port:
        return None

    try:
        resp = urllib.request.urlopen(f"http://127.0.0.1:{_cdp_port}/json", timeout=2)
        targets = json.loads(resp.read())
        pages = [t for t in targets if t.get('type') == 'page']
        if not pages:
            return None

        # Prefer a page matching the conversation ID
        if conversation_id:
            for p in pages:
                if conversation_id in p.get('url', ''):
                    return p['webSocketDebuggerUrl']

        # Fall back to first page
        return pages[0]['webSocketDebuggerUrl']
    except Exception as e:
        print(f"[CDP] Error getting page target: {e}", file=sys.stderr)
        # Port may have changed (Antigravity restarted), rediscover
        _cdp_port = None
        return None


async def inject_chat_via_cdp(message_text, conversation_id=None):
    """Inject a chat message into Antigravity's chat input via CDP.
    Uses Input.insertText + Enter keypress to submit as a real user message."""
    ws_url = get_cdp_page_ws(conversation_id)
    if not ws_url:
        print("[CDP] No page target available", file=sys.stderr, flush=True)
        return False

    try:
        async with websockets.connect(ws_url, max_size=50*1024*1024, close_timeout=5) as ws:
            msg_id = 1

            async def send_cdp(method, params=None):
                nonlocal msg_id
                payload = {"id": msg_id, "method": method, "params": params or {}}
                msg_id += 1
                await ws.send(json.dumps(payload))
                while True:
                    resp = json.loads(await ws.recv())
                    if resp.get("id") == payload["id"]:
                        if "error" in resp:
                            print(f"[CDP] Error: {resp['error']}", file=sys.stderr)
                        return resp

            # Focus the contenteditable chat input
            await send_cdp("Runtime.evaluate", {
                "expression": """
                    (() => {
                        const ce = document.querySelector('[contenteditable="true"]');
                        if (!ce) return {error: 'No contenteditable found'};
                        ce.focus();
                        window.getSelection().selectAllChildren(ce);
                        return {ok: true};
                    })()
                """,
                "returnByValue": True
            })

            await asyncio.sleep(0.1)

            # Insert the message text
            await send_cdp("Input.insertText", {"text": message_text})

            await asyncio.sleep(0.2)

            # Press Enter to submit
            await send_cdp("Input.dispatchKeyEvent", {
                "type": "rawKeyDown",
                "key": "Enter",
                "code": "Enter",
                "windowsVirtualKeyCode": 13,
                "nativeVirtualKeyCode": 13
            })
            await asyncio.sleep(0.05)
            await send_cdp("Input.dispatchKeyEvent", {
                "type": "keyUp",
                "key": "Enter",
                "code": "Enter",
                "windowsVirtualKeyCode": 13,
                "nativeVirtualKeyCode": 13
            })

            return True
    except Exception as e:
        print(f"[CDP] Injection error: {e}", file=sys.stderr, flush=True)
        # Port may have changed
        global _cdp_port
        _cdp_port = None
        return False


# ─── Project Discovery ───
def get_projects_with_details():
    """Returns list of {id, name, folderUri, folderName} dicts, sorted by mtime."""
    project_files = glob.glob(os.path.join(PROJECTS_DIR, "*.json"))
    project_files.sort(key=lambda x: os.path.getmtime(x), reverse=True)

    projects = []
    for f in project_files:
        try:
            with open(f, 'r', encoding='utf-8') as file:
                data = json.load(file)
                name = data.get("name", "")
                proj_id = data.get("id", "")

                folder_uri = ""
                folder_name = ""
                resources = data.get("projectResources", {}).get("resources", [])
                if resources:
                    folder_uri = resources[0].get("gitFolder", {}).get("folderUri", "")
                    if folder_uri:
                        folder_name = folder_uri.rstrip('/').split('/')[-1]

                if name:
                    projects.append({
                        "id": proj_id,
                        "name": name,
                        "folderUri": folder_uri,
                        "folderName": folder_name
                    })
        except Exception:
            pass
    return projects


# ─── Conversation Discovery ───
def find_conversations_for_project(folder_name):
    """Scan brain/ for conversations matching a project's folder name.
    Returns list of {id, lastActive, firstMessage} dicts, sorted by recency."""
    if not folder_name or not os.path.isdir(BRAIN_DIR):
        return []

    # Get all conversation directories with transcript mtime
    conversations = []
    for entry in os.scandir(BRAIN_DIR):
        if not entry.is_dir() or entry.name == "tempmediaStorage":
            continue
        transcript = os.path.join(entry.path, ".system_generated", "logs", "transcript.jsonl")
        if os.path.exists(transcript):
            try:
                mtime = os.path.getmtime(transcript)
                conversations.append((entry.name, transcript, mtime))
            except OSError:
                continue

    # Sort by mtime descending (most recent first)
    conversations.sort(key=lambda x: x[2], reverse=True)

    # Build regex to match folder references in content
    dev_pattern = re.compile(r'[Dd]evelopment[/\\]' + re.escape(folder_name), re.IGNORECASE)

    matches = []
    for conv_id, transcript_path, mtime in conversations[:SCAN_DEPTH]:
        try:
            first_user_msg = ""
            found = False

            with open(transcript_path, 'r', encoding='utf-8', errors='replace') as f:
                for _ in range(TRANSCRIPT_SCAN_LINES):
                    line = f.readline()
                    if not line:
                        break
                    try:
                        d = json.loads(line)
                        content = d.get('content', '')
                        step_type = d.get('type', '')

                        # Extract first user message as preview
                        if step_type == 'USER_INPUT' and not first_user_msg:
                            match = re.search(
                                r'<USER_REQUEST>(.*?)(?:</USER_REQUEST>|$)',
                                content, re.DOTALL
                            )
                            if match:
                                first_user_msg = match.group(1).strip()[:120]
                            else:
                                first_user_msg = content.strip()[:120]

                        # Check tool_calls for workspace paths
                        if not found:
                            tool_calls = d.get('tool_calls', [])
                            for tc in tool_calls:
                                args = tc.get('args', {})
                                if isinstance(args, str):
                                    try:
                                        args = json.loads(args)
                                    except Exception:
                                        continue
                                if isinstance(args, dict):
                                    for val in args.values():
                                        if isinstance(val, str) and dev_pattern.search(val):
                                            found = True
                                            break
                                if found:
                                    break

                        # Check content for workspace URIs / paths
                        if not found and dev_pattern.search(content):
                            found = True

                    except (json.JSONDecodeError, Exception):
                        continue

            if found:
                matches.append({
                    "id": conv_id,
                    "lastActive": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(mtime)),
                    "firstMessage": first_user_msg
                })
        except Exception:
            continue

    return matches


# ─── Broadcast ───
async def broadcast(msg_dict):
    """Send a message to all connected WebSocket clients."""
    if not connected_clients:
        return
    payload = json.dumps(msg_dict)
    tasks = [asyncio.create_task(client.send(payload)) for client in connected_clients]
    await asyncio.gather(*tasks, return_exceptions=True)


# ─── Transcript Processing ───
async def process_transcript_entry(data):
    """Process a single transcript entry and broadcast relevant events."""
    step_type = data.get("type")

    if step_type == "USER_INPUT":
        content = data.get("content", "")
        match = re.search(r'<USER_REQUEST>(.*?)</USER_REQUEST>', content, re.DOTALL)
        if match:
            content = match.group(1).strip()
        await broadcast({"type": "chat", "role": "user", "message": content})

    elif step_type == "PLANNER_RESPONSE":
        thinking = data.get("thinking", "")
        if thinking:
            await broadcast({"type": "thought", "text": thinking})

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
                        await broadcast({
                            "type": "artifact",
                            "title": title,
                            "content": code_content
                        })

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
                        await broadcast({
                            "type": "approval_request",
                            "title": title,
                            "options": options
                        })


# ─── Transcript Tailer ───
async def transcript_tailer(cancel_event):
    """Tails the active conversation's transcript. Stops when cancel_event is set."""
    global active_conversation_id

    while not cancel_event.is_set():
        if not active_conversation_id:
            await asyncio.sleep(1)
            continue

        transcript_path = os.path.join(
            BRAIN_DIR, active_conversation_id,
            ".system_generated", "logs", "transcript_full.jsonl"
        )

        # Wait for transcript file to exist
        while not os.path.exists(transcript_path) and not cancel_event.is_set():
            await asyncio.sleep(1)

        if cancel_event.is_set():
            break

        current_conv = active_conversation_id  # Track if conversation changes
        try:
            with open(transcript_path, 'r', encoding='utf-8') as f:
                f.seek(0, os.SEEK_END)
                buffer = ""

                while not cancel_event.is_set() and current_conv == active_conversation_id:
                    chunk = f.readline()
                    if not chunk:
                        await asyncio.sleep(0.1)
                        continue

                    buffer += chunk
                    if not buffer.endswith('\n'):
                        continue

                    line = buffer
                    buffer = ""

                    try:
                        data = json.loads(line)
                        await process_transcript_entry(data)
                    except Exception as e:
                        print(f"Error parsing transcript line: {e}")
        except Exception as e:
            print(f"Error in transcript tailer: {e}", file=sys.stderr)
            if not cancel_event.is_set():
                await asyncio.sleep(2)


async def restart_tailer():
    """Stop existing tailer and start a new one for the active conversation."""
    global tailer_task, _tailer_cancel

    # Cancel existing tailer
    if _tailer_cancel:
        _tailer_cancel.set()
    if tailer_task and not tailer_task.done():
        tailer_task.cancel()
        try:
            await tailer_task
        except asyncio.CancelledError:
            pass

    # Start new tailer
    _tailer_cancel = asyncio.Event()
    tailer_task = asyncio.create_task(transcript_tailer(_tailer_cancel))
    if active_conversation_id:
        print(f"[TAILER] Started for conversation {active_conversation_id[:12]}...", flush=True)
    else:
        print("[TAILER] No conversation selected", flush=True)


# ─── Image Handling ───
def save_image_to_conversation(base64_data, conversation_id):
    """Save a base64-encoded image to the conversation directory.
    Returns the file path on success, None on failure."""
    try:
        image_bytes = base64.b64decode(base64_data)
        conv_dir = os.path.join(BRAIN_DIR, conversation_id)
        os.makedirs(conv_dir, exist_ok=True)

        filename = f"uploaded_media_{uuid.uuid4().hex[:8]}.png"
        filepath = os.path.join(conv_dir, filename)

        with open(filepath, 'wb') as f:
            f.write(image_bytes)

        return filepath
    except Exception as e:
        print(f"Error saving image: {e}", file=sys.stderr)
        return None


# ─── CLI Args ───
def parse_args():
    parser = argparse.ArgumentParser(description="Tether - Antigravity Remote Control Receiver")
    parser.add_argument('--host', default='0.0.0.0', help='Host address to bind to')
    parser.add_argument('--port', type=int, default=8080, help='Port to listen on')
    parser.add_argument('--mock', action='store_true', help='Disable message injection (dry-run mode)')
    return parser.parse_args()


# ─── WebSocket Handler ───
async def handle_client(websocket, *args, mock=False, **kwargs):
    """Handles incoming WebSocket connections and processes JSON control messages."""
    global active_conversation_id, active_project_name

    # Auto-approve connection since Tailscale provides transport security
    try:
        await websocket.send(json.dumps({"type": "auth_success"}))
    except Exception as e:
        print(f"Auth error: {e}", file=sys.stderr)
        await websocket.close(1008, "Auth error")
        return

    connected_clients.add(websocket)

    try:
        # Send handshake with project details
        projects = get_projects_with_details()
        handshake_data = {
            "projects": [p["name"] for p in projects],
            "projectDetails": projects,
            "activeConversation": active_conversation_id or "",
            "activeProject": active_project_name or "",
            "current_project": projects[0]["name"] if projects else ""
        }
        await websocket.send(json.dumps({"type": "handshake", "data": handshake_data}))
    except Exception as e:
        print(f"Error sending handshake: {e}", file=sys.stderr)

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

                # ─── Project Selection ───
                if event == "select_project":
                    project_name = data.get("project", "")
                    if not project_name:
                        continue

                    print(f"[SELECT_PROJECT] {project_name}", flush=True)

                    # Find the project's folder name
                    projects = get_projects_with_details()
                    project = next((p for p in projects if p["name"] == project_name), None)
                    if not project:
                        print(f"Error: Project '{project_name}' not found", file=sys.stderr)
                        continue

                    folder_name = project.get("folderName", "")
                    if not folder_name:
                        print(f"Error: Project '{project_name}' has no folder URI", file=sys.stderr)
                        continue

                    # Find matching conversations
                    convos = find_conversations_for_project(folder_name)

                    if convos:
                        # Auto-select most recent
                        selected = convos[0]
                        active_conversation_id = selected["id"]
                        active_project_name = project_name

                        print(f"[SELECTED] Conversation {active_conversation_id[:12]}... for {project_name}", flush=True)

                        # Restart transcript tailer for new conversation
                        await restart_tailer()

                        # Notify client
                        await broadcast({
                            "type": "project_selected",
                            "project": project_name,
                            "conversationId": active_conversation_id,
                            "firstMessage": selected.get("firstMessage", "")
                        })
                        await broadcast({
                            "type": "conversations",
                            "data": convos
                        })
                    else:
                        print(f"No conversations found for project '{project_name}'", flush=True)
                        active_project_name = project_name
                        active_conversation_id = None
                        await broadcast({
                            "type": "project_selected",
                            "project": project_name,
                            "conversationId": "",
                            "firstMessage": "No conversations found for this project"
                        })

                # ─── Create Project ───
                elif event == "create_project":
                    project_name = data.get("name", "").strip()
                    if not project_name:
                        print("Error: Missing project name in create_project", file=sys.stderr)
                        continue
                    
                    print(f"[CREATE_PROJECT] {project_name}", flush=True)
                    
                    # 1. Create directory
                    dev_dir = r"C:\Development"
                    project_path = os.path.join(dev_dir, project_name)
                    os.makedirs(project_path, exist_ok=True)
                    
                    # 2. Create Antigravity JSON
                    proj_id = str(uuid.uuid4())
                    proj_json = {
                      "id": proj_id,
                      "name": project_name,
                      "projectResources": {
                        "resources": [
                          {
                            "gitFolder": {
                              "folderUri": f"file:///c%3A/Development/{urllib.parse.quote(project_name)}",
                              "defaultBranch": "main",
                              "allowWrite": True
                            }
                          }
                        ]
                      },
                      "permissionGrants": {
                        "permissionGrants": {
                          "allow": [
                            "command(@\")"
                          ]
                        }
                      }
                    }
                    
                    json_path = os.path.join(PROJECTS_DIR, f"{proj_id}.json")
                    with open(json_path, "w", encoding="utf-8") as f:
                        json.dump(proj_json, f, indent=2)
                    
                    # 3. Broadcast updated projects list
                    new_projects = get_projects_with_details()
                    await broadcast({
                        "type": "handshake",
                        "data": {
                            "projects": [p["name"] for p in new_projects],
                            "projectDetails": new_projects,
                            "activeConversation": active_conversation_id or "",
                            "activeProject": active_project_name or "",
                            "current_project": new_projects[0]["name"] if new_projects else ""
                        }
                    })

                # ─── Direct Conversation Selection ───
                elif event == "select_conversation":
                    conv_id = data.get("conversationId", "")
                    if conv_id:
                        active_conversation_id = conv_id
                        print(f"[SELECT_CONVERSATION] {conv_id[:12]}...", flush=True)
                        await restart_tailer()
                        await broadcast({
                            "type": "project_selected",
                            "project": active_project_name or "",
                            "conversationId": active_conversation_id,
                            "firstMessage": ""
                        })

                # ─── Chat Message Injection via CDP ───
                elif event == "chat":
                    message_text = data.get("message")
                    if message_text:
                        print(f"[CHAT] {message_text}", flush=True)
                        if not mock:
                            if not active_conversation_id:
                                print("Error: No conversation selected", file=sys.stderr)
                                await broadcast({
                                    "type": "chat",
                                    "role": "system",
                                    "message": "\u26a0\ufe0f No conversation selected. Please select a project first."
                                })
                                continue
                            try:
                                success = await inject_chat_via_cdp(message_text, active_conversation_id)
                                if success:
                                    print(f"[CHAT] Injected via CDP to {active_conversation_id[:12]}...", flush=True)
                                else:
                                    print(f"[CHAT] CDP injection failed, check Antigravity is running", file=sys.stderr, flush=True)
                                    await broadcast({
                                        "type": "chat",
                                        "role": "system",
                                        "message": "\u26a0\ufe0f CDP injection failed. Is Antigravity running?"
                                    })
                            except Exception as e:
                                print(f"Error injecting chat message: {e}", file=sys.stderr)

                # ─── Image Upload ───
                elif event == "image":
                    base64_data = data.get("data")
                    if base64_data:
                        print("[IMAGE] Received image payload", flush=True)
                        if not mock and active_conversation_id:
                            filepath = save_image_to_conversation(base64_data, active_conversation_id)
                            if filepath:
                                # Inject a chat message referencing the saved image via CDP
                                content = f"[Image attached: {filepath}]"
                                success = await inject_chat_via_cdp(content, active_conversation_id)
                                if success:
                                    print(f"[IMAGE] Saved to {filepath} and injected via CDP", flush=True)
                                else:
                                    print(f"[IMAGE] Saved to {filepath} but CDP injection failed", file=sys.stderr, flush=True)
                        elif not active_conversation_id:
                            print("Error: No conversation selected for image", file=sys.stderr)

                # ─── Legacy Events (no-op stubs) ───
                elif event in ("mouse_move", "mouse_click", "keyboard_input"):
                    print(f"[{event.upper()}] Received (no-op, trackpad disabled)", flush=True)

                else:
                    print(f"Error: Unknown event type: {event}", file=sys.stderr)

            except Exception as e:
                print(f"Error: Unexpected exception in event processing: {e}", file=sys.stderr)
                continue

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        connected_clients.discard(websocket)


# ─── Main ───
async def main():
    if sys.platform.startswith('win'):
        if hasattr(sys.stdout, 'reconfigure'):
            sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
        if hasattr(sys.stderr, 'reconfigure'):
            sys.stderr.reconfigure(encoding='utf-8', errors='backslashreplace')

    args = parse_args()

    # Try to launch the bundled Tailscale Go proxy
    if getattr(sys, 'frozen', False):
        base_dir = os.path.dirname(sys.executable)
    else:
        base_dir = os.path.dirname(os.path.abspath(sys.argv[0]))

    proxy_path = os.path.join(base_dir, "tsnet_proxy.exe")
    print(f"Looking for Tailscale proxy at: {proxy_path}", flush=True)
    if os.path.exists(proxy_path):
        def run_proxy():
            try:
                proc = subprocess.Popen(
                    [proxy_path],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    text=True, bufsize=1
                )
                for line in proc.stdout:
                    line = line.strip()
                    if "TAILSCALE_AUTH_URL:" in line:
                        url = line.split("TAILSCALE_AUTH_URL:")[1].strip()
                        print("\n" + "=" * 60, flush=True)
                        print("\U0001f511 ACTION REQUIRED: Tailscale Remote Access Authentication", flush=True)
                        print("Please visit the following URL to authenticate your PC:", flush=True)
                        print(url, flush=True)
                        print("=" * 60 + "\n", flush=True)
                    elif "TAILSCALE_IP:" in line:
                        ip = line.split("TAILSCALE_IP:")[1].strip()
                        print(f"\u2705 Tailscale proxy connected! IP: {ip}", flush=True)
            except Exception as e:
                print(f"Failed to run proxy: {e}", file=sys.stderr)

        threading.Thread(target=run_proxy, daemon=True).start()

    async with websockets.serve(
        partial(handle_client, mock=args.mock), args.host, args.port
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
            "_antigravity._tcp.local.",
            f"{hostname}._antigravity._tcp.local.",
            addresses=[socket.inet_aton(ip) for ip in local_ips],
            port=actual_port,
            properties={
                'app': 'antigravity',
                'hostname': hostname,
                'os': platform.system(),
                'version': '2.0.0'
            },
            server=f"{hostname}.local.",
        )
        await aiozc.async_register_service(info)
        print(f"Registered mDNS service as {info.name} at {local_ips[0]}:{actual_port}", flush=True)

        # Print startup summary
        projects = get_projects_with_details()
        print(f"Discovered {len(projects)} projects", flush=True)
        print(f"Brain directory: {BRAIN_DIR}", flush=True)
        print("Waiting for client to select a project...", flush=True)

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
