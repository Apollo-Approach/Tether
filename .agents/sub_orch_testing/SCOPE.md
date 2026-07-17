# Scope: E2E Testing Track

## Architecture
- **E2E Test Runner**: `tests/run_tests.py` using Python's `unittest` or a custom test runner. It will execute test cases defined in `tests/test_cases.py` (or individual test modules in `tests/`).
- **Target under test**: `receiver/receiver.py` (WebSocket receiver server).
- **Communication Protocol**: WebSocket JSON messages as defined in `PROJECT.md`.
- **Test Harness**: Starts `receiver/receiver.py` in a background subprocess, initiates a WebSocket client to connect to it, sends simulated UI events, and checks that the receiver correctly processes/logs/handles them.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| SM1 | Test Infra & Design | Write TEST_INFRA.md and create tests/ directory structure, run_tests.py framework. | None | DONE |
| SM2 | Tier 1 (Feature Coverage) | Implement >=15 tests covering mouse_move, mouse_click, keyboard_input happy paths. | SM1 | DONE |
| SM3 | Tier 2 (Boundary & Corner) | Implement >=15 tests covering boundary/edge/error cases (empty, negative, overflow, invalid). | SM1 | DONE |
| SM4 | Tier 3 (Cross-Feature) | Implement pairwise feature interaction tests (e.g., drag, shift+click+type). | SM2 | DONE |
| SM5 | Tier 4 (Real-World Scenarios) | Implement >=5 complex real-world workload tests. | SM3, SM4 | DONE |
| SM6 | Final Verification & Publish | Run entire test suite, verify pass rates, write and publish TEST_READY.md. | SM5 | DONE |

## Interface Contracts
### E2E Test Client ↔ Receiver WebSocket Server
- WebSocket messages sent in JSON format.
- `mouse_move`: `{"event": "mouse_move", "dx": float, "dy": float}`
- `mouse_click`: `{"event": "mouse_click", "button": "left" | "right" | "middle"}`
- `keyboard_input`: `{"event": "keyboard_input", "key": string}`
