# Milestone M1 Review & Adversarial Challenge Report

## Review Summary

**Verdict**: REQUEST_CHANGES

The project initialization is structurally sound, and the basic configuration and compilation flow are correct. However, critical gaps in the test runner discovery (bypassing the stress tests) and flaky subprocess timeout errors in the test setup require resolution before this milestone can be considered fully complete and robust.

---

## Findings

### [Major] Finding 1: Flaky Test Setup Subprocess Timeout
- **What**: The test setup for `TestTier1FeatureCoverage` and `TestAdversarialAndStress` frequently fails due to a `TimeoutError` when waiting for the receiver startup log.
- **Where**: `tests/test_cases.py` (line 28) and `tests/test_adversarial.py` (line 28).
- **Why**: Spawning a new Python process for every single test case is heavy and slow on Windows, often exceeding the tight 3.0-second timeout. This causes intermittent test failures under resource contention.
- **Suggestion**: Either increase the startup timeout from `3.0` to `10.0` seconds or refactor the test suite to spawn a single persistent receiver subprocess per test suite execution.

### [Major] Finding 2: Incomplete Test Suite Discovery (Bypassed Stress Tests)
- **What**: The main test runner does not run the stress tests suite.
- **Where**: `tests/run_tests.py` (line 15).
- **Why**: The discovery pattern is set to `test_*.py`. Because the stress tests file is named `stress_tests.py`, it is completely bypassed during `python tests/run_tests.py` runs, leaving 6 critical stress tests unexecuted by default.
- **Suggestion**: Rename `tests/stress_tests.py` to `tests/test_stress.py` or modify the discovery pattern in `run_tests.py` to `*test*.py`.

### [Minor] Finding 3: Unimplemented Test Cases from TEST_INFRA.md
- **What**: Multiple test cases specified in the test design docs are missing from the implementation files.
- **Where**: `TEST_INFRA.md` (Tiers 2, 3, and 4) compared against `tests/test_cases.py` and `tests/test_adversarial.py`.
- **Why**: Tests like `test_mouse_move_large_dx`, `test_null_values`, Tier 3 cross-feature interactions (drag, shift-click), and Tier 4 workflows (drawing a circle) are documented but not present in the code.
- **Suggestion**: Implement the missing test cases to match the documentation, or update the documentation to align with the current project scope.

### [Minor] Finding 4: Compose Gesture Detection Conflict Risk
- **What**: The touch area defines two separate chained `pointerInput(Unit)` modifiers.
- **Where**: `android/app/src/main/java/com/antigravity/remote/MainActivity.kt` (lines 67-87).
- **Why**: In Jetpack Compose, multiple separate gesture detectors on the same modifier can conflict, resulting in one gesture consuming events and blocking the other from executing (e.g. tap vs drag).
- **Suggestion**: Combine gesture detection (drag and tap) into a single `pointerInput` block.

---

## Verified Claims

- **compileSdk / targetSdk = 36** → verified via inspection of `android/app/build.gradle.kts` → **PASS**
- **Internet Permission declared in Manifest** → verified via inspection of `android/app/src/main/AndroidManifest.xml` → **PASS**
- **Android App independent compilation** → verified via running `.\gradlew assembleDebug` and finding the generated `app-debug.apk` → **PASS**
- **Test suite execution** → verified by running `python tests/run_tests.py` (30 tests executed) → **PASS** (but noted flakiness/timeout issues on consecutive runs).

---

## Coverage Gaps

- **Unexplored Tier 2, 3, 4 Tests** — risk level: **medium** — recommendation: investigate and implement the missing test cases to ensure complete protocol safety.
- **Excluded Stress Tests file** — risk level: **medium** — recommendation: rename `stress_tests.py` to `test_stress.py` to include it in the default test runner.

---

## Unverified Items

- *None.* All tasks and configurations have been independently executed and verified.

---
---

## Challenge Summary

**Overall risk assessment**: MEDIUM

The receiver code correctly handles JSON exceptions and invalid action types. However, it relies on client cooperation for numeric input sanity and network environment conditions.

---

## Challenges

### [Medium] Challenge 1: Absence of Coordinate Sanitization (Infinity/NaN)
- **Assumption challenged**: The values of `dx` and `dy` parsed from the JSON payload are always standard finite numbers.
- **Attack scenario**: A compromised or bug-triggered client sends `{"event": "mouse_move", "dx": Infinity, "dy": NaN}`. Python's `json.loads` parses these successfully as float representations (`math.inf` and `math.nan`), bypassing `isinstance(dx, (int, float))`.
- **Blast radius**: The values are outputted directly to stdout and will be passed to OS-level mouse emulation (e.g., PyAutoGUI), potentially crashing the event handler or triggering OS API lockups.
- **Mitigation**: Validate that input coordinates are finite numbers using `math.isfinite(dx)` and clamp them to safe operational bounds.

### [Low] Challenge 2: Port Binding Failure
- **Assumption challenged**: The receiver server always binds to port 8080 successfully.
- **Attack scenario**: A service already runs on port 8080.
- **Blast radius**: The script crashes with an unhandled `OSError` (Address already in use).
- **Mitigation**: Handle socket bind exceptions gracefully, log a clear user-facing error message, or fallback to an available port.

---

## Stress Test Results

- **Rapid request load (100 msgs in 0.1s)** → server processes all packets successfully without dropouts → **PASS**
- **Abrupt websocket connection drop** → server cleans up and is immediately ready to accept new clients → **PASS**
- **Massive message size payload (2MB)** → socket terminates connection safely due to max message size limits, server recovers and accepts next connection → **PASS**

---

## Unchallenged Areas

- **OS Emulation** — reason not challenged: The server was reviewed and tested in mock/dry-run mode (`--mock`). True OS interaction (with `pyautogui` or similar) was not tested directly against the OS.
