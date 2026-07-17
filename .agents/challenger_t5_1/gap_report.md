# Gap Report — WebSocket Protocol and Receiver Robustness

This report documents the discovered test coverage gaps and vulnerabilities in the WebSocket receiver (`receiver/receiver.py`) identified during the Tier 5 Adversarial Hardening phase.

---

## Discovered Gaps & Vulnerabilities

### 1. Valid JSON Non-Dict Payload Validation
- **Description**: The WebSocket receiver (`receiver/receiver.py`) checks if the parsed JSON is not a dictionary (`not isinstance(data, dict)`), prints an error, and continues. However, there were no existing tests in the suite verifying this behavior for various valid JSON non-dict structures (e.g., lists, booleans, numbers, strings, and null).
- **Impact**: Medium (Lack of test coverage for explicit validation logic).
- **Adversarial Inputs**:
  - List: `[1, 2, 3]`
  - Boolean: `true`
  - Number: `123.45`
  - String: `"hello_world"`
  - Null: `null`
- **Expected Behavior**: Receiver logs `Error: Invalid payload format, expected JSON object` to `stderr` and keeps the connection open.

### 2. Unicode Lone Surrogates Connection Crash
- **Description**: When receiving a `keyboard_input` event, the receiver reconfigures `sys.stdout` and `sys.stderr` to use UTF-8 on Windows. However, it does not customize the error handling behavior of the stream wrapper (it uses the default `strict` encoder). When a client sends a lone UTF-16 surrogate (such as a lone high surrogate `\uD83D` or low surrogate `\uDE80`), Python's default UTF-8 encoder raises a `UnicodeEncodeError` when trying to print `[KEYBOARD_INPUT] key: {key}`. Because the event processing block inside `handle_client` is not wrapped in a `try-except` structure, this unhandled exception crashes the coroutine, dropping the WebSocket connection immediately.
- **Impact**: High (Denial of Service/Connection Drop for the client session).
- **Adversarial Inputs**:
  - `{"event": "keyboard_input", "key": "\uD83D"}`
  - `{"event": "keyboard_input", "key": "\uDE80"}`
- **Expected Behavior**: The receiver should gracefully sanitize or log the error without throwing an unhandled exception that terminates the client session.

### 3. Coordinate Overflow Unhandled Exception
- **Description**: In the `mouse_move` handler, the code validates coordinates by verifying if they are integers/floats and checking `not math.isfinite(dx)`. However, in Python, if an extremely large integer (e.g., `10**310`) is passed, converting it to a float or executing `math.isfinite(dx)` raises an `OverflowError` (since double-precision floats cannot represent values exceeding ~`1.79e308`). Because there is no error handler wrapping the event processing logic, this raises an unhandled exception that terminates the connection handling coroutine and drops the WebSocket connection.
- **Impact**: High (Denial of Service/Connection Drop for the client session).
- **Adversarial Input**: `{"event": "mouse_move", "dx": 10**310, "dy": 0.0}`
- **Expected Behavior**: The receiver should catch conversion or boundary errors gracefully and log them to `stderr` rather than allowing them to bubble up and drop the connection.

### 4. Non-String Event Type Fields Validation
- **Description**: If the client sends an event payload where the `"event"` field is a non-string type (e.g., an integer, boolean, list, or dictionary), the receiver prints `"Error: Unknown event type: ..."` to `stderr`. While this is handled, the test suite had no test cases ensuring that non-string event field types do not crash the server and are correctly logged.
- **Impact**: Low (Lack of test coverage for input validation boundary).
- **Adversarial Inputs**:
  - `{"event": 123}`
  - `{"event": true}`
  - `{"event": []}`
  - `{"event": {}}`
- **Expected Behavior**: The receiver logs `Error: Unknown event type: ...` to `stderr` and remains alive.

### 5. Rapid Sequential Connections Lifecycle Stress
- **Description**: While simple connection drops were tested, there were no stress tests verifying whether the server can survive a rapid succession of connection/disconnection loops (e.g., 50 sequential connections opened and closed rapidly) without leaking file descriptors, sockets, or crashing.
- **Impact**: Medium (Potential file descriptor exhaustion or race conditions).
- **Expected Behavior**: The server handles the rapid cycle and remains fully responsive to subsequent connections.
