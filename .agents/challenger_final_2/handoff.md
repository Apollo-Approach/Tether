# Handoff Report — Empirical Challenge of Receiver and Test Suites

## 1. Observation

### Test Runner and pytest Execution
- Standard execution of the test suite (62 tests discovered by `tests/run_tests.py`, 67 tests discovered by `pytest tests/`) succeeds completely when the host CPU is under normal load:
```text
Ran 62 tests in 188.366s
OK
```
And under pytest:
```text
======================= 67 passed in 110.39s (0:01:50) ========================
```

### Instability Under CPU Stress
- Spawning a background CPU load generator (`python tests/cpu_stress.py 120`) that stresses all CPU cores on Windows and then executing `python tests/run_tests.py` causes multiple test failures with `ERROR` status.
- Specifically, the following traceback was observed from `tests/test_adversarial.py` and `tests/test_cases.py` setups:
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
  File "C:\Development\Monolith\tests\test_adversarial.py", line 20, in asyncSetUp
    line = await asyncio.wait_for(self.process.stdout.readline(), timeout=5.0)
           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\tasks.py", line 519, in wait_for
    async with timeouts.timeout(timeout):
               ^^^^^^^^^^^^^^^^^^^^^^^^^
  File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\asyncio\timeouts.py", line 115, in __aexit__
    raise TimeoutError from exc_val
TimeoutError
```

### UnicodeEncodeError (Vulnerability)
- Spawning the receiver on a Windows system with default active code page encoding (CP1252) and sending a valid keyboard input containing a non-ASCII character (e.g. `{"event": "keyboard_input", "key": "🚀"}`) crashes the receiver connection handler with the following traceback on standard error:
```text
Receiver stderr: connection handler failed
Traceback (most recent call last):
  File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\site-packages\websockets\asyncio\server.py", line 374, in conn_handler
    await self.handler(connection)
  File "C:\Development\Monolith\receiver\receiver.py", line 84, in handle_client
    print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
  File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\encodings\cp1252.py", line 19, in encode
    return codecs.charmap_encode(input,self.errors,encoding_table)[0]
           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
UnicodeEncodeError: 'charmap' codec can't encode character '\U0001f680' in position 22: character maps to <undefined>
```

### Escaped Error Logging on Stderr
- Sending an unknown event containing non-ASCII characters (`{"event": "🚀"}`) outputs the following backslash-escaped representation to `sys.stderr` instead of the original raw characters:
```text
Receiver stderr line: Error: Unknown event type: \U0001f680
```
This is because Python’s `sys.stderr` stream uses the `backslashreplace` error handler by default.

---

## 2. Logic Chain

1. **Subprocess Startup Delays under Stress**: When the host system is under 100% CPU stress, Windows process creation and Python module imports (`websockets`, `asyncio`, etc.) are heavily delayed.
2. **Hardcoded Setup Timeouts**: The E2E tests in `tests/test_cases.py`, `tests/test_adversarial.py`, and `tests/test_challenge.py` use a hardcoded timeout of `5.0` seconds in `asyncSetUp` to wait for the receiver's startup line `Server listening on ws://...`.
3. **Flakiness Conclusion**: Because the process startup time exceeds 5.0 seconds under heavy CPU load, the tests fail in their setup phase with `TimeoutError` / `RuntimeError`. Therefore, the test suites are *unstable* and *flaky* under CPU stress.
4. **Strict Standard Output Encoding**: Python's `sys.stdout` on Windows defaults to the system code page (typically CP1252) and uses `strict` error handling.
5. **Unicode Crash Conclusion**: When the receiver prints a keyboard event containing non-ASCII character(s) (such as a rocket emoji `🚀`), standard output fails to encode it and raises `UnicodeEncodeError`. Because the print statement runs inside the connection handler, this unhandled exception crashes the connection, disconnecting the client immediately. This represents a functional defect and denial-of-service vulnerability.

---

## 3. Caveats
- Emulation of physical input (e.g. PyAutoGUI/mouse cursor movements) is disabled via the `--mock` flag in E2E tests. The behavior and timing characteristics under real physical input capture on a Windows desktop environment were not tested.
- Port exhaustion was not fully reached in practice because the tests are run sequentially, and the total test count (67) is well below the ephemeral port pool limit (typically 16k on Windows). However, if tests are run in parallel, sockets remaining in `TIME_WAIT` state could theoretically exhaust ports.

---

## 4. Conclusion
- The test suite is functionally correct under normal load, but **unstable and flaky under CPU stress** due to low hardcoded timeouts (5.0s in `asyncSetUp`, 1.0s in logging assertions).
- The receiver has a **severe reliability defect on Windows** where sending any non-ASCII keyboard key crashes the connection handler task with a `UnicodeEncodeError` because standard output does not force UTF-8 mode.
- Actionable recommendations:
  1. Set `PYTHONUTF8=1` in the environment when launching the receiver, or configure `sys.stdout` / `sys.stderr` to use UTF-8 encoding or `backslashreplace` error handling to prevent encoding crashes.
  2. Increase the E2E setup timeout in `asyncSetUp` from `5.0` seconds to at least `15.0` seconds, and assertion timeouts from `1.0` second to `5.0` seconds to support execution under CPU stress.

---

## 5. Verification Method

### Verify CPU Stress Instability
1. Start the CPU stress generator:
   ```powershell
   python tests/cpu_stress.py 60
   ```
2. Run the test suite concurrently in another terminal:
   ```powershell
   python tests/run_tests.py
   ```
3. Observe multiple test setup timeout failures on standard output.

### Verify Unicode Connection Crash
1. Run the non-ASCII test file created in the `tests/` directory:
   ```powershell
   python -m unittest tests/test_non_ascii.py
   ```
2. Observe `test_non_ascii_keyboard_input` failure with `UnicodeEncodeError` in standard error logs.
