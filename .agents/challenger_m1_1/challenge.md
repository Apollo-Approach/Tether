# Adversarial Challenge Report: Environment & Project Init (Milestone M1)

## Challenge Summary

**Overall risk assessment**: LOW

All target criteria for Milestone M1 initialization are met: the Android Jetpack Compose app successfully compiles and runs on the Android 16 (API 36) emulator; the package name matches `com.antigravity.remote`; the target SDK is verified as API 36; the WebSocket receiver handles connection drops and rapid concurrent clients robustly; and the complete test suite (including stress tests) passes. 

However, we identified a medium-level risk regarding **test runner flakiness** under system resource constraints.

---

## Challenges

### [Medium] Challenge 1: Subprocess Startup Timeout Flakiness in Test Suite
- **Assumption challenged**: The test runner assumes that spawning a Python subprocess and reading the startup log will always take under 3.0 seconds.
- **Attack scenario**: When running the complete test suite sequentially (30 tests in `test_cases.py` and `test_adversarial.py`), the system spawns 30 distinct Python receiver subprocesses. If the host machine is under load (e.g., compile tasks running, emulator booting, VM CPU throttling), spawning a subprocess can exceed 3.0 seconds, causing a setup failure.
- **Blast radius**: The test suite fails in `asyncSetUp` with a `RuntimeError: Failed to read server startup log in time`, even though the implementation code is correct. We empirically observed this failure on `test_mouse_move_precision` during parallel resource activity, which immediately passed upon re-run.
- **Mitigation**: Increase the startup timeout in `asyncSetUp` from `3.0` to `8.0` seconds to accommodate slower environments, or reuse a single server process across tests where complete isolation is not required.

### [Low] Challenge 2: Android Compilation Warnings
- **Assumption challenged**: The app compiles without warnings.
- **Attack scenario**: A clean build outputs two distinct warnings:
  1. `:app:processDebugResources`: `Warning: SDK processing. This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered.`
  2. `:app:stripDebugDebugSymbols`: `Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so. Run with --info option to learn more.`
- **Blast radius**: Increased APK size due to unstripped native symbol files, and potential future build tooling incompatibilities.
- **Mitigation**: Ensure Android Studio / command-line build tools match the Android SDK version 36 package. Explicitly configure packaging rules or strip tools in `build.gradle.kts` if needed.

---

## Stress Test Results

- **Multiple Concurrent Client Connections** → Spawns 5 concurrent WebSocket connections sending keyboard events. → Server log shows all 5 client payloads processed without crashing. → **PASS**
- **Abrupt Connection Drops** → Abruptly drops active WebSocket writer, then reconnects a new client. → Server detects drop via `ConnectionClosed` exception, terminates client task gracefully, and accepts new client. → **PASS**
- **Malformed JSON Streams** → Sends raw non-JSON text, unclosed braces, and empty strings. → Server logs parser error to stderr and continues listening on current connection. → **PASS**
- **Payload Data Type Validation** → Sends invalid types (e.g. coordinates as strings, buttons as integers, nested dictionaries as key). → Server detects type violations, logs errors to stderr, and ignores invalid events without crashing. → **PASS**
- **Massive Payload Size** → Sends a 2MB keyboard input message. → Connection is closed due to websocket library limits, but server recovers immediately and processes subsequent client connections. → **PASS**

---

## Unchallenged Areas

- **OS-level Emulation Side Effects**: The receiver server was tested in `--mock` (dry-run) mode to avoid side effects on the host. The actual execution of OS-level inputs (e.g. mouse cursor movements via PyAutoGUI or similar) has not been stress-tested for concurrency or safety on the OS level.
