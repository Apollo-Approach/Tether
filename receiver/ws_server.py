from __future__ import annotations
import asyncio
import json
import os
import re
import sys
import time
import base64
import uuid
import ast
import urllib.parse
import websockets

class WebSocketServer:
    """
    Encapsulates the WebSocket server logic, handling clients, broadcasting,
    and tailing the transcript.
    """
    def __init__(self, state, project_manager, cdp_client, brain_dir: str):
        self.state = state
        self.project_manager = project_manager
        self.cdp_client = cdp_client
        self.brain_dir = brain_dir
        
        self.tailer_task = None
        self._tailer_cancel = None

    async def broadcast(self, msg_dict):
        """Send a message to all connected WebSocket clients."""
        print(f"[DEBUG] broadcast called! connected_clients count: {len(self.state.connected_clients)}, type: {msg_dict.get('type')}", flush=True)
        if not self.state.connected_clients:
            print("[DEBUG] No clients connected! Message dropped.", flush=True)
            return
        payload = json.dumps(msg_dict)
        print(f"[DEBUG] Sending payload to {len(self.state.connected_clients)} clients. Payload starts with: {payload[:50]}", flush=True)
        tasks = [asyncio.create_task(client.send(payload)) for client in self.state.connected_clients]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        print(f"[DEBUG] Broadcast results: {results}", flush=True)

    async def process_transcript_entry(self, data):
        """Process a single transcript entry and broadcast relevant events."""
        step_type = data.get("type")

        if step_type == "USER_INPUT":
            content = data.get("content", "")
            match = re.search(r'<USER_REQUEST>(.*?)</USER_REQUEST>', content, re.DOTALL)
            if match:
                content = match.group(1).strip()
            await self.broadcast({"type": "chat", "role": "user", "message": content})

        elif step_type == "PLANNER_RESPONSE":
            thinking = data.get("thinking", "")
            if thinking:
                await self.broadcast({"type": "thought", "text": thinking})

            content = data.get("content", "")
            if content:
                await self.broadcast({"type": "chat", "role": "assistant", "message": content})

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
                            await self.broadcast({
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
                        await self.broadcast({
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
                            await self.broadcast({
                                "type": "approval_request",
                                "title": title,
                                "options": options
                            })

    async def transcript_tailer(self, cancel_event):
        """Tails the active conversation's transcript. Stops when cancel_event is set."""
        while not cancel_event.is_set():
            if not self.state.active_conversation_id:
                await asyncio.sleep(1)
                continue

            transcript_path = os.path.join(
                self.brain_dir, self.state.active_conversation_id,
                ".system_generated", "logs", "transcript_full.jsonl"
            )

            # Wait for transcript file to exist
            while not os.path.exists(transcript_path) and not cancel_event.is_set():
                await asyncio.sleep(1)

            if cancel_event.is_set():
                break

            current_conv = self.state.active_conversation_id  # Track if conversation changes
            try:
                with open(transcript_path, 'r', encoding='utf-8') as f:
                    f.seek(0, os.SEEK_END)
                    buffer = ""

                    while not cancel_event.is_set() and current_conv == self.state.active_conversation_id:
                        if not self.state.connected_clients:
                            await asyncio.sleep(0.5)
                            continue

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
                            step_type = data.get("type", "UNKNOWN")
                            print(f"[DEBUG] Tailer parsed line. Type: {step_type}", flush=True)
                            await self.process_transcript_entry(data)
                        except Exception as e:
                            print(f"Error parsing transcript line: {e}")
            except Exception as e:
                print(f"Error in transcript tailer: {e}", file=sys.stderr)
                if not cancel_event.is_set():
                    await asyncio.sleep(2)

    async def restart_tailer(self):
        """Stop existing tailer and start a new one for the active conversation."""
        print(f"[DEBUG] restart_tailer called! active_conversation_id: {self.state.active_conversation_id}", flush=True)

        # Cancel existing tailer
        if self._tailer_cancel:
            self._tailer_cancel.set()
        if self.tailer_task and not self.tailer_task.done():
            self.tailer_task.cancel()
            try:
                await self.tailer_task
            except asyncio.CancelledError:
                pass
        
        if not self.state.active_conversation_id:
            print("[DEBUG] No active conversation. Not starting tailer.", flush=True)
            return

        # Start new tailer
        self._tailer_cancel = asyncio.Event()
        self.tailer_task = asyncio.create_task(self.transcript_tailer(self._tailer_cancel))
        if self.state.active_conversation_id:
            print(f"[TAILER] Started for conversation {self.state.active_conversation_id[:12]}...", flush=True)
        else:
            print("[TAILER] No conversation selected", flush=True)

    def save_image_to_conversation(self, base64_data, conversation_id):
        """Save a base64-encoded image to the conversation directory.
        Returns the file path on success, None on failure."""
        try:
            image_bytes = base64.b64decode(base64_data)
            conv_dir = os.path.join(self.brain_dir, conversation_id)
            os.makedirs(conv_dir, exist_ok=True)

            filename = f"uploaded_media_{uuid.uuid4().hex[:8]}.png"
            filepath = os.path.join(conv_dir, filename)

            with open(filepath, 'wb') as f:
                f.write(image_bytes)

            return filepath
        except Exception as e:
            print(f"Error saving image: {e}", file=sys.stderr)
            return None

    async def handle_client(self, websocket, *args, mock=False, **kwargs):
        """Handles incoming WebSocket connections and processes JSON control messages."""

        # Auto-approve connection since Tailscale provides transport security
        try:
            await websocket.send(json.dumps({"type": "auth_success"}))
        except Exception as e:
            print(f"Auth error: {e}", file=sys.stderr)
            await websocket.close(1008, "Auth error")
            return

        self.state.connected_clients.add(websocket)

        try:
            # Send handshake with project details
            projects = self.project_manager.get_projects_with_details()
            handshake_data = {
                "projects": [p["name"] for p in projects],
                "projectDetails": projects,
                "activeConversation": self.state.active_conversation_id or "",
                "activeProject": self.state.active_project_name or "",
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
                        projects = self.project_manager.get_projects_with_details()
                        project = next((p for p in projects if p["name"] == project_name), None)
                        if not project:
                            print(f"Error: Project '{project_name}' not found", file=sys.stderr)
                            continue

                        folder_name = project.get("folderName", "")
                        if not folder_name:
                            print(f"Error: Project '{project_name}' has no folder URI", file=sys.stderr)
                            continue

                        # Find matching conversations
                        convos = self.project_manager.find_conversations_for_project(folder_name)

                        if convos:
                            # Auto-select most recent
                            selected = convos[0]
                            self.state.active_conversation_id = selected["id"]
                            self.state.active_project_name = project_name
                            self.state.update()

                            print(f"[SELECTED] Conversation {self.state.active_conversation_id[:12]}... for {project_name}", flush=True)

                            # Restart transcript tailer for new conversation
                            await self.restart_tailer()

                            # Notify client
                            await self.broadcast({
                                "type": "project_selected",
                                "project": project_name,
                                "conversationId": self.state.active_conversation_id,
                                "firstMessage": selected.get("firstMessage", "")
                            })
                            await self.broadcast({
                                "type": "conversations",
                                "data": convos
                            })
                        else:
                            print(f"No conversations found for project '{project_name}'", flush=True)
                            self.state.active_project_name = project_name
                            self.state.update()
                            self.state.active_conversation_id = None
                            await self.broadcast({
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
                        
                        self.project_manager.create_project(project_name)
                        
                        # 3. Broadcast updated projects list
                        new_projects = self.project_manager.get_projects_with_details()
                        await self.broadcast({
                            "type": "handshake",
                            "data": {
                                "projects": [p["name"] for p in new_projects],
                                "projectDetails": new_projects,
                                "activeConversation": self.state.active_conversation_id or "",
                                "activeProject": self.state.active_project_name or "",
                                "current_project": new_projects[0]["name"] if new_projects else ""
                            }
                        })

                    # ─── Direct Conversation Selection ───
                    elif event == "select_conversation":
                        conv_id = data.get("conversationId", "")
                        if conv_id:
                            self.state.active_conversation_id = conv_id
                            print(f"[SELECT_CONVERSATION] {conv_id[:12]}...", flush=True)
                            await self.restart_tailer()
                            await self.broadcast({
                                "type": "project_selected",
                                "project": self.state.active_project_name or "",
                                "conversationId": self.state.active_conversation_id,
                                "firstMessage": ""
                            })
                            
                    # ─── Model Selection ───
                    elif event == "set_model":
                        model_name = data.get("model", "")
                        if model_name:
                            print(f"[SET_MODEL] {model_name}", flush=True)
                            if not mock:
                                if not self.state.active_conversation_id:
                                    print("Error: No conversation selected", file=sys.stderr)
                                    continue
                                try:
                                    success = await self.cdp_client.inject_model_change(model_name, self.state.active_conversation_id)
                                    if success:
                                        print(f"[SET_MODEL] Changed to {model_name}", flush=True)
                                    else:
                                        print(f"[SET_MODEL] CDP failed, check Antigravity is running", file=sys.stderr, flush=True)
                                except Exception as e:
                                    print(f"Error setting model: {e}", file=sys.stderr)

                    # ─── Chat Message Injection via CDP ───
                    elif event == "chat":
                        message_text = data.get("message")
                        if message_text:
                            print(f"[CHAT] {message_text}", flush=True)
                            if not mock:
                                if not self.state.active_conversation_id:
                                    print("Error: No conversation selected", file=sys.stderr)
                                    await self.broadcast({
                                        "type": "chat",
                                        "role": "system",
                                        "message": "\u26a0\ufe0f No conversation selected. Please select a project first."
                                    })
                                    continue
                                try:
                                    success = await self.cdp_client.inject_chat(message_text, self.state.active_conversation_id)
                                    if success:
                                        print(f"[CHAT] Injected via CDP to {self.state.active_conversation_id[:12]}...", flush=True)
                                    else:
                                        print(f"[CHAT] CDP injection failed, check Antigravity is running", file=sys.stderr, flush=True)
                                        await self.broadcast({
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
                            if not mock and self.state.active_conversation_id:
                                filepath = self.save_image_to_conversation(base64_data, self.state.active_conversation_id)
                                if filepath:
                                    # Inject a chat message referencing the saved image via CDP
                                    content = f"[Image attached: {filepath}]"
                                    success = await self.cdp_client.inject_chat(content, self.state.active_conversation_id)
                                    if success:
                                        print(f"[IMAGE] Saved to {filepath} and injected via CDP", flush=True)
                                    else:
                                        print(f"[IMAGE] Saved to {filepath} but CDP injection failed", file=sys.stderr, flush=True)
                            elif not self.state.active_conversation_id:
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
            self.state.connected_clients.discard(websocket)
