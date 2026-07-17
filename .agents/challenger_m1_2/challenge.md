# M1 Environment & Project Init - Challenge Report

## Challenge Summary

**Overall risk assessment**: HIGH

Through empirical verification of the M1 environment, build pipeline, and Python receiver script, we have identified multiple critical bugs and architectural issues that impact system stability, reliability, and correctness.

---

## Challenges

### [High] Challenge 1: Build Instability under Gradle Configuration Cache
- **Assumption challenged**: Running clean builds (`.\gradlew clean assembleDebug`) consistently compiles successfully.
- **Attack scenario**: When running `.\gradlew clean assembleDebug` consecutively, Gradle reuses the configuration cache. If `clean` runs, it deletes the `build/` output directory. However, when the configuration cache is reused, Gradle incorrectly skips the `:app:writeDebugAppMetadata` task, believing its output is still up-to-date or not correctly tracking its output's deletion. Subsequently, the `:app:packageDebug` task fails because the expected `app-metadata.properties` file does not exist.
- **Blast radius**: The developer build pipeline or CI/CD pipelines will fail consistently on subsequent clean builds unless the configuration cache is disabled or the task input/output contracts are corrected.
- **Mitigation**:
  1. Add proper task dependency or input/output declarations for the metadata generation task.
  2. Or disable the configuration cache when running the `clean` task (e.g., run `.\gradlew clean` separately or pass `--no-configuration-cache`).

### [High] Challenge 2: Python Receiver Crashes on Raw Binary Data (Invalid UTF-8)
- **Assumption challenged**: The Python receiver is robust when sent malformed JSON messages or raw binary data.
- **Attack scenario**: The connection handler in `receiver/receiver.py` catches `json.JSONDecodeError` to handle malformed JSON text gracefully. However, if a client sends a binary frame containing invalid UTF-8 bytes (e.g. `b'\xff\xff'`), the `json.loads(message)` call raises a `UnicodeDecodeError`. Since `UnicodeDecodeError` is not a subclass of `json.JSONDecodeError`, this exception is unhandled and propagates up, crashing the connection handler and dropping the connection.
- **Blast radius**: Any connection sending raw/binary payloads or corrupted packets will immediately crash the connection handler for that client.
- **Mitigation**: Update the exception handling in `receiver.py`'s connection handler to catch `ValueError` (which covers both `json.JSONDecodeError` and `UnicodeDecodeError`) or catch both exceptions explicitly:
  ```python
  except (json.JSONDecodeError, UnicodeDecodeError):
      print("Error: Malformed payload received", file=sys.stderr)
      continue
  ```

### [Medium] Challenge 3: Leaked / Zombie Processes on Test Setup Failures
- **Assumption challenged**: The receiver correctly closes socket connections and exits cleanly without leaving zombie processes.
- **Attack scenario**: In `tests/test_cases.py` and `tests/test_adversarial.py`, the `asyncSetUp` method spawns the Python receiver as a subprocess and then connects to it via WebSockets. If the WebSocket connection fails or times out, an exception is thrown in `asyncSetUp`. Under Python's `unittest` framework, if `setUp`/`asyncSetUp` raises an exception, the corresponding `tearDown`/`asyncTearDown` is **not executed**. As a result, the spawned receiver subprocess is never terminated and is leaked as a zombie process on the host.
- **Blast radius**: Flaky connections or setup failures will accumulate zombie python processes, leaking memory and socket handles, and potentially blocking port 8080.
- **Mitigation**: Use a `try...except` block in `asyncSetUp` to catch any initialization exceptions and terminate the spawned subprocess before propagating the exception, or use `addCleanup` which is guaranteed to run even if `setUp` fails:
  ```python
  async def asyncSetUp(self):
      self.process = await asyncio.create_subprocess_exec(...)
      self.addCleanup(self.cleanup_process)
      ...
  ```

### [Medium] Challenge 4: Stress Test Suite is Silently Excluded from Execution
- **Assumption challenged**: The test suite execution command runs all verification tests.
- **Attack scenario**: The test discovery pattern in `tests/run_tests.py` searches for `test_*.py` files. However, the stress tests file is named `stress_tests.py` (missing the `test_` prefix). As a result, both the default test runner `python tests/run_tests.py` and automatic `pytest` discovery silently ignore `stress_tests.py`. The stress tests are never executed during normal validation runs unless explicitly targeted (`pytest tests/stress_tests.py`).
- **Blast radius**: The stress and robustness tests (covering multiple connections, connection drops, and massive payloads) are completely unrun and unverified in normal CI/CD runs.
- **Mitigation**: Rename `tests/stress_tests.py` to `tests/test_stress.py` to align with the standard test discovery naming convention.

---

## Stress Test Results

- **Clean Build Stability Test** → Run `.\gradlew clean assembleDebug` 5 times consecutively → Fails on 3rd execution due to missing `app-metadata.properties` → **FAIL**
- **Malformed JSON Robustness** → Send malformed JSON string (e.g. `{"event": "mouse_move"`) → Server logs error and continues serving → **PASS**
- **Raw Binary Robustness** → Send invalid UTF-8 binary payload (`b'\xff\xff'`) → Connection handler raises `UnicodeDecodeError` and crashes → **FAIL**
- **Socket Close & Zombie Process Test** → Induce setup failure and check for leaked python processes → Multiple `receiver.py` and `run_tests.py` zombie processes left running → **FAIL**
- **Default Test Suite Discovery** → Run `run_tests.py` / `pytest` → Only 30/36 tests collected (`stress_tests.py` ignored) → **FAIL**

---

## Unchallenged Areas

- **OS-Level Emulation logic** — Not challenged because tests run in `--mock` mode, which bypasses OS-level mouse and keyboard emulation. Emulation drivers and OS permissions (e.g., Accessibility or Administrator rights under Windows) are out of scope for M1.
