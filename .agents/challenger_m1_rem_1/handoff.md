# Handoff Report: Challenger 1 M1 Remediation

## 1. Observation

1. In `receiver/receiver.py` (lines 28-31), malformed JSON and UTF-8 decode errors are caught:
   ```python
   28:                 data = json.loads(message)
   29:             except (json.JSONDecodeError, UnicodeDecodeError):
   30:                 print("Error: Malformed JSON payload received", file=sys.stderr)
   31:                 continue
   ```
2. In `receiver/receiver.py` (lines 52-54), coordinates are checked for finiteness:
   ```python
   52:                 if not math.isfinite(dx) or not math.isfinite(dy):
   53:                     print("Error: Invalid coordinates type in mouse_move event", file=sys.stderr)
   54:                     continue
   ```
3. Executing `python tests/run_tests.py` runs all 62 original tests successfully:
   ```text
   Ran 62 tests in 186.016s
   OK
   ```
4. Running the full test suite with `pytest` (including the added `tests/test_non_ascii.py` from other workers) raises a `UnicodeEncodeError` when trying to print a rocket emoji key `🚀`:
   ```text
   UnicodeEncodeError: 'charmap' codec can't encode character '\U0001f680' in position 22: character maps to <undefined>
   ```
   This error occurs in `receiver/receiver.py` line 84:
   ```python
   84:                 print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
   ```
5. We created `tests/test_challenge.py` to target invalid UTF-8 bytes (binary frame and raw TCP text frame) and Infinity/NaN coordinates, and verified that all 5 tests passed:
   ```text
   tests\test_challenge.py .....                                            [100%]
   ============================= 5 passed in 19.75s ==============================
   ```

## 2. Logic Chain

1. **Observation 1 & 5**: The try-except block in `receiver.py` handles `UnicodeDecodeError`. Our test `test_invalid_utf8_binary_frame` successfully sends invalid UTF-8 bytes `b'\xff\xff'`, which raises a `UnicodeDecodeError` in `json.loads` but is caught by the `except` block. The server prints the error message and continues. Our test `test_invalid_utf8_text_frame` sends invalid UTF-8 bytes directly inside a text frame over raw TCP, causing the `websockets` library to cleanly reject the connection with code 1007. The receiver process is unaffected and successfully handles new connections. Thus, the receiver is completely immune to crashing on invalid UTF-8 bytes.
2. **Observation 2 & 5**: The `math.isfinite` check in `receiver.py` successfully intercepts any `NaN` or `Infinity` coordinate values. Our tests in `test_challenge.py` confirm that NaN/Infinity literals and numeric overflows are correctly rejected with `Error: Invalid coordinates type in mouse_move event` and do not cause a crash or lockup.
3. **Observation 3**: Discovery and execution of the original 62 tests completes with all tests passing successfully.
4. **Observation 4**: Valid non-ASCII characters sent to the keyboard event handler cause the server to crash under Windows because standard output uses CP1252/ANSI by default when redirected, which cannot represent characters like `🚀`. This indicates a system-dependent vulnerability that can crash client connections.

## 3. Caveats

- **OS Emulation Hooks**: Host-level mouse and keyboard actions were not emulated directly, as `--mock` mode was active for all tests to protect the developer environment.
- **Process Startup Delays**: Under load, spawning a new Python subprocess for each test can occasionally exceed the default `5.0` seconds startup timeout in `asyncSetUp`, causing test runner flakiness.

## 4. Conclusion

The remediated code is immune to crashes when receiving invalid UTF-8 bytes and properly intercepts and rejects non-finite (NaN/Infinity) coordinates. However, a `UnicodeEncodeError` vulnerability exists when printing valid non-ASCII keyboard inputs (e.g. emojis) to redirected standard output on Windows, which crashes the websocket client connection handler.

## 5. Verification Method

To verify the test suite:
1. Run the test suite using pytest to execute all test files:
   ```bash
   pytest
   ```
2. Verify our challenge-specific robustness tests:
   ```bash
   pytest tests/test_challenge.py
   ```
   Ensure all 5 tests pass successfully.
