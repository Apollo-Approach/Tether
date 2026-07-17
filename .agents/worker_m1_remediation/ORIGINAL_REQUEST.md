## 2026-07-15T02:38:53Z
You are the Worker for Milestone M1 Remediation. Your working directory is c:\Development\Monolith\.agents\worker_m1_remediation\.
Your task is to fix the issues discovered by the review/challenge team:

1. **Gradle Build Cache and Configuration Cache Instability**:
   - In `android/gradle.properties`, set `org.gradle.configuration-cache=false`. This prevents Gradle build failures during consecutive clean runs when configuration cache is reused.

2. **Compose Pointer Input modifier conflict**:
   - In `android/app/src/main/java/com/antigravity/remote/MainActivity.kt`, combine `detectDragGestures` and `detectTapGestures` into a single `pointerInput(Unit)` modifier. You can use a `coroutineScope { launch { detectDragGestures(...) } launch { detectTapGestures(...) } }` pattern to run them concurrently in the same pointer input scope.

3. **Receiver Robustness and Coordinate Sanitization**:
   - In `receiver/receiver.py`, ensure that in `handle_client`:
     * Catch both `json.JSONDecodeError` and `UnicodeDecodeError` when decoding messages.
     * Use `math.isfinite(dx)` and `math.isfinite(dy)` to verify that coordinates are finite numbers (not `inf`, `-inf`, or `nan`). If they are not finite, log an error to `sys.stderr` and skip the event.

4. **Test Suite Discovery**:
   - Rename `tests/stress_tests.py` to `tests/test_stress.py` so it matches the test discovery pattern `test_*.py`.

5. **Test Setup Robustness & Zombie Process Prevention**:
   - In `tests/test_cases.py`, `tests/test_adversarial.py`, and `tests/test_stress.py` (previously `stress_tests.py`):
     * Increase the startup log timeout in `asyncSetUp` from `3.0` to `5.0` seconds to avoid timeout flakiness.
     * Wrap the remaining part of `asyncSetUp` (specifically `websockets.connect`) in a `try...except` block. If `websockets.connect` fails, catch the exception, terminate and wait for the spawned `self.process` using `self.process.terminate(); await self.process.wait()`, and re-raise the exception. This ensures no zombie processes are leaked if setup fails.

Verify your changes:
- Run a clean build of the Android app using `.\gradlew clean assembleDebug` in `android/`.
- Run the test suite using `python tests/run_tests.py` in the workspace root and verify that all 36 tests pass successfully.
- Write your findings to `handoff.md` and notify the parent when done.

DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.
