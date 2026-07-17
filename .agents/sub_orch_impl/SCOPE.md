# Scope: Implementation Track

## Architecture
- **Android App** (`/android`): Jetpack Compose, Kotlin, targeting API 36 (Android 16). Contains trackpad gesture area and keyboard capturing UI. Sends input events via WebSocket client.
- **Receiver Server** (`/receiver`): Python script `receiver.py` using `websockets` or similar library. Listens on port 8080. Decodes incoming JSON packets and logs/simulates execution.
- **Interface Contracts**: JSON WebSocket events for `mouse_move`, `mouse_click`, `keyboard_input` (defined in PROJECT.md).

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Environment & Project Init | Initialize Android 16 project structure, verify build commands, create initial receiver structure. | None | DONE |
| M2 | Communication Protocol Design | Define the exact message validation schema, establish mock/test receiver functionality. | M1 | DONE |
| M3 | Android UI and Input Capture | Create the Jetpack Compose layout for trackpad and text input, capture motion events and keystrokes. | M1 | DONE |
| M4 | Client-Server WebSocket Integration | Implement WebSocket communication in Android client and receiver python server, test simple connection. | M2, M3 | DONE |
| M5 | E2E Testing & Verification | Verify against E2E test suite published by testing track (triggered when TEST_READY.md is present). | M4 | DONE |

## Interface Contracts
See PROJECT.md for details.
