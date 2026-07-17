# Project: Antigravity Remote Control

## Architecture
- **Android App (Kotlin, Jetpack Compose)**:
  - Targets Android 16 (API level 36).
  - Contains a Touch Area UI for relative trackpad movements and clicks.
  - Contains a Text/Keyboard Area UI for capturing typing and special keys.
  - Client component communicates with the receiver script over a WebSocket connection.
- **Receiver Script (Python)**:
  - Runs in the Antigravity (Windows) environment.
  - Hosts a WebSocket server (e.g. port 8080).
  - Decodes incoming control packets (JSON) and logs/executes them.
- **E2E Testing Suite (Python)**:
  - Independent testing script to simulate inputs on the Android app (via emulator input commands / websocket mockup) and check that the receiver script receives and decodes them correctly.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Environment & Project Init | Create empty Android 16 (API 36) project, verify build & setup. | None | DONE |
| M2 | Communication Protocol Design | Define JSON payload format, design mock receiver script. | M1 | DONE |
| M3 | Android UI and Input Capture | Implement trackpad compose UI, mouse gesture capture, keyboard capture. | M1 | DONE |
| M4 | Client-Server WebSocket Integration | Connect Android WebSocket client to Receiver WebSocket server, send events. | M2, M3 | DONE |
| M5 | E2E Testing & Verification | Validate all E2E test cases (Tiers 1-4), ensuring 100% pass rate. | M4 | DONE |
| M6 | Adversarial Hardening (Tier 5) | White-box search for edge-cases and untested paths, add robust error handling. | M5 | DONE |

## Interface Contracts
### Android Client ↔ Receiver Server (WebSocket JSON Protocol)
All messages are JSON objects sent over a WebSocket connection.

#### 1. Trackpad Move Event
- Client sends relative mouse coordinates (dx, dy).
- Format:
```json
{
  "event": "mouse_move",
  "dx": 15.5,
  "dy": -10.2
}
```

#### 2. Mouse Click Event
- Client sends mouse click action.
- Format:
```json
{
  "event": "mouse_click",
  "button": "left" | "right" | "middle"
}
```

#### 3. Keyboard Input Event
- Client sends keystrokes or text chunks.
- Format:
```json
{
  "event": "keyboard_input",
  "key": "a" | "Enter" | "Backspace" | "Shift"
}
```

## Code Layout
- `/android` - Android 16 Jetpack Compose project
  - `/app/src/main/java/` - Kotlin sources
  - `build.gradle.kts` - targeting API 36
- `/receiver` - Python WebSocket receiver script
  - `receiver.py` - WebSocket server listening on port 8080
- `/tests` - Opaque-box E2E testing suite
  - `run_tests.py` - Test runner running Tiers 1-4
  - `test_cases.py` - Individual test definitions
