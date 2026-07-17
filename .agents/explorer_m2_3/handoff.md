# Milestone M2 Communication Protocol Design Handoff Report

## 1. Observation
A thorough examination of the codebase and test files within `c:\Development\Monolith\` yielded the following direct observations:

*   **Test Execution Output**:
    Running `python tests/run_tests.py` executing 69 test cases returned:
    ```text
    Ran 69 tests in 102.662s
    OK
    ```
*   **Directory Structure (`tests/` and `receiver/`)**:
    *   `receiver/receiver.py` (target under test) implements a WebSocket listener.
    *   `tests/test_cases.py` (440 lines) houses Tier 1 (17 tests) and Tiers 2-4 (26 tests).
    *   `tests/test_adversarial.py` (207 lines) contains `TestAdversarialAndStress` (13 tests).
    *   `tests/test_stress.py` (223 lines) contains `TestStressAndCornerCases` (6 tests).
    *   `tests/test_non_ascii.py` (81 lines) contains `TestNonAsciiKeyboardInput` (2 tests).
    *   `tests/test_challenge.py` (211 lines) contains `TestChallengeRobustness` (5 tests).
    *   `tests/verify_zombies.py` (90 lines) implements cleanup checks for failed setup connections.
*   **Documentation Discrepancies**:
    *   `TEST_READY.md` lists a total count of **62 test cases** (comprising Tier 1: 17, Tier 2: 17, Tier 3: 4, Tier 4: 5, Adversarial: 13, and Stress: 6).
    *   The test runner actually discovers and executes **69 tests** because it includes `test_non_ascii.py` (2 tests) and `test_challenge.py` (5 tests), which are not listed in the summary table of `TEST_READY.md`.
*   **Receiver Protocol Parsing Logic (`receiver/receiver.py`)**:
    *   Checks that incoming messages are dictionaries:
        ```python
        if not isinstance(data, dict):
            print("Error: Invalid payload format, expected JSON object", file=sys.stderr)
            continue
        ```
    *   Defines three core event formats:
        *   `mouse_move` (requires finite `dx` and `dy` numbers, clamped to `[-2000.0, 2000.0]`)
        *   `mouse_click` (requires `button` in `("left", "right", "middle")`)
        *   `keyboard_input` (requires `key` as a string of length `1 <= len(key) <= 100`)

---

## 2. Logic Chain

### Mapping of Features to Test Cases

1.  **Mouse Relative Movement** (`dx` and `dy` parsing, limits, type checking, NaN/Infinity, clamping):
    *   *Tier 1 Happy Paths*: `test_mouse_move_positive`, `test_mouse_move_negative`, `test_mouse_move_integers`, `test_mouse_move_zero`, `test_mouse_move_precision` (in `test_cases.py`).
    *   *Tier 2 Boundary/Type Cases*: `test_mouse_move_large_dx`, `test_mouse_move_large_dy`, `test_mouse_move_nan`, `test_mouse_move_inf`, `test_mouse_move_missing_dy`, `test_mouse_move_invalid_types` (in `test_cases.py` and duplicated in `test_adversarial.py` / `test_stress.py`).
    *   *Robustness Challenge Cases*: `test_coordinates_nan_literal`, `test_coordinates_infinity_literal`, `test_coordinates_overflow_to_infinity` (in `test_challenge.py`).
    *   *Workflows*: `test_draw_circle_workflow` (16 moves + click), `test_move_and_type`, `test_drag_interaction`.

2.  **Mouse Button Clicking** (`button` validation):
    *   *Tier 1 Happy Paths*: `test_mouse_click_left`, `test_mouse_click_right`, `test_mouse_click_middle`, `test_mouse_click_sequence_left_right`, `test_mouse_click_rapid_left`.
    *   *Tier 2 Boundary Cases*: `test_mouse_click_invalid_button`, `test_mouse_click_missing_button` (also checked in `test_adversarial.py` and `test_stress.py` for type resilience).
    *   *Workflows*: `test_double_click_selection`, `test_shift_click`, `test_drag_interaction`.

3.  **Keyboard Key Inputs** (`key` validation and non-ASCII character handling):
    *   *Tier 1 Happy Paths*: `test_keyboard_single_char`, `test_keyboard_uppercase_char`, `test_keyboard_special_enter`, `test_keyboard_special_backspace`, `test_keyboard_modifier_shift`, `test_keyboard_number`, `test_keyboard_special_space`.
    *   *Tier 2 Boundary Cases*: `test_keyboard_empty_key`, `test_keyboard_very_long_key` (length > 100 limit), `test_keyboard_missing_key`.
    *   *Non-ASCII Emojis*: `test_non_ascii_keyboard_input` (verifies UTF-8 encoding support).
    *   *Workflows*: `test_type_sentence_workflow`, `test_code_navigation_workflow` (e.g. `Ctrl+F` sequences).

4.  **Protocol Schema Validation** (JSON format and structure checking):
    *   *Malformed Payloads*: `test_malformed_json`, `test_malformed_json_raw_string`, `test_malformed_json_unclosed_brace` (in `test_cases.py`, `test_adversarial.py`, and `test_stress.py`).
    *   *Missing Fields*: `test_missing_event_field`, `test_unknown_event`, `test_null_values`.
    *   *Resilience Challenge*: `test_invalid_utf8_binary_frame`, `test_invalid_utf8_text_frame` (raw TCP connection setup sending bad frame structure).

5.  **Connection Lifecycle / Stress** (Drops, concurrency, payload size):
    *   *Connection Drops*: `test_accidental_connection_drop`, `test_abrupt_connection_drop_and_reconnect`, `test_connection_drops` (closing transport writer directly).
    *   *Concurrency & Load*: `test_concurrent_connections` (5 clients), `test_rapid_multiple_client_connections` (5 concurrent sessions), `test_extremely_rapid_requests` (100 sequential moves), `test_rapid_request_stress` (100 moves).
    *   *Massive Payload*: `test_massive_payload_size` (2MB packet disconnect recovery).

### Identified Gaps in Protocol & Testing

Based on the implemented parsing logic in `receiver.py` and the coverage in the test files, the following design and testing gaps were identified:

1.  **Lack of Mouse Hold/Release (Drag-and-Drop) and Scroll Events**:
    *   The protocol only supports instantaneous `mouse_click` and relative `mouse_move` events.
    *   There are no `mouse_down` (button down) and `mouse_up` (button up) events. True drag-and-drop operations cannot be emulated on the OS.
    *   There is no mouse wheel scroll support (e.g. scrolling pages up/down/left/right).
2.  **Keyboard Modifier Hold State Limitations**:
    *   Since keyboard events are sent as standalone keypresses (e.g. `{"event": "keyboard_input", "key": "Ctrl"}` followed by `{"event": "keyboard_input", "key": "c"}`), they are emulated sequentially (press-and-release) rather than concurrently. This makes true keyboard combinations (Ctrl+C, Ctrl+Alt+Del, Shift+click) impossible to execute correctly without state tracking or a modified protocol schema (e.g., adding a `modifiers` list parameter to inputs).
3.  **Unidirectional Protocol (Lack of Client-Server Feedback)**:
    *   The protocol is entirely one-way (unidirectional). The server accepts payloads but never responds to the client with success or error notifications. Error messages are only printed to the server's local `stderr`.
4.  **No Handshake/Protocol Versioning or Security**:
    *   There is no protocol version negotiation or initial handshake event. Any modification to the event format would break older clients.
    *   There is no security/authentication layer tested or specified. Any client can connect and execute arbitrary keyboard/mouse commands on the host machine.
5.  **No Ping/Pong Keep-Alive Tests**:
    *   The test suite does not cover connection health monitoring (WebSocket ping/pong) to clean up dead sockets when clients drop silently without sending a TCP FIN/RST packet.
6.  **Missing Test Coverage for Non-Dict JSON Structures**:
    *   Although `receiver.py` (lines 33-35) checks if `data` is a dictionary (`isinstance(data, dict)`), there is no test case in the test suites that sends a valid JSON list (e.g., `"[1, 2]"`) or primitive (e.g., `"true"` or `123`) to explicitly assert that this error path is handled and logged without raising exceptions.

---

## 3. Caveats
*   **Mock Emulation Layer**: All tests run the receiver process in `--mock` (dry-run) mode, which bypasses actual OS-level emulation calls (pyautogui). We assume the OS emulation layer executes correct commands, though it is not verified here.
*   **Android Client Mocking**: The Android application client is simulated entirely by Python's mock client. Actual behavior of the Android client's WebSocket connection over a real network (including packet loss, Wi-Fi drops, and latency jitter) remains uninvestigated in this milestone.

---

## 4. Conclusion
The Milestone M2 test suite provides extensive coverage of the planned JSON communication protocol. The server is highly resilient to parsing issues, malicious/massive frames, NaN/Infinity literals, and concurrency stress, passing all 69 test cases successfully.

However, to move to subsequent milestones (specifically M3/M4/M5), the following protocol improvements are recommended:
1.  Extend `mouse_click` or introduce `mouse_state` to support `press` and `release` (for dragging) and `scroll` (for scrolling).
2.  Redesign `keyboard_input` to accept a `modifiers` array (e.g. `["Ctrl"]`) to support compound shortcuts properly, rather than relying on sequential key strokes.
3.  Add tests checking how `receiver.py` handles valid JSON but invalid types (like JSON lists or integers instead of dictionary objects).

---

## 5. Verification Method
To verify these findings and ensure the test suite is intact, execute the following commands in the workspace:

1.  **E2E Test Execution**:
    ```bash
    python tests/run_tests.py
    ```
    *Verification Condition*: The script must output `Ran 69 tests` and finish with `OK` (exit code 0).
2.  **Zombie Process Detection**:
    ```bash
    python tests/verify_zombies.py
    ```
    *Verification Condition*: The script must output `ALL ZOMBIE TESTS PASSED.` (exit code 0).
