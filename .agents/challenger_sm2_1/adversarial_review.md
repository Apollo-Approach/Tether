## Challenge Summary

**Overall risk assessment**: HIGH

## Challenges

### [High] Challenge 1: E2E Test Suite Flakiness Under Load due to Tight Subprocess Startup Timeout

- **Assumption challenged**: Spawning the Python receiver process in `asyncSetUp` will always complete and print its startup log within 3.0 seconds.
- **Attack scenario**: When the CPU is busy or multiple tests are run under load (e.g. loops or concurrent execution), process instantiation on Windows can exceed 3.0 seconds. In our stress tests, this occurred multiple times (`test_keyboard_modifier_shift` in Run 2, `test_mouse_click_invalid_button_type` in Run 4), causing the test runner to abort setup and raise a `TimeoutError`.
- **Blast radius**: Falsely flags test cases as failed, causing CI/CD pipeline instability and unreliable verification of receiver behavior.
- **Mitigation**: Increase the startup timeout from `3.0` to `10.0` seconds (or more) in both `tests/test_cases.py` and `tests/test_adversarial.py`. Additionally, the 1.0 second timeout in `send_and_assert_log` and `send_raw_and_assert_err` should be increased to `3.0` seconds to accommodate CPU scheduling lag on slow environments.

### [High] Challenge 2: Lack of Input Sanitization for NaN/Infinity and Large Coordinates

- **Assumption challenged**: The client will only send valid finite float coordinates in the `mouse_move` payload.
- **Attack scenario**: A malicious or malformed client can send `NaN` (Not a Number), `Infinity`, or extremely large coordinates (e.g., `1e12`). The receiver currently validates only that the data type is `int` or `float` (and not `bool`), which evaluates to `True` for `float('nan')` and `float('inf')`. It then prints or accepts them without clamping or range validation.
- **Blast radius**: When run in production mode (non-mock), passing `NaN` or `Infinity` coordinates to OS-level cursor control libraries (such as `pyautogui`) will result in unhandled exceptions and process crashes, denying service to all connected clients.
- **Mitigation**: Add checks in `receiver.py` for `math.isnan(dx)` and `math.isinf(dx)` (and same for `dy`). Clamp coordinates to a sane range (e.g., matching the host's screen dimensions) before executing or logging.

### [Medium] Challenge 3: Shared Stateful OS Emulation Concurrency Issues (Race Conditions)

- **Assumption challenged**: Multiple clients can interact with the server concurrently without interference.
- **Attack scenario**: The server accepts multiple concurrent WebSocket client connections and processes their payloads concurrently in different asyncio tasks. However, the host OS has only one physical mouse pointer and keyboard focus. If Client A and Client B concurrently send coordinates to move the mouse and perform clicks, their events will interleave. For example, Client A moves mouse to (10, 10), then Client B moves mouse to (100, 100), then Client A clicks, resulting in a click at Client B's destination.
- **Blast radius**: Extreme user confusion, security vulnerability (hijacking other user actions), and incorrect remote control outcomes.
- **Mitigation**: Introduce a session lock or access control list so only one active WebSocket connection can control the OS inputs at any given moment, rejecting or queueing other concurrent controllers.

### [Medium] Challenge 4: Incomplete Test Coverage versus Specification Document

- **Assumption challenged**: The test suite covers all test cases specified in the `TEST_INFRA.md` plan.
- **Attack scenario**: A review of `tests/test_cases.py` and `tests/test_adversarial.py` reveals that several test cases outlined in `TEST_INFRA.md` are completely missing. These include:
  - `test_keyboard_empty_key` (Tier 2, case 7)
  - `test_keyboard_very_long_key` (Tier 2, case 8)
  - `test_mouse_move_large_dx` / `dy` (Tier 2, cases 1-2)
  - All Tier 3 tests (drag-and-drop, shift-click, ctrl-c, move-and-type)
  - Almost all Tier 4 tests (drawing circle, typing sentence, code navigation, double-click)
- **Blast radius**: Multiple edge cases and interaction workflows are left untested, allowing bugs (like lack of coordinate clamping or lack of key string length truncation) to slip into production undetected.
- **Mitigation**: Implement the missing test cases in `test_adversarial.py` and `test_cases.py` to match the `TEST_INFRA.md` specification.

## Stress Test Results

- **Sequentially run test suite (10 times)** → Verify zero failures → FAILED (2 out of 10 runs failed due to `TimeoutError` in `asyncSetUp` for process startup).
- **Sequentially run stress tests (10 times)** → Verify zero failures → PASSED.
- **Flood receiver with 100 concurrent clients sending 100 messages each (10,000 total events)** → Verify no server crash, memory leak, or dropped messages → PASSED (Server processed all 10,000 messages successfully without crashing).

## Unchallenged Areas

- **Android App implementation** — Out of scope for this task (focused only on the receiver script and E2E python test suites).
- **Physical OS Emulation input validation** — The receiver does not currently implement any actual OS emulation dependencies (like pyautogui), so real OS emulation behavior could not be evaluated.
