# Handoff Report: Receiver and E2E Test Suite Stress Analysis

## 1. Observation
We ran and analyzed the E2E test suites under idle conditions and under 100% CPU stress using the provided `tests/run_tests.py` and `tests/test_stress.py` scripts on Windows.

### Test Execution Results
- **Idle Conditions (CPU load ~19%)**:
  All 67 tests in the suite ran successfully and passed:
  ```
  Ran 67 tests in 120.139s
  OK
  ```
- **CPU Stress Conditions (CPU load 100% via `tests/cpu_stress.py`)**:
  Multiple tests failed with `TimeoutError` and `RuntimeError`. The run of `tests/run_tests.py` failed with:
  ```
  FAILED (failures=2, errors=6)
  ```
  Additionally, running `python -m unittest tests/test_stress.py` failed with 100% error rate (6/6 errors):
  ```
  FAILED (errors=6)
  ```

### Verbatim Errors (Timeout under CPU Stress)
Under CPU stress, the receiver process setup in `tests/test_stress.py` failed to complete within the hardcoded 5-second limit:
```
File "C:\Development\Monolith\tests\test_stress.py", line 29, in asyncSetUp
  line = await asyncio.wait_for(self.process.stdout.readline(), timeout=5.0)
...
File "C:\Development\Monolith\tests\test_stress.py", line 41, in asyncSetUp
  raise RuntimeError(f"Failed to read server startup log in time. Stderr: {stderr_data.decode('utf-8', errors='ignore')}")
RuntimeError: Failed to read server startup log in time. Stderr:
```
Similar `asyncio.exceptions.CancelledError` and `TimeoutError` occurred in `test_adversarial.py` and `test_cases.py` setups and assertions, e.g., in `send_and_assert_log`:
```
line_bytes = await asyncio.wait_for(self.process.stdout.readline(), timeout=1.0)
```

### Verbatim Errors (Unicode Encoding on Windows)
Running `tests/test_non_ascii.py` produced a `UnicodeEncodeError` in the receiver process stdout and a failure due to backslash escaping in stderr:
```
UnicodeEncodeError: 'charmap' codec can't encode character '\U0001f680' in position 22: character maps to <undefined>
```
and:
```
FAIL: test_non_ascii_unknown_event (test_non_ascii.TestNonAsciiKeyboardInput.test_non_ascii_unknown_event)
----------------------------------------------------------------------
Traceback (most recent call last):
...
AssertionError: 'Error: Unknown event type: \\U0001f680' != 'Error: Unknown event type: 🚀'
- Error: Unknown event type: \U0001f680
?                            ^^^^^^^^^^
+ Error: Unknown event type: 🚀
?                            ^
```

---

## 2. Logic Chain
1. **Flaky Test Failures**:
   - Under CPU stress, logical cores are pegged at 100% load (Observation: LoadPercentage 100%).
   - The test setup spawns a Python subprocess of `receiver/receiver.py` (Observation: `asyncio.create_subprocess_exec`).
   - The test setup expects the subprocess to boot and write a startup log to stdout within 5.0 seconds (Observation: `timeout=5.0`).
   - Because process spawning and socket initialization on Windows are severely throttled under CPU stress, the boot time exceeds 5.0 seconds.
   - Consequently, `asyncSetUp` raises a `TimeoutError` / `RuntimeError`, aborting the tests before they run.
   - Similarly, individual event read assertions use a tight `1.0s` or `2.0s` timeout (Observation: `timeout=1.0`), which is frequently breached under load.

2. **Windows Non-ASCII Crash (UnicodeEncodeError)**:
   - When a non-ASCII key (e.g. `🚀`) is sent to the receiver, the receiver prints it to stdout: `print(f"[KEYBOARD_INPUT] key: {key}", flush=True)` (Observation: `receiver.py:84`).
   - By default, standard output in sub-processes on Windows uses the system locale's ANSI code page (e.g. CP1252/charmap) unless forced to UTF-8.
   - Since `🚀` is not representable in CP1252, Python raises a `UnicodeEncodeError` and terminates the connection handler loop (Observation: `UnicodeEncodeError`).
   - If printed to stderr, Python falls back to `backslashreplace`, encoding `🚀` as `\\U0001f680`, causing E2E string assertions to fail (Observation: `AssertionError`).

3. **Potential Resource Leak (Port Exhaustion)**:
   - `TestTier1FeatureCoverage.asyncTearDown` closes the WebSocket via `await self.websocket.close()` without try/except handling (Observation: `test_cases.py:55`).
   - If `self.websocket.close()` raises an exception (e.g. due to connection disruption or unexpected close state), the method halts immediately.
   - The subsequent `self.process.terminate()` is never reached, leaving the `receiver.py` process running in the background and leaking its network port/socket.

---

## 3. Caveats
- CPU stress was simulated globally on all 16 logical cores using an infinite-loop Python process spawner (`multiprocessing.cpu_count()`). We did not test partial CPU starvation (e.g. 50% load).
- We assumed the host environment's Python execution environment does not have `PYTHONIOENCODING=utf-8` set as a global Windows environment variable. If forced globally, the UnicodeEncodeError would not manifest, but the E2E test suite should be resilient to default Windows console configurations.

---

## 4. Conclusion
The receiver and E2E test suites are **unstable under CPU stress** on Windows. The failures are caused by:
1. Hardcoded, tight timeouts (5.0s for setup, 1.0s/2.0s for event processing) that are easily violated under resource constraints.
2. An unhandled `UnicodeEncodeError` when the receiver prints non-ASCII inputs (e.g., emojis) to a non-UTF-8 stdout/stderr stream on Windows.
3. Vulnerable process teardown logic in `TestTier1FeatureCoverage` that can cause zombie processes and socket leaks when WebSocket closure fails.

---

## 5. Verification Method
To verify these issues:
1. **CPU Stress Simulation**:
   Run `python tests/cpu_stress.py 60` in the background (pegs CPU to 100% load).
2. **Execute E2E Suite**:
   Run `python -u tests/run_tests.py` or `python -u -m unittest tests/test_stress.py` while the stress is running.
3. **Invalidation Conditions**:
   - The test runner will exit with code `1` showing `TimeoutError` or `RuntimeError` in `asyncSetUp`.
   - `test_non_ascii_keyboard_input` and `test_non_ascii_unknown_event` will fail due to encoding errors or assertion mismatches.
