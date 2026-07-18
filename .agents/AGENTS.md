# Antigravity Remote Control - Workspace Agent Rules

## Real-Time Artifact Tailing

This project (`Monolith`) contains a real-time transcript tailing mechanism within `receiver.py`.

**How it works:**
1. The `receiver.py` script runs continuously in the background on the host PC.
2. It actively tails the agent's internal `transcript_full.jsonl` log file.
3. Whenever an agent creates or updates an **Artifact** (via the `write_to_file` or `replace_file_content` tools), the tailer instantly intercepts the payload.
4. The tailer broadcasts this payload over the WebSocket connection to the connected Android client.
5. The Android app receives the JSON payload (`type: "artifact"`) and renders it natively over the chat interface in real-time.

**Mandatory Agent Behavior:**
Because this is the *only* way the user can see your plans and progress visually on their phone while they are away from their PC, **you MUST heavily utilize Artifacts**. 

- **Always** write your Implementation Plans to an artifact (`implementation_plan.md`).
- **Always** write your summaries and testing results to an artifact (`walkthrough.md`).
- **Always** maintain your task list in an artifact (`task.md`).
- When proposing large changes, draft them in artifacts so the user can review them on their device.

Never skip the artifact planning phase if the task involves anything more than a trivial one-line fix. The user explicitly relies on artifacts as their primary feedback mechanism to understand what you are doing.

## Process Management

**Mandatory Agent Behavior:**
The agent is **NOT ALLOWED** to start or run `receiver.exe` using terminal commands (e.g., `run_command`). The user manually manages the lifecycle and execution of the receiver process. If `receiver.exe` needs to be run, instruct the user to start it rather than attempting to launch it in the background yourself.

## Architecture Documentation Constraints

**Mandatory Agent Behavior:**
As an agent, you do not possess persistent memory between sessions unless information is injected into your context. To prevent you from breaking existing architecture or re-making past mistakes:

1. **Critical Constraints Injection:** The `receiver.py` local server listens on port `8080`, while the bundled `tsnet_proxy.exe` (Tailscale) is hardcoded to listen on port `8765`. DO NOT change these port mappings in the Android app without explicit permission.
2. **Context-Loading Mandate:** Before proposing or executing any major refactors, networking modifications, or UI flow changes, you **MUST** use the `view_file` tool to read `C:\Development\Tether\receiver\README.md` and any relevant artifacts (like `connection_flow_analysis.md`) to ingest the project's current architectural state. Do not rely on assumptions.
