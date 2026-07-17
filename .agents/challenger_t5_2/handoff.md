# Handoff Report - Challenger 2

## 1. Observation
- **File**: `c:\Development\Monolith\receiver\receiver.py`
  - Lines 73-84:
    ```python
            elif event == "keyboard_input":
                key = data.get("key")
                if key is None:
                    print("Error: Missing key in keyboard_input event", file=sys.stderr)
                    continue
                if not isinstance(key, str):
                    print("Error: Invalid key type in keyboard_input event", file=sys.stderr)
                    continue
                if key == "" or len(key) > 100:
                    print("Error: Invalid key type or value in keyboard_input event", file=sys.stderr)
                    continue
                print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
    ```
- **File**: `c:\Development\Monolith\android\app\src\main\java\com\antigravity\remote\KeyMapper.kt`
  - Lines 7-20:
    ```kotlin
        fun mapKey(key: Key): String? {
            return when (key) {
                Key.Enter -> "Enter"
                Key.Backspace -> "Backspace"
                Key.Spacebar -> "Space"
                Key.ShiftLeft, Key.ShiftRight -> "Shift"
                Key.CtrlLeft, Key.CtrlRight -> "Ctrl"
                Key.AltLeft, Key.AltRight -> "Alt"
                Key.Escape -> "Escape"
                Key.DirectionUp -> "ArrowUp"
                Key.DirectionDown -> "ArrowDown"
                Key.DirectionLeft -> "ArrowLeft"
                Key.DirectionRight -> "ArrowRight"
                else -> null
            }
        }
    ```
- **File**: `c:\Development\Monolith\android\app\src\main\java\com\antigravity\remote\MainActivity.kt`
  - Lines 268-303:
    Contains the Compose key event handling logic, which filters key events by `KeyEventType.KeyDown` only, and does not capture KeyUp.
- **Traceback**: Executed `python -m unittest tests/test_keyboard_adversarial.py` and captured the following traceback on the receiver's stderr when sending the unpaired surrogate `\uD83D`:
  ```
  connection handler failed
  Traceback (most recent call last):
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\site-packages\websockets\asyncio\server.py", line 374, in conn_handler
      await self.handler(connection)
    File "C:\Development\Monolith\receiver\receiver.py", line 84, in handle_client
      print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
  UnicodeEncodeError: 'utf-8' codec can't encode character '\ud83d' in position 22: surrogates not allowed
  ```

---

## 2. Logic Chain
1. `receiver.py` parses WebSocket payloads and extracts the `key` string.
2. If `key` is an unpaired surrogate (e.g. `\uD83D`), it is decoded successfully by Python's `json.loads` as `\ud83d` (a surrogate code point).
3. The receiver reconfigures `sys.stdout` to use `utf-8` without specifying an error handler (e.g. `errors='replace'`).
4. When `print(f"[KEYBOARD_INPUT] key: {key}")` runs, Python attempts to encode the surrogate character `\ud83d` into UTF-8.
5. UTF-8 does not permit encoding of surrogate code points, resulting in a `UnicodeEncodeError`.
6. This exception escapes the client handler coroutine, crashing the connection task and disconnecting the client.
7. Furthermore, the protocol does not transmit separate KeyDown/KeyUp events, meaning modifier keys cannot be held down concurrently.
8. Meta keys (Windows/Command) are not mapped at all in `KeyMapper.kt` and return `null`.

---

## 3. Caveats
- Testing was performed in mock/dry-run mode (`--mock`). OS-level key injection was not tested directly, but protocol validation was thoroughly verified.
- The behavior of terminal injection via ANSI escape sequences depends on the terminal emulator running the receiver. Some shells may sanitize escape codes, while others may execute them (e.g. screen clearing).

---

## 4. Conclusion
- The receiver WebSocket server has a high-severity vulnerability where malformed Unicode inputs (specifically unpaired UTF-16 surrogates) crash connection handler tasks.
- Key mapping coverage has significant gaps on the client side (missing Meta key, Tab, CapsLock, Delete, Insert, Home, End, PageUp, PageDown, PrintScreen, F1-F12 keys).
- The protocol design lacks key statefulness (KeyDown/KeyUp), preventing modifier key combos from being held or chained.

---

## 5. Verification Method

### Test Cases Created in `tests/test_keyboard_adversarial.py`
1. `test_unpaired_surrogate_utf16`: Sends unpaired high surrogate `\uD83D` and verifies that the connection drops due to `UnicodeEncodeError` in stderr.
2. `test_zwj_joined_emoji_length_rejection`: Sends a sequence of 15 ZWJ family emojis `👨‍👩‍👧‍👦` (length 105 in Python) to verify that string lengths are checked correctly.
3. `test_control_character_log_injection`: Sends raw control characters (BEL, ESC ANSI escape sequences) to check if they are logged without sanitization.
4. `test_null_key` / `test_boolean_key`: Verifies that null/boolean values in keyboard events are rejected.

### Command to Run Tests
To run just the new keyboard adversarial tests:
```bash
python -m unittest tests/test_keyboard_adversarial.py
```
To run the full E2E test suite (including the new ones):
```bash
python tests/run_tests.py
```

### Full Test Output (stdout/stderr)
```
Discovering and running tests...
test_abrupt_connection_drop_and_reconnect (test_adversarial.TestAdversarialAndStress.test_abrupt_connection_drop_and_reconnect) ... ok
test_concurrent_connections (test_adversarial.TestAdversarialAndStress.test_concurrent_connections) ... ok
test_keyboard_input_invalid_key_type (test_adversarial.TestAdversarialAndStress.test_keyboard_input_invalid_key_type) ... ok
test_keyboard_input_missing_key (test_adversarial.TestAdversarialAndStress.test_keyboard_input_missing_key) ... ok
test_malformed_json_raw_string (test_adversarial.TestAdversarialAndStress.test_malformed_json_raw_string) ... ok
test_malformed_json_unclosed_brace (test_adversarial.TestAdversarialAndStress.test_malformed_json_unclosed_brace) ... ok
test_missing_event_field (test_adversarial.TestAdversarialAndStress.test_missing_event_field) ... ok
test_mouse_click_invalid_button_type (test_adversarial.TestAdversarialAndStress.test_mouse_click_invalid_button_type) ... ok
test_mouse_click_missing_button (test_adversarial.TestAdversarialAndStress.test_mouse_click_missing_button) ... ok
test_mouse_move_invalid_types (test_adversarial.TestAdversarialAndStress.test_mouse_move_invalid_types) ... ok
test_mouse_move_missing_dy (test_adversarial.TestAdversarialAndStress.test_mouse_move_missing_dy) ... ok
test_rapid_request_stress (test_adversarial.TestAdversarialAndStress.test_rapid_request_stress) ... ok
test_unknown_event_type (test_adversarial.TestAdversarialAndStress.test_unknown_event_type) ... ok
test_accidental_connection_drop (test_cases.TestE2ETestsTiers2To4.test_accidental_connection_drop) ... ok
test_code_navigation_workflow (test_cases.TestE2ETestsTiers2To4.test_code_navigation_workflow) ... ok
test_ctrl_c_combination (test_cases.TestE2ETestsTiers2To4.test_ctrl_c_combination) ... ok
test_double_click_selection (test_cases.TestE2ETestsTiers2To4.test_double_click_selection) ... ok
test_drag_interaction (test_cases.TestE2ETestsTiers2To4.test_drag_interaction) ... ok
test_draw_circle_workflow (test_cases.TestE2ETestsTiers2To4.test_draw_circle_workflow) ... ok
test_extra_unsupported_fields (test_cases.TestE2ETestsTiers2To4.test_extra_unsupported_fields) ... ok
test_extremely_rapid_requests (test_cases.TestE2ETestsTiers2To4.test_extremely_rapid_requests) ... ok
test_keyboard_empty_key (test_cases.TestE2ETestsTiers2To4.test_keyboard_empty_key) ... ok
test_keyboard_missing_key (test_cases.TestE2ETestsTiers2To4.test_keyboard_missing_key) ... ok
test_keyboard_very_long_key (test_cases.TestE2ETestsTiers2To4.test_keyboard_very_long_key) ... ok
test_malformed_json (test_cases.TestE2ETestsTiers2To4.test_malformed_json) ... ok
test_missing_event_field (test_cases.TestE2ETestsTiers2To4.test_missing_event_field) ... ok
test_mouse_click_invalid_button (test_cases.TestE2ETestsTiers2To4.test_mouse_click_invalid_button) ... ok
test_mouse_click_missing_button (test_cases.TestE2ETestsTiers2To4.test_mouse_click_missing_button) ... ok
test_mouse_move_inf (test_cases.TestE2ETestsTiers2To4.test_mouse_move_inf) ... ok
test_mouse_move_invalid_types (test_cases.TestE2ETestsTiers2To4.test_mouse_move_invalid_types) ... ok
test_mouse_move_large_dx (test_cases.TestE2ETestsTiers2To4.test_mouse_move_large_dx) ... ok
test_mouse_move_large_dy (test_cases.TestE2ETestsTiers2To4.test_mouse_move_large_dy) ... ok
test_mouse_move_missing_dy (test_cases.TestE2ETestsTiers2To4.test_mouse_move_missing_dy) ... ok
test_mouse_move_nan (test_cases.TestE2ETestsTiers2To4.test_mouse_move_nan) ... ok
test_move_and_type (test_cases.TestE2ETestsTiers2To4.test_move_and_type) ... ok
test_null_values (test_cases.TestE2ETestsTiers2To4.test_null_values) ... ok
test_shift_click (test_cases.TestE2ETestsTiers2To4.test_shift_click) ... ok
test_type_sentence_workflow (test_cases.TestE2ETestsTiers2To4.test_type_sentence_workflow) ... ok
test_unknown_event (test_cases.TestE2ETestsTiers2To4.test_unknown_event) ... ok
test_keyboard_modifier_shift (test_cases.TestTier1FeatureCoverage.test_keyboard_modifier_shift) ... ok
test_keyboard_number (test_cases.TestTier1FeatureCoverage.test_keyboard_number) ... ok
test_keyboard_single_char (test_cases.TestTier1FeatureCoverage.test_keyboard_single_char) ... ok
test_keyboard_special_backspace (test_cases.TestTier1FeatureCoverage.test_keyboard_special_backspace) ... ok
test_keyboard_special_enter (test_cases.TestTier1FeatureCoverage.test_keyboard_special_enter) ... ok
test_keyboard_special_space (test_cases.TestTier1FeatureCoverage.test_keyboard_special_space) ... ok
test_keyboard_uppercase_char (test_cases.TestTier1FeatureCoverage.test_keyboard_uppercase_char) ... ok
test_mouse_click_left (test_cases.TestTier1FeatureCoverage.test_mouse_click_left) ... ok
test_mouse_click_middle (test_cases.TestTier1FeatureCoverage.test_mouse_click_middle) ... ok
test_mouse_click_rapid_left (test_cases.TestTier1FeatureCoverage.test_mouse_click_rapid_left) ... ok
test_mouse_click_right (test_cases.TestTier1FeatureCoverage.test_mouse_click_right) ... ok
test_mouse_click_sequence_left_right (test_cases.TestTier1FeatureCoverage.test_mouse_click_sequence_left_right) ... ok
test_mouse_move_integers (test_cases.TestTier1FeatureCoverage.test_mouse_move_integers) ... ok
test_mouse_move_negative (test_cases.TestTier1FeatureCoverage.test_mouse_move_negative) ... ok
test_mouse_move_positive (test_cases.TestTier1FeatureCoverage.test_mouse_move_positive) ... ok
test_mouse_move_precision (test_cases.TestTier1FeatureCoverage.test_mouse_move_precision) ... ok
test_mouse_move_zero (test_cases.TestTier1FeatureCoverage.test_mouse_move_zero) ... ok
test_coordinates_infinity_literal (test_challenge.TestChallengeRobustness.test_coordinates_infinity_literal)
Test sending JSON with Infinity / -Infinity literals. ... ok
test_coordinates_nan_literal (test_challenge.TestChallengeRobustness.test_coordinates_nan_literal)
Test sending JSON with NaN literals. ... ok
test_coordinates_overflow_to_infinity (test_challenge.TestChallengeRobustness.test_coordinates_overflow_to_infinity)
Test sending numeric values that overflow to infinity in Python's float conversion. ... ok
test_invalid_utf8_binary_frame (test_challenge.TestChallengeRobustness.test_invalid_utf8_binary_frame)
Test sending invalid UTF-8 bytes as a binary frame. ... ok
test_invalid_utf8_text_frame (test_challenge.TestChallengeRobustness.test_invalid_utf8_text_frame)
Test sending invalid UTF-8 bytes directly inside a text frame over raw TCP. ... ok
test_coordinates_huge_integer_crash (test_challenger_adversarial.TestChallengerAdversarial.test_coordinates_huge_integer_crash)
Test sending coordinates that exceed Python's float conversion capabilities (OverflowError). ... ok
test_invalid_event_type_bool (test_challenger_adversarial.TestChallengerAdversarial.test_invalid_event_type_bool)
Test event field containing a boolean. ... ok
test_invalid_event_type_dict (test_challenger_adversarial.TestChallengerAdversarial.test_invalid_event_type_dict)
Test event field containing an object. ... ok
test_invalid_event_type_int (test_challenger_adversarial.TestChallengerAdversarial.test_invalid_event_type_int)
Test event field containing an integer. ... ok
test_invalid_event_type_list (test_challenger_adversarial.TestChallengerAdversarial.test_invalid_event_type_list)
Test event field containing a list. ... ok
test_lone_surrogate_key_crash_high (test_challenger_adversarial.TestChallengerAdversarial.test_lone_surrogate_key_crash_high)
Test sending a lone high surrogate character, causing UnicodeEncodeError on print. ... ok
test_lone_surrogate_key_crash_low (test_challenger_adversarial.TestChallengerAdversarial.test_lone_surrogate_key_crash_low)
Test sending a lone low surrogate character, causing UnicodeEncodeError on print. ... ok
test_non_dict_payload_bool (test_challenger_adversarial.TestChallengerAdversarial.test_non_dict_payload_bool)
Test sending a valid JSON boolean. ... ok
test_non_dict_payload_list (test_challenger_adversarial.TestChallengerAdversarial.test_non_dict_payload_list)
Test sending a valid JSON list which is not a dictionary. ... ok
test_non_dict_payload_null (test_challenger_adversarial.TestChallengerAdversarial.test_non_dict_payload_null)
Test sending a valid JSON null. ... ok
test_non_dict_payload_num (test_challenger_adversarial.TestChallengerAdversarial.test_non_dict_payload_num)
Test sending a valid JSON number. ... ok
test_non_dict_payload_str (test_challenger_adversarial.TestChallengerAdversarial.test_non_dict_payload_str)
Test sending a valid JSON string (which is parsed as a string). ... ok
test_rapid_sequential_connections_stress (test_challenger_adversarial.TestChallengerAdversarial.test_rapid_sequential_connections_stress)
Test sequentially opening and abruptly closing 50 connections to ensure server remains healthy. ... ok
test_boolean_key (test_keyboard_adversarial.TestKeyboardAdversarial.test_boolean_key)
Send a JSON payload with key as boolean. ... ok
test_control_character_log_injection (test_keyboard_adversarial.TestKeyboardAdversarial.test_control_character_log_injection)
Check if control characters like BEL, ESC (ANSI escape sequence) are logged without sanitization. ... ok
test_null_key (test_keyboard_adversarial.TestKeyboardAdversarial.test_null_key)
Send a JSON payload with key as null. ... ok
test_unpaired_surrogate_utf16 (test_keyboard_adversarial.TestKeyboardAdversarial.test_unpaired_surrogate_utf16)
Send an unpaired UTF-16 surrogate to check for UnicodeEncodeError crash/disconnect. ... ok
test_zwj_joined_emoji_length_rejection (test_keyboard_adversarial.TestKeyboardAdversarial.test_zwj_joined_emoji_length_rejection)
Verify that ZWJ joined emoji sequences exceeding length 100 are rejected even if visually short. ... ok
test_non_ascii_keyboard_input (test_non_ascii.TestNonAsciiKeyboardInput.test_non_ascii_keyboard_input) ... ok
test_non_ascii_unknown_event (test_non_ascii.TestNonAsciiKeyboardInput.test_non_ascii_unknown_event) ... ok
test_connection_drops (test_stress.TestStressAndCornerCases.test_connection_drops)
Verify the receiver continues functioning after connection drops. ... ok
test_malformed_json_streams (test_stress.TestStressAndCornerCases.test_malformed_json_streams)
Verify receiver handles malformed JSON and keeps running. ... ok
test_massive_payload_size (test_stress.TestStressAndCornerCases.test_massive_payload_size)
Verify behaviour when receiving massive payloads (exceeding standard limits). ... ok
test_missing_fields_and_unknown_events (test_stress.TestStressAndCornerCases.test_missing_fields_and_unknown_events)
Verify receiver gracefully rejects messages with missing fields or unknown events. ... ok
test_rapid_multiple_client_connections (test_stress.TestStressAndCornerCases.test_rapid_multiple_client_connections)
Verify the receiver script can handle multiple concurrent connections. ... ok
test_unexpected_types_in_fields (test_stress.TestStressAndCornerCases.test_unexpected_types_in_fields)
Verify receiver behaviour when data fields have unexpected types. ... ok
test_unicode_and_modifiers_under_stress (test_unicode_modifiers_stress.TestUnicodeModifiersStress.test_unicode_and_modifiers_under_stress)
Stress-test Unicode inputs (surrogate pairs) and modifier combinations. ... Spawning 16 CPU-spinning processes for 5.0 seconds...
[DEBUG] Connection closed as expected: received 1011 (internal error); then sent 1011 (internal error)
[DEBUG] Stderr traceback:
connection handler failed
Traceback (most recent call last):
  File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\site-packages\websockets\asyncio\server.py", line 374, in conn_handler
    await self.handler(connection)
  File "C:\Development\Monolith\receiver\receiver.py", line 84, in handle_client
    print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
UnicodeEncodeError: 'utf-8' codec can't encode character '\ud83d' in position 22: surrogates not allowed

Receiver stdout: [KEYBOARD_INPUT] key: 🚀
Receiver stderr line: Error: Unknown event type: 🚀
Sending 200 key events under CPU stress...
Reading and verifying logs from receiver...
Executing <Task pending name='Task-683' coro=<TestUnicodeModifiersStress.test_unicode_and_modifiers_under_stress() running at C:\Development\Monolith\tests\test_unicode_modifiers_stress.py:131> wait_for=<Future pending cb=[Task.task_wakeup()] created at C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\base_events.py:448> cb=[_run_until_complete_cb() at C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\base_events.py:181] created at C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\runners.py:100> took 0.172 seconds
Stopping stress...
All key events successfully verified under CPU stress!
Executing <Task finished name='Task-683' coro=<TestUnicodeModifiersStress.test_unicode_and_modifiers_under_stress() done, defined at C:\Development\Monolith\tests\test_unicode_modifiers_stress.py:75> result=None created at C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\runners.py:100> took 2.750 seconds
ok
test_unicode_and_shortcuts_stress (test_unicode_shortcuts_stress.TestUnicodeAndShortcutsStress.test_unicode_and_shortcuts_stress) ... Executing <Task pending name='Task-690' coro=<TestUnicodeAndShortcutsStress.test_unicode_and_shortcuts_stress() running at C:\Development\Monolith\tests\test_unicode_shortcuts_stress.py:72> wait_for=<Future pending cb=[Task.task_wakeup()] created at C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\base_events.py:448> cb=[_run_until_complete_cb() at C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\base_events.py:181] created at C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\runners.py:100> took 0.141 seconds
ok

----------------------------------------------------------------------
Ran 89 tests in 263.234s

OK
Successfully processed 500 stress events (including surrogate pairs and modifier combinations) without failures or lost keystrokes.
```
