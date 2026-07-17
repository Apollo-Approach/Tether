# Handoff Report - Tier 1 Feature Coverage (Milestone SM2)

## 1. Observation
We observed the following from the codebase configuration and documentation files:

*   **`PROJECT.md` (Lines 26-60)** defines the JSON WebSocket payload contracts for the three event types:
    *   **mouse_move**:
        ```json
        {
          "event": "mouse_move",
          "dx": 15.5,
          "dy": -10.2
        }
        ```
    *   **mouse_click**:
        ```json
        {
          "event": "mouse_click",
          "button": "left" | "right" | "middle"
        }
        ```
    *   **keyboard_input**:
        ```json
        {
          "event": "keyboard_input",
          "key": "a" | "Enter" | "Backspace" | "Shift"
        }
        ```
*   **`TEST_INFRA.md` (Lines 45-87)** outlines the requirements for subprocess execution, connection retry loops, and the list of Tier 1 (Feature Coverage) happy paths:
    *   Subprocess command line: `sys.executable -u receiver/receiver.py --mock` (where `-u` guarantees unbuffered stdout).
    *   WebSocket retry loop: attempt connecting to `ws://localhost:8080` with `0.1s` delay up to `3.0s` (30 attempts).
    *   A set of 15 happy-path test cases covering move, click, and keyboard events.
*   **`tests/test_cases.py` (Lines 1-7)** currently contains only a dummy sanity test:
    ```python
    class TestE2ESanity(unittest.IsolatedAsyncioTestCase):
        async def test_setup_sanity(self):
            """A dummy test case that passes immediately to verify the test discovery works."""
            self.assertTrue(True)
    ```
*   **`tests/run_tests.py` (Lines 11-21)** discovers and runs tests matching `test_*.py` using standard `unittest`.
*   The `receiver/` directory does not currently exist.

---

## 2. Logic Chain
1. **JSON Payload Specification**: Based on `PROJECT.md` contracts, we define the exact payloads for each of the test cases to verify both standard and edge happy paths (e.g. negative numbers, high-precision floats, uppercase and special keys).
2. **Feature Coverage Requirement**: The user requests at least 5 tests per feature (mouse_move, mouse_click, keyboard_input), totaling at least 15 tests.
    *   `mouse_move` has 5 distinct happy path cases: positive floats, negative floats, integers, zeros, and high-precision floats.
    *   `mouse_click` in the original `TEST_INFRA.md` only had 3 cases (`left`, `right`, `middle`). To meet the 5-test minimum, we add `test_mouse_click_sequential` (verifying multiple clicks are processed over the same connection) and `test_mouse_click_repeated_left` (verifying repeated single clicks).
    *   `keyboard_input` has 7 distinct happy path cases covering lowercase, uppercase, special keys, backspace, spaces, modifiers (Shift), and numbers.
    *   Total tests: 5 (move) + 5 (click) + 7 (keyboard) = 17 tests (exceeding the 15-test requirement).
3. **Stdout Log Format Design**: To ensure the tests can execute as black-box E2E assertions, the receiver script must write easily parsable logs to stdout when it successfully handles events. We recommend:
    *   `[EVENT] mouse_move: dx={dx}, dy={dy}`
    *   `[EVENT] mouse_click: button={button}`
    *   `[EVENT] keyboard_input: key={key}`
    *   On startup: `[RECEIVER] Running in mock mode (no OS-level emulation)` and `[RECEIVER] WebSocket server starting on ws://localhost:8080`.
4. **Mock Receiver Design**: The receiver must be an async script using `asyncio` and `websockets`. It parses the `--mock` flag using `argparse`, prints startup headers, launches a websocket server on port 8080, reads messages, decodes the JSON, and logs the events to stdout in the exact format defined above.
5. **E2E Test Case Design (`test_cases.py`)**:
    *   `asyncSetUp` will spawn the receiver in a subprocess using `asyncio.create_subprocess_exec` and wait for the WebSocket port to be open.
    *   An helper method `assert_stdout_contains` reads line-by-line from the process's stdout pipe with a timeout (using `asyncio.wait_for`) to search for the expected log. This prevents the tests from blocking indefinitely.
    *   `asyncTearDown` closes the websocket client and terminates the receiver subprocess.

---

## 3. Caveats
*   **Emulation vs Mock**: In `--mock` mode, the receiver only logs events to stdout. Emulation libraries (e.g. `pyautogui` or `pynput`) are not required yet.
*   **Dependency Management**: Before running, `websockets>=14.2` must be installed on the environment (`pip install -r tests/requirements.txt`).
*   **Subprocess Cleanup**: Port binding issues can occur if a previous receiver process does not terminate. The `asyncTearDown` handles this robustly with a timeout and a fallback `kill()` call.
*   **Windows Stdout Line Endings**: Subprocess stdout in Windows contains `\r\n`, which is cleaned up by decoding with UTF-8 and calling `.strip()`.

---

## 4. Conclusion
We recommend implementing the following components to achieve E2E test coverage for Milestone SM2:

### A. Recommended Structure for `receiver/receiver.py`
This script should start a WebSocket server and print event logs to stdout.

```python
import asyncio
import json
import argparse
import sys
import websockets

async def handle_connection(websocket):
    """Handles incoming WebSocket connections and parses JSON events."""
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                event = data.get("event")
                
                if event == "mouse_move":
                    dx = float(data.get("dx", 0.0))
                    dy = float(data.get("dy", 0.0))
                    print(f"[EVENT] mouse_move: dx={dx}, dy={dy}")
                    
                elif event == "mouse_click":
                    button = data.get("button")
                    print(f"[EVENT] mouse_click: button={button}")
                    
                elif event == "keyboard_input":
                    key = data.get("key")
                    print(f"[EVENT] keyboard_input: key={key}")
                    
                else:
                    print(f"[ERROR] Unknown event type: {event}")
            except (json.JSONDecodeError, TypeError, ValueError) as e:
                print(f"[ERROR] Failed to process message: {e}")
    except websockets.exceptions.ConnectionClosed:
        pass

async def main():
    parser = argparse.ArgumentParser(description="Antigravity Remote Control WebSocket Receiver")
    parser.add_argument("--mock", action="store_true", help="Enable mock mode (no OS-level emulation)")
    args = parser.parse_args()

    if args.mock:
        print("[RECEIVER] Running in mock mode (no OS-level emulation)")
    else:
        print("[RECEIVER] Running in active mode (OS-level emulation enabled)")
        
    print("[RECEIVER] WebSocket server starting on ws://localhost:8080")
    
    # Bind to localhost on port 8080
    async with websockets.serve(handle_connection, "localhost", 8080):
        await asyncio.Future()  # Run forever until terminated

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("[RECEIVER] Server stopped by user")
```

### B. Precise JSON Payload and Expected Log Formats (Tier 1 Features)
The table below specifies the 17 E2E tests, their exact payloads, and expected stdout log lines:

| # | Test Case Name | Event Type | JSON Payload | Expected Receiver Log |
|---|----------------|------------|--------------|-----------------------|
| 1 | `test_mouse_move_positive` | `mouse_move` | `{"event": "mouse_move", "dx": 5.5, "dy": 10.2}` | `[EVENT] mouse_move: dx=5.5, dy=10.2` |
| 2 | `test_mouse_move_negative` | `mouse_move` | `{"event": "mouse_move", "dx": -12.4, "dy": -8.1}` | `[EVENT] mouse_move: dx=-12.4, dy=-8.1` |
| 3 | `test_mouse_move_integers` | `mouse_move` | `{"event": "mouse_move", "dx": 10, "dy": 20}` | `[EVENT] mouse_move: dx=10.0, dy=20.0` |
| 4 | `test_mouse_move_zero` | `mouse_move` | `{"event": "mouse_move", "dx": 0.0, "dy": 0.0}` | `[EVENT] mouse_move: dx=0.0, dy=0.0` |
| 5 | `test_mouse_move_precision` | `mouse_move` | `{"event": "mouse_move", "dx": 1.2345, "dy": -5.6789}` | `[EVENT] mouse_move: dx=1.2345, dy=-5.6789` |
| 6 | `test_mouse_click_left` | `mouse_click` | `{"event": "mouse_click", "button": "left"}` | `[EVENT] mouse_click: button=left` |
| 7 | `test_mouse_click_right` | `mouse_click` | `{"event": "mouse_click", "button": "right"}` | `[EVENT] mouse_click: button=right` |
| 8 | `test_mouse_click_middle` | `mouse_click` | `{"event": "mouse_click", "button": "middle"}` | `[EVENT] mouse_click: button=middle` |
| 9 | `test_mouse_click_sequential` | `mouse_click` | Send left, then right clicks | `[EVENT] mouse_click: button=left`<br>`[EVENT] mouse_click: button=right` |
| 10 | `test_mouse_click_repeated` | `mouse_click` | Send left click 3 times | `[EVENT] mouse_click: button=left` (x3) |
| 11 | `test_keyboard_single_char` | `keyboard_input` | `{"event": "keyboard_input", "key": "a"}` | `[EVENT] keyboard_input: key=a` |
| 12 | `test_keyboard_uppercase` | `keyboard_input` | `{"event": "keyboard_input", "key": "Z"}` | `[EVENT] keyboard_input: key=Z` |
| 13 | `test_keyboard_enter` | `keyboard_input` | `{"event": "keyboard_input", "key": "Enter"}` | `[EVENT] keyboard_input: key=Enter` |
| 14 | `test_keyboard_backspace` | `keyboard_input` | `{"event": "keyboard_input", "key": "Backspace"}` | `[EVENT] keyboard_input: key=Backspace` |
| 15 | `test_keyboard_space` | `keyboard_input` | `{"event": "keyboard_input", "key": "Space"}` | `[EVENT] keyboard_input: key=Space` |
| 16 | `test_keyboard_shift` | `keyboard_input` | `{"event": "keyboard_input", "key": "Shift"}` | `[EVENT] keyboard_input: key=Shift` |
| 17 | `test_keyboard_number` | `keyboard_input` | `{"event": "keyboard_input", "key": "1"}` | `[EVENT] keyboard_input: key=1` |

### C. Recommended Code for `tests/test_cases.py`
This class replaces the dummy sanity test with full E2E coverage.

```python
import unittest
import asyncio
import sys
import os
import json
import websockets

class TestTier1FeatureCoverage(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        """Start the receiver subprocess and wait for the WebSocket server to be ready."""
        project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        receiver_script = os.path.join(project_root, "receiver", "receiver.py")
        
        # Spawn receiver script in unbuffered mock mode
        self.process = await asyncio.create_subprocess_exec(
            sys.executable, "-u", receiver_script, "--mock",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        # Connection retry loop
        self.websocket = None
        retries = 30
        delay = 0.1
        for i in range(retries):
            try:
                self.websocket = await websockets.connect("ws://localhost:8080")
                break
            except (ConnectionRefusedError, OSError):
                if i == retries - 1:
                    self.process.terminate()
                    await self.process.wait()
                    raise ConnectionError("Failed to connect to receiver server after 3.0 seconds")
                await asyncio.sleep(delay)

    async def asyncTearDown(self):
        """Close WebSocket client connection and terminate receiver process."""
        if self.websocket:
            await self.websocket.close()
        
        if self.process:
            try:
                self.process.terminate()
                await asyncio.wait_for(self.process.wait(), timeout=2.0)
            except asyncio.TimeoutError:
                self.process.kill()
                await self.process.wait()

    async def assert_stdout_contains(self, expected_log, timeout=2.0):
        """Read stdout lines from the subprocess and assert that expected_log is in one of them."""
        try:
            while True:
                line_bytes = await asyncio.wait_for(self.process.stdout.readline(), timeout=timeout)
                if not line_bytes:
                    self.fail(f"Subprocess stdout closed. Expected log: '{expected_log}'")
                line = line_bytes.decode('utf-8').strip()
                if expected_log in line:
                    return
        except asyncio.TimeoutError:
            self.fail(f"Timeout waiting for log: '{expected_log}'")

    # --- Mouse Move Tests (5 tests) ---

    async def test_mouse_move_positive(self):
        """Send relative mouse movement with positive coordinates."""
        payload = {"event": "mouse_move", "dx": 5.5, "dy": 10.2}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] mouse_move: dx=5.5, dy=10.2")

    async def test_mouse_move_negative(self):
        """Send relative mouse movement with negative coordinates."""
        payload = {"event": "mouse_move", "dx": -12.4, "dy": -8.1}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] mouse_move: dx=-12.4, dy=-8.1")

    async def test_mouse_move_integers(self):
        """Send relative mouse movement with integer coordinates."""
        payload = {"event": "mouse_move", "dx": 10, "dy": 20}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] mouse_move: dx=10.0, dy=20.0")

    async def test_mouse_move_zero(self):
        """Send relative mouse movement with zero coordinates."""
        payload = {"event": "mouse_move", "dx": 0.0, "dy": 0.0}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] mouse_move: dx=0.0, dy=0.0")

    async def test_mouse_move_precision(self):
        """Send relative mouse movement with high precision float coordinates."""
        payload = {"event": "mouse_move", "dx": 1.2345, "dy": -5.6789}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] mouse_move: dx=1.2345, dy=-5.6789")

    # --- Mouse Click Tests (5 tests) ---

    async def test_mouse_click_left(self):
        """Send mouse click with left button."""
        payload = {"event": "mouse_click", "button": "left"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] mouse_click: button=left")

    async def test_mouse_click_right(self):
        """Send mouse click with right button."""
        payload = {"event": "mouse_click", "button": "right"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] mouse_click: button=right")

    async def test_mouse_click_middle(self):
        """Send mouse click with middle button."""
        payload = {"event": "mouse_click", "button": "middle"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] mouse_click: button=middle")

    async def test_mouse_click_sequential(self):
        """Send a left click followed by a right click sequentially."""
        payload_left = {"event": "mouse_click", "button": "left"}
        payload_right = {"event": "mouse_click", "button": "right"}
        await self.websocket.send(json.dumps(payload_left))
        await self.assert_stdout_contains("[EVENT] mouse_click: button=left")
        await self.websocket.send(json.dumps(payload_right))
        await self.assert_stdout_contains("[EVENT] mouse_click: button=right")

    async def test_mouse_click_repeated_left(self):
        """Send three consecutive left clicks."""
        payload = {"event": "mouse_click", "button": "left"}
        for _ in range(3):
            await self.websocket.send(json.dumps(payload))
            await self.assert_stdout_contains("[EVENT] mouse_click: button=left")

    # --- Keyboard Input Tests (7 tests) ---

    async def test_keyboard_single_char(self):
        """Send a single lowercase character key."""
        payload = {"event": "keyboard_input", "key": "a"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] keyboard_input: key=a")

    async def test_keyboard_uppercase_char(self):
        """Send a single uppercase character key."""
        payload = {"event": "keyboard_input", "key": "Z"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] keyboard_input: key=Z")

    async def test_keyboard_enter(self):
        """Send Enter key."""
        payload = {"event": "keyboard_input", "key": "Enter"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] keyboard_input: key=Enter")

    async def test_keyboard_backspace(self):
        """Send Backspace key."""
        payload = {"event": "keyboard_input", "key": "Backspace"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] keyboard_input: key=Backspace")

    async def test_keyboard_space(self):
        """Send Space key."""
        payload = {"event": "keyboard_input", "key": "Space"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] keyboard_input: key=Space")

    async def test_keyboard_shift(self):
        """Send Shift modifier key."""
        payload = {"event": "keyboard_input", "key": "Shift"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] keyboard_input: key=Shift")

    async def test_keyboard_number(self):
        """Send a number key."""
        payload = {"event": "keyboard_input", "key": "1"}
        await self.websocket.send(json.dumps(payload))
        await self.assert_stdout_contains("[EVENT] keyboard_input: key=1")
```

---

## 5. Verification Method
1. **Prerequisite**: Install `websockets` dependency:
   ```bash
   pip install -r tests/requirements.txt
   ```
2. **Implementation Execution**: Implement `receiver/receiver.py` and overwrite `tests/test_cases.py` with the structures above.
3. **Execution Command**:
   ```bash
   python tests/run_tests.py
   ```
   Or using pytest:
   ```bash
   pytest tests/
   ```
4. **Validation Criteria**:
   *   17 test cases must be discovered and run.
   *   All 17 tests must pass successfully (exit code 0).
   *   The receiver process must spawn and terminate cleanly without leaving orphaned server processes.
5. **Invalidation Conditions**:
   *   If the receiver process exits with a non-zero code during tests.
   *   If the test client fails to connect or logs a timeout.
   *   If the stdout formatting on the receiver does not exactly match the assertions.
