# Handoff Report: E2E and Receiver Stress Testing & Empirical Challenge

## 1. Observation

- **Command executed**: `python tests/run_tests.py` ran successfully in first run with 30 tests passing in `70.845s`.
- **Command executed**: `python -m unittest tests/stress_tests.py` ran successfully with 6 tests passing in `15.242s`.
- **Stress Harness Loop Execution**: During sequential repetition of the test suite (10 times) under load:
  - Run 1, 3: PASSED
  - Run 2: FAILED with `RuntimeError: Failed to read server startup log in time. Stderr:` (line 22: `test_keyboard_modifier_shift` in `tests/test_cases.py`).
  - Run 4: FAILED with `RuntimeError: Failed to read server startup log in time. Stderr:` (line 100: `test_mouse_click_invalid_button_type` in `tests/test_adversarial.py`).
- **Verbatim Error Stack Trace**:
  ```text
  Traceback (most recent call last):
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\tasks.py", line 520, in wait_for
      return await fut
             ^^^^^^^^^
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\streams.py", line 568, in readline
      line = await self.readuntil(sep)
             ^^^^^^^^^^^^^^^^^^^^^^^^^
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\streams.py", line 660, in readuntil
      await self._wait_for_data('readuntil')
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\streams.py", line 545, in _wait_for_data
      await self._waiter
  asyncio.exceptions.CancelledError

  The above exception was the direct cause of the following exception:

  Traceback (most recent call last):
    File "c:\Development\Monolith\tests\test_adversarial.py", line 28, in asyncSetUp
      line = await asyncio.wait_for(self.process.stdout.readline(), timeout=3.0)
             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\tasks.py", line 519, in wait_for
      async with timeouts.timeout(timeout):
                 ^^^^^^^^^^^^^^^^^^^^^^^^^
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\timeouts.py", line 115, in __aexit__
      raise TimeoutError from exc_val
  TimeoutError

  During handling of the above exception, another exception occurred:

  Traceback (most recent call last):
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\runners.py", line 118, in run
      return self._loop.run_until_complete(task)
             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\base_events.py", line 691, in run_until_complete
      return future.result()
             ^^^^^^^^^^^^^^^
    File "c:\Development\Monolith\tests\test_adversarial.py", line 40, in asyncSetUp
      raise RuntimeError(f"Failed to read server startup log in time. Stderr: {stderr_data.decode('utf-8', errors='ignore')}")
  RuntimeError: Failed to read server startup log in time. Stderr: 
  ```
- **AsyncIO Event Loop Lag Warnings**:
  - `Executing <Task pending ... asyncSetUp()> took 2.188 seconds`
  - `Executing <TimerHandle ... Timeout._on_timeout()> took 0.875 seconds`
- **Missing Test Implementations (versus `TEST_INFRA.md`)**:
  - In `test_adversarial.py` and `test_cases.py`, the following tests listed under Tier 2, 3, and 4 in `TEST_INFRA.md` are missing:
    - Coordinate clamping tests (`test_mouse_move_large_dx` and `dy`)
    - Empty key / long key validation tests (`test_keyboard_empty_key`, `test_keyboard_very_long_key`)
    - Interaction tests (drag-and-drop, shift-click, ctrl-c, move-and-type)
    - Slower workflow tests (drawing circle, typing sentence, code navigation, double-click)
- **Receiver Input Validation**:
  - In `receiver/receiver.py`, coordinates are checked with:
    `if (not isinstance(dx, (int, float)) or isinstance(dx, bool) or not isinstance(dy, (int, float)) or isinstance(dy, bool))`
  - However, there are no checks for `math.isnan()` or `math.isinf()`.
  - There is no check for empty keyboard key string (`key == ""`) or maximum string length limit on keyboard key.

## 2. Logic Chain

- **Observation of flakiness**: In sequential loop executions of `run_tests.py`, we observed runs failing with `TimeoutError` in `asyncSetUp` (Run 2, Run 4).
- **Explanation of flakiness**: Spawning a subprocess (`receiver.py`) for *every single test case* creates process scheduling overhead. On Windows, Python interpreter initialization and package import (`websockets`) often exceed the hardcoded `3.0s` timeout when CPU usage spikes or scheduler delays happen (evidenced by the 2.188s asyncio event loop block warning).
- **Therefore**, the flakiness is **NOT** fully resolved, and is caused by an overly restrictive timeout during process startup in the test infrastructure setup.
- **Verification of receiver correctness under load**: When flooded with 100 concurrent connections sending 100 messages each (10,000 total events) to a single running instance of `receiver.py` (via our `stress_harness.py`), the receiver processed all 10,000 messages and did not crash or hang.
- **Therefore**, the receiver itself handles concurrent connections and message streams robustly under load, but suffers from logic-level concurrency issues (interleaving OS mouse/keyboard actions) and missing range/special float input validation (such as `NaN` or `Infinity`).

## 3. Caveats

- We tested the receiver using `--mock` mode. The physical OS emulation layer (e.g., `pyautogui` or other OS API) was not tested because no emulation logic is implemented in `receiver.py` (it only prints logs to stdout/stderr in both mock and normal modes).
- Ephemeral port exhaustion was not observed under our stress load, but remains a minor risk on systems running other heavy network applications due to the repeated creation and destruction of sockets.

## 4. Conclusion

The flakiness in the E2E test suite is **NOT** fully resolved. It is caused by an aggressive `3.0s` process startup timeout in `asyncSetUp` under load. Additionally, the E2E test coverage is incomplete compared to `TEST_INFRA.md`, and the receiver itself lacks validation against special float values (`NaN`, `Infinity`), string lengths, and multi-user concurrency race conditions.

## 5. Verification Method

To independently verify these findings:

1. **Verify Test Flakiness**:
   Run the test runner multiple times in a loop (e.g. 5-10 times) under system CPU load:
   ```powershell
   for ($i=1; $i -le 10; $i++) { python tests/run_tests.py }
   ```
   Inspect if any runs fail with `RuntimeError: Failed to read server startup log in time`.

2. **Verify Missing Validation / Missing Tests**:
   - Inspect `tests/test_adversarial.py` and `tests/test_cases.py` to confirm they lack tests for `test_keyboard_empty_key`, `test_keyboard_very_long_key`, and coordinate clamping (`dx: 1e6`).
   - Run a websocket client and send a payload containing `NaN` or `Infinity`:
     ```json
     {"event": "mouse_move", "dx": NaN, "dy": Infinity}
     ```
     Observe that the receiver logs it to stdout as `[MOUSE_MOVE] dx: nan, dy: inf` instead of rejecting it.
