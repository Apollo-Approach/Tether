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
from typing import Any, Callable, Coroutine, Dict, List, Set, Optional, TYPE_CHECKING
import config

if TYPE_CHECKING:
    from receiver import AppState
    from project_manager import ProjectManager
    from cdp_client import RoverCDPClient


class WebSocketServer:
    """
    Encapsulates the WebSocket server logic, handling clients, broadcasting,
    and tailing the transcript.
    """
    state: AppState
    project_manager: ProjectManager
    cdp_client: RoverCDPClient
    brain_dir: str
    tailer_task: asyncio.Task[None] | None
    _tailer_cancel: asyncio.Event | None
    queue_monitor_task: asyncio.Task[None] | None

    def __init__(
        self,
        state: AppState,
        project_manager: ProjectManager,
        cdp_client: RoverCDPClient,
        brain_dir: str
    ) -> None:
        self.state = state
        self.project_manager = project_manager
        self.cdp_client = cdp_client
        self.brain_dir = brain_dir
        
        self.tailer_task = None
        self._tailer_cancel = None
        self.queue_monitor_task = None

    async def broadcast(self, msg_dict: dict[str, Any]) -> None:
        """Send a message to all connected WebSocket clients."""
        print(f"[DEBUG] broadcast called! connected_clients count: {len(self.state.connected_clients)}, type: {msg_dict.get('type')}", flush=True)
        if not self.state.connected_clients:
            print("[DEBUG] No clients connected! Message dropped.", flush=True)
            return
        payload = json.dumps(msg_dict)
        print(f"[DEBUG] Sending payload to {len(self.state.connected_clients)} clients. Payload starts with: {payload[:50]}", flush=True)
        
        async def send_with_timeout(client: Any) -> None:
            try:
                await asyncio.wait_for(client.send(payload), timeout=config.BROADCAST_TIMEOUT_SEC)
            except Exception as e:
                print(f"[DEBUG] Broadcast to a client failed or timed out: {e}", flush=True)
                
        tasks = [asyncio.create_task(send_with_timeout(client)) for client in self.state.connected_clients]
        await asyncio.gather(*tasks)

    async def process_transcript_entry(self, data: dict[str, Any]) -> None:
        """Process a single transcript entry and broadcast relevant events."""
        step_type = data.get("type")

        if step_type == "USER_INPUT":
            content = str(data.get("content", ""))
            match = re.search(r'<USER_REQUEST>(.*?)</USER_REQUEST>', content, re.DOTALL)
            if match:
                content = match.group(1).strip()
            await self.broadcast({"type": "chat", "role": "user", "message": content})

        elif step_type == "PLANNER_RESPONSE":
            thinking = str(data.get("thinking", ""))
            if thinking:
                await self.broadcast({"type": "thought", "text": thinking})

            content = str(data.get("content", ""))
            if content:
                await self.broadcast({"type": "chat", "role": "assistant", "message": content})

            tool_calls = data.get("tool_calls", [])
            if isinstance(tool_calls, list):
                for call in tool_calls:
                    if not isinstance(call, dict):
                        continue
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
                            filename = str(args.get("TargetFile", ""))
                            if filename.endswith(".md"):
                                meta = args.get("ArtifactMetadata", {})
                                title = meta.get("Summary", filename) if isinstance(meta, dict) else filename
                                code_content = str(args.get("CodeContent", ""))
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
                                if isinstance(q, dict):
                                    title = q.get("question", "Question")
                                    options = q.get("options", ["Yes", "No"])
                                    await self.broadcast({
                                        "type": "approval_request",
                                        "title": title,
                                        "options": options
                                    })

    async def transcript_tailer(self, cancel_event: asyncio.Event) -> None:
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

    async def restart_tailer(self) -> None:
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
        
        # Start queue monitor
        if self.queue_monitor_task and not self.queue_monitor_task.done():
            self.queue_monitor_task.cancel()
            try:
                await self.queue_monitor_task
            except asyncio.CancelledError:
                pass
        
        async def on_state_change(state_data: dict[str, Any]) -> None:
            await self.broadcast({"type": "queue_update", "messages": state_data.get("messages", [])})
            await self.broadcast({"type": "tasks_update", "tasks": state_data.get("tasks", [])})

        self.queue_monitor_task = asyncio.create_task(
            self.cdp_client.monitor_queue(on_state_change, self.state.active_conversation_id)
        )
        
        if self.state.active_conversation_id:
            print(f"[TAILER] Started for conversation {self.state.active_conversation_id[:12]}...", flush=True)
            print(f"[QUEUE_MONITOR] Started for conversation {self.state.active_conversation_id[:12]}...", flush=True)
        else:
            print("[TAILER] No conversation selected", flush=True)

    def save_image_to_conversation(self, base64_data: str, conversation_id: str) -> str | None:
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

    async def handle_client(self, websocket: Any, *args: Any, mock: bool = False, **kwargs: Any) -> None:
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
                "projects": [p["name"] for p in projects if "name" in p],
                "projectDetails": projects,
                "activeConversation": self.state.active_conversation_id or "",
                "activeProject": self.state.active_project_name or "",
                "current_project": projects[0]["name"] if projects and "name" in projects[0] else ""
            }
            await websocket.send(json.dumps({"type": "handshake", "data": handshake_data}))
        except Exception as e:
            print(f"Error sending handshake: {e}", file=sys.stderr)

        try:
            audio_buffer = bytearray()
            async for message in websocket:
                if isinstance(message, bytes):
                    audio_buffer.extend(message)
                    continue

                try:
                    try:
                        data = json.loads(message)
                    except (json.JSONDecodeError, UnicodeDecodeError, TypeError):
                        print("Error: Malformed JSON payload received", file=sys.stderr)
                        continue

                    if not isinstance(data, dict):
                        print("Error: Invalid payload format, expected JSON object", file=sys.stderr)
                        continue

                    event = data.get("event") or data.get("type")
                    if not event:
                        print("Error: Missing event type in payload", file=sys.stderr)
                        continue
                        
                    if event == "voice_stream_end":
                        if audio_buffer:
                            print(f"[VOICE] Transcribing {len(audio_buffer)} bytes of audio...", flush=True)
                            
                            def transcribe_audio(audio_data: bytes) -> str:
                                import numpy as np
                                from faster_whisper import WhisperModel
                                
                                audio_np = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
                                
                                if not hasattr(self, "_whisper_model"):
                                    print("[VOICE] Loading Whisper model...", flush=True)
                                    self._whisper_model = WhisperModel("base.en", device="cpu", compute_type="int8")
                                
                                segments, _ = self._whisper_model.transcribe(audio_np, beam_size=5)
                                text = " ".join([segment.text for segment in segments]).strip()
                                return text

                            try:
                                text = await asyncio.to_thread(transcribe_audio, bytes(audio_buffer))
                                audio_buffer.clear()
                                
                                if text:
                                    print(f"[VOICE] Transcribed: {text}", flush=True)
                                    if not mock and self.state.active_conversation_id:
                                        success = await self.cdp_client.inject_chat(text, self.state.active_conversation_id)
                                        if success:
                                            print(f"[VOICE] Injected via CDP to {self.state.active_conversation_id[:12]}...", flush=True)
                                        else:
                                            print(f"[VOICE] CDP injection failed", file=sys.stderr, flush=True)
                                    else:
                                        print("Error: No conversation selected for voice", file=sys.stderr)
                                else:
                                    print("[VOICE] Transcribed text was empty", flush=True)
                            except Exception as e:
                                print(f"Error transcribing audio: {e}", file=sys.stderr)
                                audio_buffer.clear()
                        continue

                    # ─── Project Selection ───
                    if event == "select_project":
                        project_name = data.get("project", "")
                        if not project_name:
                            continue

                        print(f"[SELECT_PROJECT] {project_name}", flush=True)

                        # Find the project's folder name
                        projects = self.project_manager.get_projects_with_details()
                        project = next((p for p in projects if p.get("name") == project_name), None)
                        if not project:
                            print(f"Error: Project '{project_name}' not found", file=sys.stderr)
                            continue

                        folder_name = project.get("folderName", "")
                        project_id = project.get("id", "")

                        # Find matching conversations
                        convos = self.project_manager.find_conversations_for_project(folder_name, project_id)

                        if convos:
                            # Auto-select most recent main conversation (not a subagent)
                            main_convos = [c for c in convos if not c.get("parentId")]
                            selected = main_convos[0] if main_convos else convos[0]
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
                        project_name = str(data.get("name", "")).strip()
                        if not project_name:
                            print("Error: Missing project name in create_project", file=sys.stderr)
                            continue
                        
                        print(f"[CREATE_PROJECT] Received request: '{project_name}'", flush=True)
                        
                        if mock:
                            print("[CREATE_PROJECT] Mock mode — skipping", flush=True)
                            continue
                        
                        # 1. Create the directory on disk
                        dev_dir = config.get_dev_directory()
                        project_path = os.path.join(dev_dir, project_name)
                        await asyncio.to_thread(os.makedirs, project_path, exist_ok=True)
                        print(f"[CREATE_PROJECT] Directory created: {project_path}", flush=True)
                        
                        # 2. Use CDP to create the project in Antigravity's UI
                        try:
                            created_id = await self.cdp_client.inject_create_project(
                                project_name,
                                self.state.active_conversation_id
                            )
                            if created_id:
                                print(f"[CREATE_PROJECT] Project created in Antigravity (section: {created_id})", flush=True)
                                
                                # Use the section ID from the URL as the active conversation
                                # This prevents inject_chat from navigating back to the old conversation
                                self.state.active_project_name = project_name
                                if created_id != "created":
                                    self.state.active_conversation_id = created_id
                                    print(f"[CREATE_PROJECT] Set active conversation to {created_id[:12]}...", flush=True)
                                else:
                                    # Fallback: clear the conversation ID so inject_chat
                                    # uses whatever page is currently open
                                    self.state.active_conversation_id = None
                                    print(f"[CREATE_PROJECT] No section ID found, cleared active conversation", flush=True)
                                self.state.update()
                                
                                await self.restart_tailer()
                                
                                await self.broadcast({
                                    "type": "project_selected",
                                    "project": project_name,
                                    "conversationId": self.state.active_conversation_id or "",
                                    "firstMessage": ""
                                })
                                await self.broadcast({
                                    "type": "chat",
                                    "role": "system",
                                    "message": f"✅ Project '{project_name}' created and selected."
                                })
                            else:
                                print(f"[CREATE_PROJECT] CDP creation failed", file=sys.stderr, flush=True)
                                await self.broadcast({
                                    "type": "chat",
                                    "role": "system",
                                    "message": f"⚠️ Could not create project in Antigravity UI. Directory created at {project_path}."
                                })
                        except Exception as e:
                            print(f"[CREATE_PROJECT] Error: {e}", file=sys.stderr, flush=True)
                            await self.broadcast({
                                "type": "chat",
                                "role": "system",
                                "message": f"⚠️ Error creating project: {e}"
                            })
                        
                        # 3. Refresh project list and broadcast to mobile
                        new_projects = self.project_manager.get_projects_with_details()
                        print(f"[CREATE_PROJECT] Refreshed project list: {len(new_projects)} projects", flush=True)
                        await self.broadcast({
                            "type": "handshake",
                            "data": {
                                "projects": [p["name"] for p in new_projects if "name" in p],
                                "projectDetails": new_projects,
                                "activeConversation": self.state.active_conversation_id or "",
                                "activeProject": self.state.active_project_name or "",
                                "current_project": new_projects[0]["name"] if new_projects and "name" in new_projects[0] else ""
                            }
                        })

                    # ─── Direct Conversation Selection ───
                    elif event == "select_conversation":
                        conv_id = data.get("conversationId", "")
                        if conv_id:
                            self.state.active_conversation_id = conv_id
                            print(f"[SELECT_CONVERSATION] {conv_id[:12]}...", flush=True)
                            await self.restart_tailer()
                            
                            first_msg = ""
                            if self.state.active_project_name:
                                projects = self.project_manager.get_projects_with_details()
                                project = next((p for p in projects if p.get("name") == self.state.active_project_name), None)
                                if project:
                                    convos = self.project_manager.find_conversations_for_project(
                                        project.get("folderName", ""), 
                                        project.get("id", "")
                                    )
                                    selected_conv = next((c for c in convos if c["id"] == conv_id), None)
                                    if selected_conv:
                                        first_msg = selected_conv.get("firstMessage", "")
                                        
                            await self.broadcast({
                                "type": "project_selected",
                                "project": self.state.active_project_name or "",
                                "conversationId": self.state.active_conversation_id,
                                "firstMessage": first_msg
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

                    # ─── Update Project Settings ───
                    elif event == "update_settings":
                        turbo = data.get("turbo", False)
                        project_name = self.state.active_project_name
                        print(f"[UPDATE_SETTINGS] turbo={turbo} for {project_name}", flush=True)
                        
                        if not project_name:
                            print("Error: No project selected to update settings", file=sys.stderr)
                            continue
                            
                        # Get project details
                        projects = self.project_manager.get_projects_with_details()
                        project = next((p for p in projects if p.get("name") == project_name), None)
                        if not project:
                            print(f"Error: Project '{project_name}' not found", file=sys.stderr)
                            continue
                            
                        target_project_id = project.get("id")
                        folder_uri = project.get("folderUri", "")
                        
                        if not target_project_id:
                            print(f"Error: Project '{project_name}' has no ID", file=sys.stderr)
                            continue
                            
                        try:
                            update_success = self.project_manager.update_project_settings(
                                project_id=target_project_id,
                                is_turbo=bool(turbo)
                            )
                            if update_success:
                                print(f"[UPDATE_SETTINGS] Settings updated successfully via file watcher", flush=True)
                            else:
                                print(f"[UPDATE_SETTINGS] Failed to update settings via file watcher", file=sys.stderr)
                        except Exception as e:
                            print(f"Error updating settings: {e}", file=sys.stderr)

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
                                    success = await self.cdp_client.inject_chat(str(message_text), self.state.active_conversation_id)
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
                                filepath = await asyncio.to_thread(self.save_image_to_conversation, str(base64_data), self.state.active_conversation_id)
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

                    # ─── Stop Agent Execution ───
                    elif event == "stop":
                        print("[STOP] Received stop request", flush=True)
                        if not mock:
                            if not self.state.active_conversation_id:
                                print("Error: No conversation selected for stop", file=sys.stderr)
                                continue
                            try:
                                success = await self.cdp_client.inject_stop(self.state.active_conversation_id)
                                if success:
                                    print("[STOP] Agent stopped via CDP", flush=True)
                                    await self.broadcast({
                                        "type": "chat",
                                        "role": "system",
                                        "message": "🛑 Agent execution stopped."
                                    })
                                else:
                                    print("[STOP] CDP stop failed", file=sys.stderr, flush=True)
                                    await self.broadcast({
                                        "type": "chat",
                                        "role": "system",
                                        "message": "⚠️ Stop failed — agent may not be running."
                                    })
                            except Exception as e:
                                print(f"Error stopping agent: {e}", file=sys.stderr)

                    # ─── Approve / Reject Permission Request ───
                    elif event == "approve":
                        option_index = data.get("option_index", 0)
                        option_text = data.get("option_text", "")
                        # Antigravity's ask_permission reads 1-indexed numeric responses from chat
                        # Option 0 (Approve) = "1", Option 1 (Approve Once) = "2", etc.
                        numeric_response = str(int(option_index) + 1) if isinstance(option_index, (int, float, str)) and str(option_index).isdigit() else "1"
                        print(f"[APPROVE] Received: option={option_index} ('{option_text}') -> injecting '{numeric_response}'", flush=True)
                        if not mock:
                            try:
                                # Force port rediscovery to ensure freshness
                                await asyncio.to_thread(self.cdp_client.discover_port)
                                print(f"[APPROVE] CDP port: {self.cdp_client._cdp_port}", flush=True)
                                
                                # Use inject_keystrokes — the permission dialog removes
                                # the contenteditable, so inject_chat won't work here.
                                success = await self.cdp_client.inject_keystrokes(
                                    numeric_response,
                                    self.state.active_conversation_id
                                )
                                if success:
                                    print(f"[APPROVE] Injected '{numeric_response}' via keystrokes", flush=True)
                                else:
                                    print(f"[APPROVE] Keystroke inject failed", file=sys.stderr, flush=True)
                                    await self.broadcast({
                                        "type": "chat",
                                        "role": "system",
                                        "message": "⚠️ Could not send approval response to Antigravity."
                                    })
                            except Exception as e:
                                print(f"Error in approve: {e}", file=sys.stderr, flush=True)
                                import traceback
                                traceback.print_exc()
                                await self.broadcast({
                                    "type": "chat",
                                    "role": "system",
                                    "message": f"⚠️ Approval error: {str(e)[:100]}"
                                })

                    # ─── Queue Management ───
                    elif event == "manage_queue":
                        index = data.get("index")
                        action = data.get("action")
                        if index is not None and action:
                            print(f"[MANAGE_QUEUE] Action: '{action}' at index: {index}", flush=True)
                            if not mock and self.state.active_conversation_id:
                                try:
                                    success = await self.cdp_client.manage_queued_message(
                                        int(index), str(action), self.state.active_conversation_id
                                    )
                                    if success:
                                        print(f"[MANAGE_QUEUE] CDP action successful", flush=True)
                                    else:
                                        print(f"[MANAGE_QUEUE] CDP action failed", file=sys.stderr, flush=True)
                                except Exception as e:
                                    print(f"Error managing queue: {e}", file=sys.stderr)

                    # ─── Stop Task ───
                    elif event == "stop_task":
                        task_id = data.get("taskId")
                        if task_id:
                            print(f"[STOP_TASK] Action for task: {task_id}", flush=True)
                            if not mock and self.state.active_conversation_id:
                                try:
                                    await self.cdp_client.stop_task(str(task_id), self.state.active_conversation_id)
                                    print(f"[STOP_TASK] CDP action sent", flush=True)
                                except Exception as e:
                                    print(f"Error stopping task: {e}", file=sys.stderr)

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

