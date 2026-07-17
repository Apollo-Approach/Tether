# Handoff Report — SM1: Test Infra & Design

## 1. Observation
- **Workspace Layout**: The workspace `c:\Development\Monolith` is currently clean, containing only `PROJECT.md` at the root and `.agents/` directories. No source code or tests exist yet.
- **Communication Protocol**: As specified in `PROJECT.md` (lines 27-60):
  - WebSocket JSON messages are used to send trackpad/keyboard inputs from the Android client to the Windows receiver server.
  - Event `mouse_move` requires payload: `{"event": "mouse_move", "dx": float, "dy": float}`
  - Event `mouse_click` requires payload: `{"event": "mouse_click", "button": "left" | "right" | "middle"}`
  - Event `keyboard_input` requires payload: `{"event": "keyboard_input", "key": string}`
- **System Environment**: Running a `pip list` query on the developer's Windows environment (via task ID `8e4af94a-e529-40eb-9cfc-4840a4293ae1/task-41`) confirmed the following packages are pre-installed:
  - `websockets` (version 14.2)
  - `websocket-client` (version 1.9.0)
  - `pywin32` (version 311)
  - `pytest` (version 9.0.3)
  - Python standard library is present (including `asyncio`, `unittest`, `subprocess`, `json`, `sys`, and `socket`).

## 2. Logic Chain
- **WebSocket Protocol Asynchrony**: Because the communication between client and server uses WebSockets, both the server (`receiver.py`) and the testing client are inherently asynchronous. 
- **Async Test Framework**: Standard Python `unittest` (specifically `unittest.IsolatedAsyncioTestCase` since Python 3.8) provides out-of-the-box support for async test cases (`async def test_...`). This allows cleanly establishing WebSocket connections and transmitting payloads without complex threading.
- **Subprocess Execution Lifecycle**: In E2E tests, the test runner must manage the life of `receiver/receiver.py` by launching it before tests start and terminating it afterward. Using `asyncio.create_subprocess_exec` is superior to synchronous `subprocess.Popen` because:
  1. It integrates directly with the `asyncio` event loop.
  2. It allows non-blocking reading of the receiver's `stdout` and `stderr` streams.
- **Output Verification Mechanism**: To verify that the receiver correctly processes payloads without letting inputs leak into the host OS (which would disrupt the developer's mouse/keyboard and lead to unstable tests), the receiver must support a `--dry-run` flag. In dry-run mode, the receiver validates and logs all incoming events but bypasses calling Windows APIs (via `pywin32`).
- **Structured Log Contract**: The test runner can redirect the receiver subprocess's stdout to a pipe, capture the output lines asynchronously, and push them to a thread-safe / task-safe queue. The test cases can then assert on the exact log statements printed by the receiver.

## 3. Caveats
- **Windows API Execution**: We assume that when dry-run is disabled, the receiver uses `pywin32`'s `win32api.mouse_event` and `win32api.keybd_event` successfully. Because we are in read-only mode and the receiver code is not yet written, we have not executed real mouse movement commands.
- **Port Conflict**: We assume port `8080` is available. To handle port collisions on build servers, the receiver script and the test runner should support a `--port` parameter (defaulting to 8080) and fallback port checks.

## 4. Conclusion
- We recommend implementing the E2E test runner using Python's standard `unittest.IsolatedAsyncioTestCase` along with the external `websockets` library.
- The test runner will spawn `receiver/receiver.py` using `asyncio.create_subprocess_exec`, collect stdout logs via an async queue, and terminate the subprocess in `asyncTearDown`.
- We have prepared a complete draft for `TEST_INFRA.md` below to guide the implementation work.

## 5. Verification Method
1. Create the `tests/` directory as described in the draft structure.
2. Place a mock receiver at `receiver/receiver.py` that starts a websocket server and logs requests.
3. Run the test suite using `python tests/run_tests.py` and confirm all tests pass.
4. Verify that running `python tests/run_tests.py` starts the receiver subprocess, runs the tests, and closes the receiver subprocess cleanly.

---

# DRAFT: TEST_INFRA.md

# Antigravity Remote Control - E2E Testing Infrastructure

This document details the architecture, design, and execution instructions for the opaque-box E2E testing suite targeting the Antigravity Remote Control receiver.

## 1. Test Architecture

The E2E test suite simulates the Android app's UI inputs by acting as a WebSocket client, connecting to the receiver server, and sending simulated control payloads.

```
+------------------+                   +----------------------+
|  E2E Test Runner |                   |   Receiver Server    |
| (run_tests.py)   |                   |  (receiver/receiver) |
|                  |                   |                      |
|  +------------+  |  Starts/Stops     |  +----------------+  |
|  | Subprocess |=====================>|  | WebSocket      |  |
|  | Manager    |  |                   |  | Server (8080)  |  |
|  +------------+  |                   |  +----------------+  |
|                  |                   |          |           |
|  +------------+  |   Sends JSON      |          |           |
|  | WebSocket  |----------------------+          | Decodes   |
|  | Client     |  | (ws://localhost)             | & Logs    |
|  +------------+  |                              v           |
|                  |                   +----------------------+
|  +------------+  |  Reads Stdout     |                      |
|  | Stdout     |<=====================|  [RECEIVER] Logs     |
|  | Reader     |  |                   |                      |
|  +------------+  |                   +----------------------+
+------------------+
```

### Components
1. **Subprocess Manager**: Launches the receiver server (`receiver/receiver.py`) as a background process with a `--dry-run` flag to prevent inputs from leaking to the host OS.
2. **WebSocket Client**: A connection pool managed inside tests using the `websockets` library.
3. **Stdout Reader**: An asynchronous buffer that captures the receiver's log statements line-by-line and pushes them to an event queue.
4. **Test Runner**: A suite written using Python's standard `unittest` and `asyncio` modules, specifically inheriting from `unittest.IsolatedAsyncioTestCase`.

---

## 2. Directory Structure

The E2E test files are organized in the `/tests` folder as follows:

```
/tests
├── run_tests.py       # Test entry point and test runner configuration
├── test_utils.py      # Base test cases and subprocess managers
└── test_cases.py      # Tier 1 to Tier 4 test case definitions
```

---

## 3. Python Libraries and Dependencies

The E2E tests and helper scripts require the following libraries:
- **Standard Library**:
  - `asyncio`: For non-blocking subprocess reading and asynchronous socket event loops.
  - `unittest`: Core test structuring and assertions.
  - `json`: Parsing/serialization of control messages.
  - `sys`, `os`: Running Python environment targets.
  - `socket`: Verifying port availability.
- **External Libraries**:
  - `websockets` (version 14.2): Native asyncio WebSocket client and server framework.

---

## 4. Subprocess & Logging Contract

To allow the E2E tests to run safely on local machines and CI/CD pipelines, the receiver must adhere to the following contracts:

### Port & Execution parameters:
- The receiver must accept `--port <int>` (default `8080`).
- The receiver must accept `--dry-run` (logs execution commands but does NOT call Windows APIs).

### Logging format (Stdout):
The receiver must log events to standard output in the following format:
- Successful mouse move: `[RECEIVER] mouse_move: dx=<float>, dy=<float>`
- Successful mouse click: `[RECEIVER] mouse_click: button=<button>`
- Successful keyboard input: `[RECEIVER] keyboard_input: key=<key>`
- Startup confirmation: `[RECEIVER] Server started on port <port>`
- Validation errors: `[RECEIVER_ERROR] <error_message>`

---

## 5. E2E Test Runner Implementation Outline

### `tests/test_utils.py`
```python
import asyncio
import sys
import socket
import websockets

class ReceiverSubprocess:
    """Manages the life cycle of the receiver subprocess and reads stdout logs."""
    def __init__(self, port=8080):
        self.port = port
        self.proc = None
        self.log_queue = asyncio.Queue()
        self._read_task = None

    async def start(self):
        # Verify port is free
        if self._is_port_in_use():
            raise RuntimeError(f"Port {self.port} is already in use!")

        # Launch the receiver in dry-run mode
        self.proc = await asyncio.create_subprocess_exec(
            sys.executable, "receiver/receiver.py",
            "--port", str(self.port),
            "--dry-run",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        self._read_task = asyncio.create_task(self._read_stdout())
        await self._wait_for_startup()

    def _is_port_in_use(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            return s.connect_ex(("127.0.0.1", self.port)) == 0

    async def _read_stdout(self):
        try:
            while self.proc and self.proc.returncode is None:
                line = await self.proc.stdout.readline()
                if not line:
                    break
                await self.log_queue.put(line.decode("utf-8").strip())
        except asyncio.CancelledError:
            pass

    async def _wait_for_startup(self, timeout=3.0):
        start_time = asyncio.get_event_loop().time()
        while asyncio.get_event_loop().time() - start_time < timeout:
            try:
                line = await asyncio.wait_for(self.log_queue.get(), timeout=0.1)
                if "Server started" in line:
                    return
            except asyncio.TimeoutError:
                continue
        raise RuntimeError("Receiver failed to start within timeout.")

    async def get_next_log(self, timeout=2.0):
        try:
            return await asyncio.wait_for(self.log_queue.get(), timeout=timeout)
        except asyncio.TimeoutError:
            raise AssertionError("Timeout waiting for receiver stdout log.")

    async def stop(self):
        if self._read_task:
            self._read_task.cancel()
        if self.proc:
            try:
                self.proc.terminate()
                await self.proc.wait()
            except Exception:
                self.proc.kill()
                await self.proc.wait()
```

### `tests/run_tests.py`
```python
import unittest
import sys

def main():
    # Discover and run tests
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir="tests", pattern="test_*.py")
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)

if __name__ == "__main__":
    main()
```

---

## 6. Test Tiers (Tiers 1-4)

The test cases are grouped into four distinct tiers in `tests/test_cases.py`:

### Tier 1: Feature Coverage (>=15 tests)
Verifies that individual features work correctly under normal conditions.
- **Mouse Movement**: 
  - Float coordinates: `{"event": "mouse_move", "dx": 12.5, "dy": -4.2}`
  - Negative values: `{"event": "mouse_move", "dx": -100.0, "dy": -50.0}`
  - Zero movement: `{"event": "mouse_move", "dx": 0.0, "dy": 0.0}`
  - Large movement: `{"event": "mouse_move", "dx": 1920.0, "dy": 1080.0}`
- **Mouse Clicks**:
  - Left click: `{"event": "mouse_click", "button": "left"}`
  - Right click: `{"event": "mouse_click", "button": "right"}`
  - Middle click: `{"event": "mouse_click", "button": "middle"}`
- **Keyboard Input**:
  - Letters: `{"event": "keyboard_input", "key": "a"}`
  - Special controls: `{"event": "keyboard_input", "key": "Enter"}`
  - Modifiers: `{"event": "keyboard_input", "key": "Shift"}`
  - Numbers: `{"event": "keyboard_input", "key": "7"}`

### Tier 2: Boundary & Corner Cases (>=15 tests)
Verifies error boundaries, malformed JSON, and unexpected types.
- **Invalid Events**: `{"event": "invalid_type"}`
- **Missing Fields**: `{"event": "mouse_move", "dx": 10.0}` (missing `dy`)
- **String Types for Floats**: `{"event": "mouse_move", "dx": "10.0", "dy": "2.0"}`
- **Invalid Button Strings**: `{"event": "mouse_click", "button": "double_click"}`
- **Extreme Coordinates**: Underflow / Overflow checks.
- **Empty Key Input**: `{"event": "keyboard_input", "key": ""}`
- **Invalid JSON structures**: Sending raw string, empty brackets `{}`.

### Tier 3: Cross-Feature Combinations
Tests pairwise interactions and multiple inputs sent in sequence.
- **Mouse Drag (Press and Move)**:
  - Sequence: Click Left Down -> Mouse Move -> Click Left Up.
- **Shift + Click**:
  - Sequence: Keyboard input Shift -> Mouse Click Left -> Release Shift.
- **Type and Enter**:
  - Sequence: Keyboard input `H` -> `e` -> `l` -> `l` -> `o` -> `Enter`.

### Tier 4: Real-World Workloads (>=5 tests)
Validates mock behaviors representing complete user interactions.
- **Drawing a Shape**: Simulates drawing a square by sending four sequential move commands with clicks.
- **Typing a Paragraph**: Simulates typing a word, using Backspace to delete a letter, then sending Enter.

---

## 7. How to Execute

To run the full E2E test suite locally:
```bash
python tests/run_tests.py
```
Or, alternatively, run via `pytest` for more detailed reporting:
```bash
pytest tests/
```
