# Antigravity Remote Control Receiver

This directory contains the `receiver.py` script, which acts as the WebSocket server for the Antigravity Remote Control application. It provides real-time integration between the Antigravity agent, the host machine's OS, and remote clients (such as mobile devices) on the local network.

## Overview

The `receiver.py` script serves three primary functions:
1. **WebSocket Server**: Hosts an asynchronous WebSocket server that remote clients can connect to. It uses mDNS for easy network discovery.
2. **Real-Time Artifact Tailing**: Actively tails the Antigravity agent's internal `transcript_full.jsonl` log file to intercept and broadcast agent activities, plans, and artifacts to connected clients in real-time.
3. **OS-Level Emulation**: Integrates with `pyautogui` and `win32gui` to process incoming control messages from remote clients, translating them into actual mouse movements, keyboard presses, clipboard actions, and window management on the host PC.

---

## 1. WebSocket Server & Discovery

The receiver leverages the `websockets` library to spin up an async server (defaulting to `localhost:8080`). 

- **mDNS Registration**: Upon startup, the server registers an mDNS service (`_antigravity._tcp.local.`) using `zeroconf`. This allows clients on the same local network to automatically discover the host without manual IP configuration.
- **Client Handling**: The server maintains a set of connected clients and broadcasts data to them (like agent chat messages and artifacts). It also listens for incoming control commands formatted as JSON payloads.
- **Project Listing**: When a client connects, the server automatically sends a list of available projects found in the `~/.gemini/config/projects` directory.

### Project Ordering
Projects are sorted by the most recent user chat timestamp, mirroring the desktop Antigravity sidebar. The ordering mechanism uses the **authoritative conversation database metadata** rather than heuristic transcript scanning:

1. **Load Projects**: Read all `~/.gemini/config/projects/*.json` files to get project names and workspace `folderUri` paths.
2. **Map Conversations → Projects**: Each conversation has a SQLite database at `~/.gemini/antigravity/conversations/{conv_id}.db`. The `trajectory_metadata_blob` table (row `id='main'`) contains the workspace `file:///` URI as a protobuf-encoded string. This is the same source of truth the desktop app uses to associate a conversation with its project.
3. **Extract Last User Chat**: For each conversation, scan the tail of its `transcript.jsonl` to find the most recent `USER_INPUT` entry's `created_at` timestamp.
4. **Sort**: Projects are sorted descending by the latest user chat timestamp across all their conversations, with `_json_mtime` as a tiebreaker for projects with no conversation history.

> **⚠️ Important**: Do NOT use heuristic transcript content scanning (regex matching folder names in transcript lines) for project ordering. This approach is prone to catastrophic false-positive matches when conversations contain other projects' paths (e.g., config file dumps, directory listings, agent tool calls referencing multiple workspaces).

### Tailscale Proxy Integration
The PC receiver also runs `tsnet_proxy.exe` alongside the WebSocket server to handle remote connections over Tailscale (for cellular handoff). 
- **CRITICAL**: `tsnet_proxy.exe` is hardcoded to listen on port **`8765`** on the Tailnet interface, while the primary `receiver.py` local Wi-Fi connection uses port **`8080`**. Ensure client applications connect to the correct ports depending on the connection medium (Tailscale vs Local Wi-Fi).

---

## 2. Artifact Tailing Mechanism

A core feature of the receiver is the `transcript_tailer` background task. Because remote clients (e.g., an Android app) need to see what the agent is doing in real-time, the receiver actively reads the agent's internal transcript file (`transcript_full.jsonl`).

### How it Works:
1. **File Monitoring**: The script continuously polls and tails the end of the `transcript_full.jsonl` file.
2. **Payload Parsing**: As new lines (JSON objects) are appended by the system, the tailer parses them and checks the `type` field.
3. **Event Broadcasting**:
   - `USER_INPUT`: Extracts the `<USER_REQUEST>` content and broadcasts it to clients as a user chat message.
   - `PLANNER_RESPONSE`: Broadcasts the agent's textual response.
   - **Tool Calls**: It intercepts specific tool calls made by the agent:
     - `write_to_file`: If the agent creates or updates a Markdown (`.md`) file, the tailer extracts the `Summary` (title) and `CodeContent` (body) and broadcasts an `artifact` payload. This allows remote clients to render the agent's Implementation Plans and Task lists natively.
     - `ask_permission` / `ask_question`: Broadcasts an `approval_request` payload to the client so the user can remotely approve actions or answer questions.

---

## 3. Keyboard & Mouse Control Integrations

The receiver processes incoming JSON messages containing an `event` key to emulate OS-level inputs. This allows the remote client to act as a wireless trackpad, keyboard, and clipboard sender.

### Supported Events:
- **`mouse_move`**: Expects `dx` and `dy` coordinates. Clamps the values (to prevent overflow/erratic movement) and uses `pyautogui.moveRel()` to smoothly move the host's cursor.
- **`mouse_click`**: Expects a `button` parameter (`left`, `right`, `middle`) and triggers a click using `pyautogui.click()`.
- **`keyboard_input`**: Expects a `key` string. It intelligently handles single characters (`pyautogui.typewrite()`), special keys (e.g., `enter`, `backspace` mapped to `pyautogui.press()`), and hotkeys (e.g., `ctrl+c` mapped to `pyautogui.hotkey()`).
- **`chat`**: Expects a `message` string. Injects the message into the Antigravity desktop app via CDP (Chrome DevTools Protocol). See **Section 4** below for details.
- **`image`**: Expects base64-encoded image data. It decodes the image using `PIL`, converts it to a DIB format, and writes it directly to the Windows clipboard using `win32clipboard`. Finally, it simulates `Ctrl+V` to paste the image into the focused application.

---

## 4. CDP Chat Injection & Cross-Project Navigation

Chat messages from the mobile app are injected into the Antigravity desktop app via the Chrome DevTools Protocol (CDP). The receiver auto-discovers Antigravity's DevTools port by scanning localhost ports for the `/json` endpoint.

### Injection Flow (`inject_chat_via_cdp`):
1. **Same conversation**: If the desktop is already showing the target conversation (conversation ID is in `window.location.href`), inject directly — no navigation, no screen flash.
2. **Different conversation**: Navigate the desktop to `/c/{conversation_id}` (no `?section=` param to avoid landing on a subagent view). Wait 2 seconds for the page to load.
3. **Subagent detection**: After cross-conversation navigation, check for the `"Cannot send message to subagent"` banner text. If detected, re-navigate to the clean `/c/{conversation_id}` URL to escape the subagent view.
4. **Message injection**: Focus the `contenteditable` chat input, insert text via `Input.insertText`, and press Enter via `Input.dispatchKeyEvent`.

### Conversation Discovery (`find_conversations_for_project`):
When a mobile client selects a project, the receiver maps that project to its conversations using the authoritative DB metadata (same approach as project ordering — see Section 1). It auto-selects the most recently active conversation and starts tailing its transcript.

> **⚠️ Important — Subagent View Pitfall**: Do NOT check `document.body.innerText.includes('Cannot send message to subagent')` when the desktop is already on the correct conversation. This text can appear in the sidebar even on the main conversation view, causing false-positive re-navigation that flashes the screen on every message. Only run this check after navigating to a *different* conversation.

## CLI Usage

The script can be run directly from the command line with optional arguments:
- `--host`: Host address to bind to (default: `localhost`).
- `--port`: Port to listen on (default: `8080`).
- `--mock`: Disables OS-level emulation (dry-run mode). Useful for testing the WebSocket and tailing mechanisms without actually moving the mouse or pressing keys.
