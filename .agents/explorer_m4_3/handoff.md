# Handoff Report: Client-Server WebSocket Integration Strategy (Milestone M4)

## 1. Observation

During the investigation of the project's WebSocket integration requirements, the following components and codebase sections were analyzed:

### A. Python Receiver Connection Lifecycle (`receiver/receiver.py`)
- **Server Initialization**: The server starts inside `main()` using the Python `websockets` library (lines 106-111):
  ```python
  async with websockets.serve(handle_client, args.host, args.port) as server:
      # Retrieve the actual listening port from the websockets server
      actual_port = server.sockets[0].getsockname()[1]
      # Print server startup log to stdout
      print(f"Server listening on ws://{args.host}:{actual_port}", flush=True)
      await asyncio.Future()  # run forever
  ```
- **Connection Handling**: Multiple connections are processed concurrently. The `handle_client` coroutine is executed in a separate asyncio Task per connection (lines 21-26):
  ```python
  async def handle_client(websocket, *args, **kwargs):
      try:
          async for message in websocket:
  ```
- **Connection Terminations & Drops**: Drops are handled via a catch-all exception block (lines 89-90):
  ```python
      except websockets.exceptions.ConnectionClosed:
          pass
  ```
  Abnormal socket terminations/closes do not crash the server; the connection's loop is exited and resources are freed, while the server continues to accept subsequent or concurrent connections.
- **Message Validation & Error Handling**:
  - Valid payloads trigger stdout logs (e.g. `[MOUSE_MOVE] dx: {dx}, dy: {dy}`, `[MOUSE_CLICK] button: {button}`).
  - Invalid JSON, missing parameters, or incorrect data types are caught and logged to `stderr` (e.g. `Error: Malformed JSON payload received`, `Error: Invalid coordinates type in mouse_move event`). They do *not* terminate the socket connection; the server continues reading the next frame on the same socket.

### B. E2E Testing Expectations (`tests/test_cases.py` & others)
- **Port Discovery**: E2E test cases launch the receiver script on an ephemeral port (`--port 0`), capturing the assigned port from stdout (lines 21-32 in `tests/test_cases.py`):
  ```python
        self.process = await asyncio.create_subprocess_exec(
            sys.executable, '-u', receiver_path, '--mock', '--port', '0',
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        # Wait for and read the startup log line to clear the buffer
        try:
            line = await asyncio.wait_for(self.process.stdout.readline(), timeout=15.0)
            line_str = line.decode('utf-8').strip()
            self.assertIn("Server listening on ws://", line_str)
            self.port = int(line_str.split(":")[-1])
  ```
- **Output-Based Assertions**:
  - Integration is validated by sending WebSocket frames to `ws://localhost:{port}` and reading `stdout` (for success logs) or `stderr` (for error logs).
- **Concurrency & Drops Robustness**:
  - `test_rapid_multiple_client_connections` in `tests/test_stress.py` spawns 5 concurrent clients sending messages to ensure multi-client stability.
  - `test_connection_drops` in `tests/test_stress.py` closes the connection transport layer abruptly to verify the server recovers immediately.
  - `test_accidental_connection_drop` in `tests/test_cases.py` opens a connection, drops it cleanly via `websocket.close()`, and reconnects to send more inputs, verifying server lifecycle permanence.
- **UTF-8 & Numeric Boundary Resilience**:
  - `test_challenge.py` tests invalid UTF-8 frames (both binary and raw TCP text frames), Infinity/NaN coordinates, and numeric overflows (e.g. `1e1000`).

### C. Android Project Setup (`android/`)
- **OkHttp Availability**: `android/app/build.gradle.kts` defines the OkHttp dependency (line 47):
  ```kotlin
  implementation(libs.okhttp)
  ```
- **Key Mappings**: `android/app/src/main/java/com/antigravity/remote/KeyMapper.kt` provides hardware key mapping to string tokens accepted by the server (e.g., `Key.Enter -> "Enter"`, `Key.Backspace -> "Backspace"`, `Key.Spacebar -> "Space"`).

---

## 2. Logic Chain

Based on these observations, we can establish the following step-by-step reasoning:

1. **Client-Server WebSocket Protocol**:
   - The Python server relies on standard WebSocket frames via `websockets.serve` without custom authentication or subprotocol negotiation.
   - Therefore, the Android app can successfully complete the handshake simply by establishing a standard WebSocket upgrade connection using the already included OkHttp library (`OkHttpClient.newWebSocket()`).
2. **Handshake Verification**:
   - Handshake success is verified when OkHttp's `WebSocketListener.onOpen` callback is invoked, which occurs immediately after the standard HTTP 101 Switching Protocols handshake response is validated.
   - Handshake failure (e.g. due to server downtime or invalid host/port mapping) is captured via `WebSocketListener.onFailure` prior to connection establishment.
3. **Emulator Networking**:
   - When running on an Android Emulator, `localhost` refers to the emulator's own local loopback. The host's loopback interface is routed through `10.0.2.2`.
   - Therefore, the Android client must target `ws://10.0.2.2:8080` (or the dynamically specified port) to successfully perform the handshake.
4. **Handling Connection Drops**:
   - Connections may drop cleanly (e.g., `onClosed` callback) or abnormally due to network interruptions (e.g., `onFailure` callback).
   - To guarantee a stable user experience, the Android application should implement an automated reconnection loop.
   - Using **exponential backoff with jitter** (e.g., starting at 1 second, doubling up to a cap of 30 seconds, plus a small random deviation) prevents overloading the server or network interface when recovering.
   - Since input events (like relative mouse coordinates) represent real-time changes, queueing mouse moves during disconnection would cause jerky/erratic cursor jumps upon reconnect. Therefore, stale mouse move events should be dropped when disconnected, whereas crucial keystroke events might be logged or cleared to avoid state mismatch.
5. **E2E Test Compatibility**:
   - Since the testing suite performs opaque-box validations on the receiver's process output, the Android app does not directly interact with the E2E Python scripts. Instead, the Android app must strictly output JSON payloads matching the contract schemas (such as clamping/formatting values, passing expected button/key strings, and handling special characters in UTF-8) to ensure that the integrated system behaves identically to the tested WebSocket client.

---

## 3. Caveats

- **Network Constraints**: The investigation was conducted in CODE_ONLY mode, meaning no external Gradle dependency checks or third-party WebSocket libraries outside of OkHttp were analyzed.
- **Physical Device Hosting**: If the Android app runs on a physical device instead of an emulator, `10.0.2.2` will not work. The device and host must be on the same local network, and the client must target the host's actual LAN IP address.
- **UI State Thread Safety**: OkHttp's listener callbacks (`onOpen`, `onClosed`, `onFailure`) run on background worker threads. Updating Jetpack Compose UI state or triggering Coroutine reconnect jobs from these callbacks must be dispatched to the Main/UI thread safely.

---

## 4. Conclusion

### Recommended Integration Strategy

1. **WebSocket Engine (Android Client)**:
   - Use OkHttp's built-in `WebSocket` API since it is already defined in `build.gradle.kts`.
   - Implement a custom `WebSocketListener` to capture lifecycle events:
     - `onOpen`: Transition state to "Connected" and update the status UI.
     - `onFailure`: Log the exception, update UI to "Disconnected", and schedule a reconnection task.
     - `onClosed` / `onClosing`: Reset connection handles and update UI.
2. **Reconnection Mechanism**:
   - Launch reconnect attempts using an asynchronous coroutine loop.
   - Employ exponential backoff with jitter (initial retry delay = 1000ms, multiplier = 2.0, max delay = 30s) to prevent request storming.
   - Provide a manual "Reconnect" button on the UI that cancels pending automatic retries and initiates a connection immediately.
3. **Queueing Strategy**:
   - **Discard** pending `mouse_move` events when the connection is down to avoid rubber-banding of the cursor.
   - **Log/Fail** pending keystrokes or button clicks rather than buffering them, which prevents delayed/accidental double-inputs when the socket reconnects.
4. **Target Address configuration**:
   - Provide a configurable target host/port in the UI (or build configuration) defaulting to `10.0.2.2:8080` for emulator convenience.

---

## 5. Verification Method

To verify the integration, the following steps should be executed:

1. **Run the E2E Test Suite**:
   - Execute the test runner from the repository root:
     ```powershell
     python tests/run_tests.py
     ```
   - This verifies that the server's connection handling, JSON parsing, error boundaries, UTF-8 resilience, and concurrent/drop robustness meet all contracts.
2. **Inspect Logs & Handshake**:
   - Start the receiver on the host manually:
     ```powershell
     python receiver/receiver.py --port 8080
     ```
   - Launch the Android app in the emulator.
   - Tap "Connect" and inspect the server terminal. Confirm the handshake by checking that the Android app's status displays "Connected" and no error traces are emitted on either the emulator logcat or the host terminal.
3. **Simulate Connection Drops**:
   - While connected, toggle the emulator's cellular/Wi-Fi connection off, or kill the host's Python server.
   - Verify that the Android app's UI immediately displays "Disconnected".
   - Restart the server (or re-enable network) and verify the app automatically reconnects and resumes functioning without needing an app restart.
