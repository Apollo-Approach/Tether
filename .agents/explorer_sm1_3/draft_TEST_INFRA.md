# Test Infrastructure Document (TEST_INFRA.md Draft)

This document describes the design, execution, and verification protocol for the End-to-End (E2E) testing suite of the Antigravity Remote Control app.

---

## 1. Test Architecture & Directory Structure

The E2E testing suite is designed to run in a black-box/opaque-box manner. It simulates the Android client by spawning a mock WebSocket client, sending JSON payloads to the Python receiver server, and asserting that the server correctly decodes and logs the events.

### Directory Layout
```text
/
├── receiver/
│   └── receiver.py        # Target under test (WebSocket server on port 8080)
├── tests/
│   ├── run_tests.py       # Test discovery & execution entry point
│   ├── test_cases.py      # E2E test cases (Tiers 1-4) using unittest.IsolatedAsyncioTestCase
│   └── requirements.txt   # Test runner dependencies
└── TEST_INFRA.md          # This documentation
```

---

## 2. Dependencies & Environment

The tests require **Python 3.8+** (specifically, Python 3.12.10 is used in this environment) due to the use of `unittest.IsolatedAsyncioTestCase` for native asynchronous test management.

### Standard Libraries
- `unittest`: Test framework, assertions, and test discovery.
- `asyncio`: Asynchronous process spawning, networking, and timeouts.
- `sys`: Interrogating the runtime interpreter (`sys.executable`).
- `json`: Formatting and parsing payloads.

### External Libraries
- `websockets (v14.2+)`: Asynchronous WebSocket client protocol.

To install dependencies, run:
```bash
pip install -r tests/requirements.txt
```

---

## 3. Subprocess & WebSocket Lifecycle Design

To ensure test isolation and reliability, each test case manages the lifecycle of the receiver server independently.

### Process Initialization
Each test spawns `receiver/receiver.py` in a separate subprocess:
1. **Command Line**: `sys.executable -u receiver/receiver.py --mock`
   - `-u` ensures the output stream is **unbuffered**, allowing the test runner to capture logs in real-time without buffering delay.
   - `--mock` (or `--dry-run`) disables OS-level mouse and keyboard emulation on the host, preventing the tests from disrupting the developer's work environment.
2. **WebSocket Retry Loop**: The test client attempts to connect to `ws://localhost:8080` with a retry delay of `0.1s` up to `3.0s` (30 attempts). This guarantees the server is listening before the client sends any events.

### Teardown & Graceful Exit
Upon test completion (or failure):
1. The WebSocket client connection is closed gracefully.
2. The receiver process is terminated using `process.terminate()`.
3. The remaining stdout and stderr outputs are collected using `await asyncio.wait_for(process.communicate(), timeout=2.0)` to clean up resources and prevent zombie processes.
4. If graceful termination fails or times out, the process is killed using `process.kill()`.

---

## 4. Test Tiers & Case Specifications

To achieve comprehensive validation, the test suite is partitioned into four distinct tiers.

### Tier 1: Feature Coverage (Happy Paths)
*Target: >= 15 test cases checking correct parsing and format compliance.*

1. **`test_mouse_move_positive`**: Send relative mouse movement (positive coordinates, e.g., `dx: 5.5, dy: 10.2`). Verify receiver log.
2. **`test_mouse_move_negative`**: Send negative coordinates (e.g., `dx: -12.4, dy: -8.1`). Verify receiver log.
3. **`test_mouse_move_integers`**: Send integer coordinates (e.g., `dx: 10, dy: 20`). Verify receiver log.
4. **`test_mouse_move_zero`**: Send zero coordinates (e.g., `dx: 0.0, dy: 0.0`). Verify receiver log.
5. **`test_mouse_move_precision`**: Send high precision floating point coordinates (e.g., `dx: 1.2345, dy: -5.6789`). Verify receiver log.
6. **`test_mouse_click_left`**: Click the left mouse button. Verify receiver log.
7. **`test_mouse_click_right`**: Click the right mouse button. Verify receiver log.
8. **`test_mouse_click_middle`**: Click the middle mouse button. Verify receiver log.
9. **`test_keyboard_single_char`**: Send a single lowercase key (e.g., `key: "a"`). Verify receiver log.
10. **`test_keyboard_uppercase_char`**: Send an uppercase key (e.g., `key: "Z"`). Verify receiver log.
11. **`test_keyboard_enter`**: Send special key `"Enter"`. Verify receiver log.
12. **`test_keyboard_backspace`**: Send special key `"Backspace"`. Verify receiver log.
13. **`test_keyboard_space`**: Send special key `"Space"`. Verify receiver log.
14. **`test_keyboard_shift`**: Send modifier key `"Shift"`. Verify receiver log.
15. **`test_keyboard_number`**: Send key `"1"`. Verify receiver log.

### Tier 2: Boundary & Corner Cases
*Target: >= 15 test cases checking error handling, bounds, and malformed inputs.*

1. **`test_mouse_move_large_dx`**: Send extremely large positive float (e.g., `dx: 1e6`). Check if the server safely handles/clamps it.
2. **`test_mouse_move_large_dy`**: Send extremely large negative float (e.g., `dy: -1e6`). Check if the server safely handles/clamps it.
3. **`test_mouse_move_missing_dy`**: Send `{"event": "mouse_move", "dx": 5.0}` without `dy`. Verify error log or graceful ignore.
4. **`test_mouse_move_invalid_types`**: Send string types for dx/dy (e.g., `dx: "ten"`). Verify error handling.
5. **`test_mouse_click_invalid_button`**: Send `"button": "double"`. Verify error handling (only left, right, middle allowed).
6. **`test_mouse_click_missing_button`**: Send `{"event": "mouse_click"}`. Verify graceful error handling.
7. **`test_keyboard_empty_key`**: Send `{"event": "keyboard_input", "key": ""}`. Verify error handling.
8. **`test_keyboard_very_long_key`**: Send a massive key string (e.g., 1000 characters). Verify text truncation or denial.
9. **`test_keyboard_missing_key`**: Send `{"event": "keyboard_input"}` without key. Verify error handling.
10. **`test_unknown_event`**: Send `{"event": "unknown_action"}`. Verify rejection or error response.
11. **`test_malformed_json`**: Send a raw non-JSON text string (e.g., `hello receiver`). Verify that server does not crash and logs parsing error.
12. **`test_missing_event_field`**: Send `{"dx": 5.0, "dy": 5.0}` (no `event` field). Verify rejection.
13. **`test_null_values`**: Send `{"event": "mouse_move", "dx": null, "dy": null}`. Verify graceful rejection.
14. **`test_extra_unsupported_fields`**: Send payload with extra fields (e.g. `{"event": "mouse_click", "button": "left", "extra": "field"}`). Verify that extra fields are either ignored or logged safely.
15. **`test_extremely_rapid_requests`**: Send 100 move requests within 0.1s. Verify that server queue handles rate/load without crashing.

### Tier 3: Cross-Feature Interactions
*Target: Verify interactions between mouse actions and keyboard modifier states.*

1. **`test_drag_interaction`**: Send sequential `mouse_move` followed by `mouse_click` events to simulate drag-and-drop.
2. **`test_shift_click`**: Simulate pressing "Shift" (keyboard event) and clicking "left" (mouse event) sequentially.
3. **`test_ctrl_c_combination`**: Simulate pressing "Ctrl", then "c" sequentially.
4. **`test_move_and_type`**: Fast sequence of moving mouse to coordinates, then typing text.

### Tier 4: Real-World Scenarios
*Target: >= 5 tests simulating user workflows.*

1. **`test_draw_circle_workflow`**: Simulate drawing a circle using 16 successive relative `mouse_move` commands forming a circular path, ending with a left-click.
2. **`test_type_sentence_workflow`**: Simulate typing a sentence: `"Hello World!"` followed by `Enter` (individual keyboard events).
3. **`test_accidental_connection_drop`**: Client opens connection, sends 3 messages, abruptly drops connection, reconnects, and sends 3 more. Verify server continues to function.
4. **`test_code_navigation_workflow`**: Simulate: `Ctrl+F` (find), type search string `"class Test"`, then `Enter`, then escape `Esc`.
5. **`test_double_click_selection`**: Click left button twice within a very small time window (e.g., 50ms) to verify double-click logging.

---

## 5. Verification & Test Runner Execution

### Test Discovery & Execution Command
The test suite can be run directly using the standard `unittest` framework:
```bash
python tests/run_tests.py
```
Alternatively, since `pytest` is installed on the system, it can be run using:
```bash
pytest tests/
```

### Invalidation Conditions
A test is considered failed if:
1. The receiver server subprocess crashes (non-zero exit code).
2. The WebSocket client times out or fails to connect within 3.0s.
3. The expected output log is not printed to stdout within the timeout window (1.0s).
4. An unexpected exception occurs during execution.
