# Verification Plan - M1 Environment & Project Init

This plan outlines the empirical tests to verify the project's build stability and the robustness of the Python receiver.

## Step 1: Clean Build Verification
- Run `.\gradlew clean assembleDebug` from `c:\Development\Monolith\android`.
- Run it 3 consecutive times to ensure consistency.
- Verify that every execution returns a success code (exit code 0) and builds the application.

## Step 2: Python Receiver Robustness to Malformed JSON & Raw Binary Data
- Run Python receiver with `--mock` and `--port 0` (or fixed port) as a subprocess.
- Connect a raw WebSocket client.
- Send malformed JSON (e.g. `{"event": "mouse_move"`, `""`, `[]`, `{"event": null}`).
- Send raw binary data (e.g., non-UTF-8 bytes, high-bit binary packets).
- Verify that the receiver logs the errors to stderr, does not crash, and continues to process subsequent valid JSON messages.
- Write a dedicated Python verification script to perform this and output results.

## Step 3: Connection Closing & Zombie Process Prevention
- Start the Python receiver server.
- Connect multiple clients, send some data, and disconnect them (both gracefully and abruptly).
- Verify that the receiver process correctly releases port 8080 (or any dynamically allocated port) and closes socket connections.
- Terminate the receiver process and verify that it exits cleanly.
- Verify using process listings (`tasklist` or PowerShell `Get-Process`) that no zombie Python processes running `receiver.py` remain.

## Step 4: Run Existing Test Suite
- Run `python tests/run_tests.py` and inspect test outputs and exit status.
- Run `pytest tests` to check if all tests pass.
- Log test execution results.
