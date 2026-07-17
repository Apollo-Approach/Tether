## Review Summary

**Verdict**: REQUEST_CHANGES

## Findings

### Major Finding 1: Incomplete Test Suite (Tier 3 and Tier 4 missing)

- **What**: Test cases specified in `TEST_INFRA.md` for Tier 3 (Cross-Feature Interactions) and Tier 4 (Real-World Scenarios) are not implemented in the test suites.
- **Where**: `tests/test_cases.py`, `tests/test_adversarial.py`, and `tests/stress_tests.py` vs. `TEST_INFRA.md` lines 107-123.
- **Why**: It fails to verify complex workflows and sequence combinations (e.g., dragging, shift-clicks, ctrl-c, drawing circles, connection recovery), which might contain state management bugs.
- **Suggestion**: Implement the missing Tier 3 and Tier 4 test cases using the async test framework, validating correct sequencing in the log outputs.

### Major Finding 2: Missing Tier 2 Edge Cases & Coordinate Clamping

- **What**: Edge cases like `test_mouse_move_large_dx`/`dy` (clamping), `test_keyboard_empty_key`, and `test_keyboard_very_long_key` are specified in `TEST_INFRA.md` but are missing from the tests and unimplemented in `receiver/receiver.py`.
- **Where**: `receiver/receiver.py` (lines 41-75) and `tests/test_adversarial.py`.
- **Why**: Large coordinate inputs are not clamped, which can crash OS-level libraries (e.g. pyautogui) in non-mock mode. Empty and excessively long keyboard keys are logged without validation or truncation.
- **Suggestion**: Implement clamping for `dx`/`dy` (e.g., to screen bounds or a max threshold) and truncation/denial for `key` lengths. Add corresponding E2E test cases.

### Minor Finding 1: Unused `get_free_port` Utility

- **What**: The `get_free_port` helper function is defined but never used.
- **Where**: `tests/stress_tests.py` lines 14-19.
- **Why**: Redundant code. The suite successfully uses `--port 0` for dynamic port assignment.
- **Suggestion**: Remove the unused utility function to keep the test codebase clean.

## Verified Claims

- **WebSocket connection works under dynamic port allocation (`--port 0`)** -> verified via running `python tests/run_tests.py` -> pass
- **Validates mouse move payload coordinate types** -> verified via `test_mouse_move_invalid_types` in `test_adversarial.py` -> pass
- **Rejects malformed JSON** -> verified via `test_malformed_json_raw_string` -> pass
- **Handles multiple concurrent client connections** -> verified via `test_concurrent_connections` -> pass
- **Handles connection drops and reconnection** -> verified via `test_abrupt_connection_drop_and_reconnect` -> pass

## Coverage Gaps

- **OS-level Emulation (when not in mock mode)** — risk level: medium — recommendation: investigate how PyAutoGUI or a similar emulation library should be integrated when `--mock` is not provided.
- **Coordinate Clamping / Key Truncation** — risk level: medium — recommendation: implement input sanitization to prevent crash/DoS conditions.

## Unverified Items

- **OS-level emulation execution** — reason not verified: PyAutoGUI is not installed, and the receiver implementation contains no emulation logic (it only prints messages).
