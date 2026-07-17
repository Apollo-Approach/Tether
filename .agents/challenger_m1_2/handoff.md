# Handoff Report - M1 Environment & Project Init Verification

## 1. Observation
- **Clean Build Failure**: The third consecutive execution of `.\gradlew clean assembleDebug` from `c:\Development\Monolith\android` failed with exit code 1:
  ```text
  * What went wrong:
  A problem was found with the configuration of task ':app:packageDebug' (type 'PackageApplication').
    - Type 'com.android.build.gradle.tasks.PackageApplication' property 'appMetadata' specifies file 'C:\Development\Monolith\android\app\build\intermediates\app_metadata\debug\writeDebugAppMetadata\app-metadata.properties' which doesn't exist.
  ```
- **Binary Data Connection Crash**: Running a custom client sending invalid UTF-8 bytes `b'\xff\xff'` to `receiver/receiver.py` resulted in the following traceback in the receiver's stderr, crashing the connection handler:
  ```text
  Traceback (most recent call last):
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\site-packages\websockets\asyncio\server.py", line 374, in conn_handler
      await self.handler(connection)
    File "C:\Development\Monolith\receiver\receiver.py", line 27, in handle_client
      data = json.loads(message)
             ^^^^^^^^^^^^^^^^^^^
    File "C:\Users\devon\AppData\Local\Programs\Python\Python312\Lib\json\__init__.py", line 341, in loads
      s = s.decode(detect_encoding(s), 'surrogatepass')
          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  UnicodeDecodeError: 'utf-8' codec can't decode byte 0xff in position 0: invalid start byte
  ```
- **Zombie Processes**: Process listings showed active zombie processes from previous test runs on the host:
  - ProcessId `18208`: `python.exe c:\Development\Monolith\tests\run_tests.py`
  - ProcessId `16844`: `python.exe -u c:\Development\Monolith\receiver\receiver.py --mock --port 0`
- **Ignored Stress Tests**: `pytest` and `run_tests.py` run 30 tests in total. However, the file `tests/stress_tests.py` contains 6 test cases which are not run. Standard discovery only runs test cases inside files matching `test_*.py`.

## 2. Logic Chain
- **Build Instability**:
  1. Running `clean` deletes the entire `build/` directory, including `build/intermediates/app_metadata/debug/writeDebugAppMetadata/app-metadata.properties` (Observation 1).
  2. Because Gradle uses configuration caching, it tracks output files and task dependencies. If the cache is reused, Gradle incorrectly skips executing `:app:writeDebugAppMetadata` because it thinks nothing has changed, unaware that `clean` deleted the output file.
  3. Consequently, `:app:packageDebug` cannot find `app-metadata.properties`, causing the build to fail.
- **Binary Data Receiver Crash**:
  1. The connection handler catches `json.JSONDecodeError` but does not catch `UnicodeDecodeError` (Observation 2).
  2. Sending raw binary bytes that cannot be decoded as UTF-8 causes `json.loads()` to raise `UnicodeDecodeError`.
  3. Since this exception is unhandled, it propagates and terminates the async connection handler, dropping the WebSocket client connection.
- **Zombie Processes**:
  1. `tests/test_cases.py` and `tests/test_adversarial.py` spawn `receiver.py` in `asyncSetUp`.
  2. If an exception occurs in `asyncSetUp` (such as a timeout or websocket connection error) before the test runs, the `asyncTearDown` method is not executed by `unittest`.
  3. Therefore, the spawned `receiver.py` process is never terminated, leading to zombie processes (Observation 3).
- **Ignored Stress Tests**:
  1. `tests/run_tests.py` discovers tests matching `test_*.py`.
  2. The file is named `stress_tests.py` (no `test_` prefix).
  3. Therefore, `stress_tests.py` is bypassed entirely during standard discovery (Observation 4).

## 3. Caveats
- No caveats. All findings were verified directly by executing local commands and custom test harnesses in the exact target environment.

## 4. Conclusion
- The build system is unstable when running consecutive clean builds with configuration caching.
- The Python receiver is vulnerable to connection crashes when sent raw binary (non-UTF-8) payloads.
- The test harness leaks subprocesses as zombie processes whenever `asyncSetUp` fails.
- The stress test suite is silently ignored by standard test runner discovery due to filename naming convention mismatch.

## 5. Verification Method
- **To verify clean build failures**: Run `.\gradlew clean assembleDebug` three times in `c:\Development\Monolith\android`. Inspect the third run output for `:app:packageDebug` failure.
- **To verify binary crash**: Run `python .agents/challenger_m1_2/test_robustness.py`. Observe the traceback and connection crash.
- **To verify skipped tests**: Run `python tests/run_tests.py` and observe that only 30 tests are run (excluding the 6 tests in `stress_tests.py`).
