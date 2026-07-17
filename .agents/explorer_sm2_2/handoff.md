# Handoff Report — SM2 Tier 1 Feature Coverage

## 1. Observation
We observed the following files and directories in the `Monolith` workspace:

- **`c:\Development\Monolith\PROJECT.md`** (Lines 26-60):
  ```markdown
  ## Interface Contracts
  ### Android Client ↔ Receiver Server (WebSocket JSON Protocol)
  All messages are JSON objects sent over a WebSocket connection.
  
  #### 1. Trackpad Move Event
  - Client sends relative mouse coordinates (dx, dy).
  - Format:
  ```json
  {
    "event": "mouse_move",
    "dx": 15.5,
    "dy": -10.2
  }
  ```
  
  #### 2. Mouse Click Event
  - Client sends mouse click action.
  - Format:
  ```json
  {
    "event": "mouse_click",
    "button": "left" | "right" | "middle"
  }
  ```
  
  #### 3. Keyboard Input Event
  - Client sends keystrokes or text chunks.
  - Format:
  ```json
  {
    "event": "keyboard_input",
    "key": "a" | "Enter" | "Backspace" | "Shift"
  }
  ```
  ```

- **`c:\Development\Monolith\.agents\sub_orch_testing\SCOPE.md`** (Lines 9-18):
  ```markdown
  ## Milestones
  | # | Name | Scope | Dependencies | Status |
  |---|------|-------|-------------|--------|
  | SM1 | Test Infra & Design | Write TEST_INFRA.md and create tests/ directory structure, run_tests.py framework. | None | DONE |
  | SM2 | Tier 1 (Feature Coverage) | Implement >=15 tests covering mouse_move, mouse_click, keyboard_input happy paths. | SM1 | PLANNED |
  ```

- **`c:\Development\Monolith\tests\test_cases.py`**:
  Contains a dummy test case:
  ```python
  import unittest
  
  class TestE2ESanity(unittest.IsolatedAsyncioTestCase):
      async def test_setup_sanity(self):
          """A dummy test case that passes immediately to verify the test discovery works."""
          self.assertTrue(True)
  ```

- **`c:\Development\Monolith\receiver/`**:
  This directory does not exist yet. No receiver script has been written.

- **`c:\Development\Monolith\tests\requirements.txt`**:
  Specifies:
  ```text
  websockets>=14.2
  ```

---

## 2. Logic Chain
1. **JSON Protocol Compliance**: Based on `PROJECT.md`, the client sends JSON payloads with specific key/value structures for `mouse_move`, `mouse_click`, and `keyboard_input` events.
2. **Milestone SM2 Target**: We must cover happy paths with at least 5 tests per feature (total >= 15 tests) in `tests/test_cases.py`.
3. **Log Interception E2E Testing**: E2E testing must verify that the receiver process accepts the WebSocket messages and logs the actions correctly. Interrogating the receiver's `stdout` stream in a subprocess is the most reliable way to achieve this without coupling test code to receiver internals.
4. **Mock Execution Support**: Running tests on developer machines requires that real mouse/keyboard emulator execution be disabled to avoid focus-stealing. The receiver should accept a `--mock` CLI flag, during which it will print event receipt to `stdout` but bypass system emulation.
5. **Stdout vs. Stderr Separation**: To keep stdout clean and easy to parse, error reports and warnings must be routed to `stderr`, while successful event processing must be printed to `stdout` in a unified, deterministic format.

---

## 3. Caveats
- **Port Conflict**: The receiver defaults to port `8080`. If another service on the test machine uses port `8080`, the tests will fail. The receiver and test runner should support configurable ports via options, though port `8080` is standard for this scope.
- **Subprocess Buffering**: On Windows, Python subprocesses buffer `stdout` by default. Spawning the receiver using the `-u` (unbuffered) Python flag is mandatory to ensure real-time log capture.
- **Operating in Mock Mode**: The tests specifically verify mock receiver logs. Testing of real OS-level PyAutoGUI/inputs is out of scope for Tier 1.

---

## 4. Conclusion

We recommend the following exact implementations for Tier 1:

### A. Recommended Structure for `receiver/receiver.py`
This mock receiver starts an asynchronous WebSocket server, accepts incoming connections, parses events, and logs them in a clean format.

```python
#!/usr/bin/env python3
"""
receiver/receiver.py
WebSocket receiver script for Antigravity Remote Control.
"""

import asyncio
import json
import argparse
import sys
import websockets

def parse_args():
    parser = argparse.ArgumentParser(description="Antigravity Remote Control WebSocket Receiver")
    parser.add_argument('--host', default='localhost', help='Host address to bind to')
    parser.add_argument('--port', type=int, default=8080, help='Port to listen on')
    parser.add_argument('--mock', action='store_true', help='Disable OS-level emulation (dry-run mode)')
    return parser.parse_args()

async def handle_client(websocket, path=None):
    """
    Handles incoming WebSocket connections and processes JSON control messages.
    """
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                print("Error: Malformed JSON payload received", file=sys.stderr)
                continue
            
            event = data.get("event")
            if not event:
                print("Error: Missing event type in payload", file=sys.stderr)
                continue
            
            if event == "mouse_move":
                dx = data.get("dx")
                dy = data.get("dy")
                if dx is None or dy is None:
                    print("Error: Missing coordinates in mouse_move event", file=sys.stderr)
                    continue
                # Print event to stdout for verification
                print(f"[MOUSE_MOVE] dx: {dx}, dy: {dy}", flush=True)
                
            elif event == "mouse_click":
                button = data.get("button")
                if not button:
                    print("Error: Missing button in mouse_click event", file=sys.stderr)
                    continue
                print(f"[MOUSE_CLICK] button: {button}", flush=True)
                
            elif event == "keyboard_input":
                key = data.get("key")
                if key is None:
                    print("Error: Missing key in keyboard_input event", file=sys.stderr)
                    continue
                print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
                
            else:
                print(f"Error: Unknown event type: {event}", file=sys.stderr)
                
    except websockets.exceptions.ConnectionClosed:
        pass

async def main():
    args = parse_args()
    
    # OS-level Emulation initialization can go here if not in mock mode.
    if not args.mock:
        # e.g., import pyautogui
        pass

    async with websockets.serve(handle_client, args.host, args.port):
        # Print server startup log to stdout
        print(f"Server listening on ws://{args.host}:{args.port}", flush=True)
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Server shut down.", file=sys.stderr)
```

### B. Precise JSON Payload and Expected Log Formats
The E2E test runner will transmit the payloads listed below and assert that the exact log output matches the expected stdout lines.

| Feature | Sub-Test Case | JSON Payload | Expected Receiver stdout Log |
|---|---|---|---|
| **Mouse Move** | positive | `{"event": "mouse_move", "dx": 5.5, "dy": 10.2}` | `[MOUSE_MOVE] dx: 5.5, dy: 10.2` |
| | negative | `{"event": "mouse_move", "dx": -12.4, "dy": -8.1}` | `[MOUSE_MOVE] dx: -12.4, dy: -8.1` |
| | integers | `{"event": "mouse_move", "dx": 10.0, "dy": 20.0}` | `[MOUSE_MOVE] dx: 10.0, dy: 20.0` |
| | zero | `{"event": "mouse_move", "dx": 0.0, "dy": 0.0}` | `[MOUSE_MOVE] dx: 0.0, dy: 0.0` |
| | precision | `{"event": "mouse_move", "dx": 1.2345, "dy": -5.6789}` | `[MOUSE_MOVE] dx: 1.2345, dy: -5.6789` |
| **Mouse Click** | left | `{"event": "mouse_click", "button": "left"}` | `[MOUSE_CLICK] button: left` |
| | right | `{"event": "mouse_click", "button": "right"}` | `[MOUSE_CLICK] button: right` |
| | middle | `{"event": "mouse_click", "button": "middle"}` | `[MOUSE_CLICK] button: middle` |
| | sequence | `{"event": "mouse_click", "button": "left"}`<br>`{"event": "mouse_click", "button": "right"}` | `[MOUSE_CLICK] button: left`<br>`[MOUSE_CLICK] button: right` |
| | rapid | `{"event": "mouse_click", "button": "left"}`<br>`{"event": "mouse_click", "button": "left"}` | `[MOUSE_CLICK] button: left`<br>`[MOUSE_CLICK] button: left` |
| **Keyboard Input** | lowercase char | `{"event": "keyboard_input", "key": "a"}` | `[KEYBOARD_INPUT] key: a` |
| | uppercase char | `{"event": "keyboard_input", "key": "Z"}` | `[KEYBOARD_INPUT] key: Z` |
| | Enter key | `{"event": "keyboard_input", "key": "Enter"}` | `[KEYBOARD_INPUT] key: Enter` |
| | Backspace key | `{"event": "keyboard_input", "key": "Backspace"}` | `[KEYBOARD_INPUT] key: Backspace` |
| | Modifier Shift | `{"event": "keyboard_input", "key": "Shift"}` | `[KEYBOARD_INPUT] key: Shift` |
| | number | `{"event": "keyboard_input", "key": "1"}` | `[KEYBOARD_INPUT] key: 1` |
| | Space key | `{"event": "keyboard_input", "key": "Space"}` | `[KEYBOARD_INPUT] key: Space` |

### C. Recommended Test Case Implementation in `tests/test_cases.py`
This implementation manages the lifecycle of the receiver subprocess asynchronously, performs retry-based connection, sends events, and reads stdout line-by-line to execute assertions.

```python
"""
tests/test_cases.py
Tier 1 Feature Coverage E2E Tests for Antigravity Remote Control.
"""

import unittest
import asyncio
import sys
import os
import json
import websockets

class TestTier1FeatureCoverage(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        # Locate project root and receiver path
        project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        receiver_path = os.path.join(project_root, 'receiver', 'receiver.py')
        
        # Spawn the receiver process in mock mode with unbuffered output (-u)
        self.process = await asyncio.create_subprocess_exec(
            sys.executable, '-u', receiver_path, '--mock',
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        # Wait for and read the startup log line to clear the buffer
        try:
            line = await asyncio.wait_for(self.process.stdout.readline(), timeout=3.0)
            line_str = line.decode('utf-8').strip()
            self.assertIn("Server listening on ws://", line_str)
        except asyncio.TimeoutError:
            self.process.terminate()
            await self.process.wait()
            raise RuntimeError("Failed to read server startup log in time.")
            
        # Connect client to the WebSocket server
        self.websocket = await websockets.connect("ws://localhost:8080")

    async def asyncTearDown(self):
        # Gracefully close WebSocket
        if hasattr(self, 'websocket'):
            await self.websocket.close()
        
        # Gracefully terminate receiver process
        if hasattr(self, 'process'):
            self.process.terminate()
            try:
                await asyncio.wait_for(self.process.wait(), timeout=2.0)
            except asyncio.TimeoutError:
                self.process.kill()
                await self.process.wait()

    async def send_and_assert_log(self, payload: dict, expected_log: str):
        """Sends payload and asserts that the receiver prints the expected stdout log."""
        await self.websocket.send(json.dumps(payload))
        try:
            line_bytes = await asyncio.wait_for(self.process.stdout.readline(), timeout=1.0)
            line = line_bytes.decode('utf-8').strip()
            self.assertEqual(line, expected_log)
        except asyncio.TimeoutError:
            self.fail(f"Timed out waiting for receiver log: '{expected_log}'")

    # =========================================================================
    # Mouse Move Tests (5 cases)
    # =========================================================================

    async def test_mouse_move_positive(self):
        payload = {"event": "mouse_move", "dx": 5.5, "dy": 10.2}
        await self.send_and_assert_log(payload, "[MOUSE_MOVE] dx: 5.5, dy: 10.2")

    async def test_mouse_move_negative(self):
        payload = {"event": "mouse_move", "dx": -12.4, "dy": -8.1}
        await self.send_and_assert_log(payload, "[MOUSE_MOVE] dx: -12.4, dy: -8.1")

    async def test_mouse_move_integers(self):
        payload = {"event": "mouse_move", "dx": 10.0, "dy": 20.0}
        await self.send_and_assert_log(payload, "[MOUSE_MOVE] dx: 10.0, dy: 20.0")

    async def test_mouse_move_zero(self):
        payload = {"event": "mouse_move", "dx": 0.0, "dy": 0.0}
        await self.send_and_assert_log(payload, "[MOUSE_MOVE] dx: 0.0, dy: 0.0")

    async def test_mouse_move_precision(self):
        payload = {"event": "mouse_move", "dx": 1.2345, "dy": -5.6789}
        await self.send_and_assert_log(payload, "[MOUSE_MOVE] dx: 1.2345, dy: -5.6789")

    # =========================================================================
    # Mouse Click Tests (5 cases)
    # =========================================================================

    async def test_mouse_click_left(self):
        payload = {"event": "mouse_click", "button": "left"}
        await self.send_and_assert_log(payload, "[MOUSE_CLICK] button: left")

    async def test_mouse_click_right(self):
        payload = {"event": "mouse_click", "button": "right"}
        await self.send_and_assert_log(payload, "[MOUSE_CLICK] button: right")

    async def test_mouse_click_middle(self):
        payload = {"event": "mouse_click", "button": "middle"}
        await self.send_and_assert_log(payload, "[MOUSE_CLICK] button: middle")

    async def test_mouse_click_sequence_left_right(self):
        payload_left = {"event": "mouse_click", "button": "left"}
        payload_right = {"event": "mouse_click", "button": "right"}
        
        await self.send_and_assert_log(payload_left, "[MOUSE_CLICK] button: left")
        await self.send_and_assert_log(payload_right, "[MOUSE_CLICK] button: right")

    async def test_mouse_click_rapid_left(self):
        payload1 = {"event": "mouse_click", "button": "left"}
        payload2 = {"event": "mouse_click", "button": "left"}
        
        await self.send_and_assert_log(payload1, "[MOUSE_CLICK] button: left")
        await self.send_and_assert_log(payload2, "[MOUSE_CLICK] button: left")

    # =========================================================================
    # Keyboard Input Tests (7 cases)
    # =========================================================================

    async def test_keyboard_single_char(self):
        payload = {"event": "keyboard_input", "key": "a"}
        await self.send_and_assert_log(payload, "[KEYBOARD_INPUT] key: a")

    async def test_keyboard_uppercase_char(self):
        payload = {"event": "keyboard_input", "key": "Z"}
        await self.send_and_assert_log(payload, "[KEYBOARD_INPUT] key: Z")

    async def test_keyboard_special_enter(self):
        payload = {"event": "keyboard_input", "key": "Enter"}
        await self.send_and_assert_log(payload, "[KEYBOARD_INPUT] key: Enter")

    async def test_keyboard_special_backspace(self):
        payload = {"event": "keyboard_input", "key": "Backspace"}
        await self.send_and_assert_log(payload, "[KEYBOARD_INPUT] key: Backspace")

    async def test_keyboard_modifier_shift(self):
        payload = {"event": "keyboard_input", "key": "Shift"}
        await self.send_and_assert_log(payload, "[KEYBOARD_INPUT] key: Shift")

    async def test_keyboard_number(self):
        payload = {"event": "keyboard_input", "key": "1"}
        await self.send_and_assert_log(payload, "[KEYBOARD_INPUT] key: 1")

    async def test_keyboard_special_space(self):
        payload = {"event": "keyboard_input", "key": "Space"}
        await self.send_and_assert_log(payload, "[KEYBOARD_INPUT] key: Space")
```

---

## 5. Verification Method
To independently verify this design, an implementer should:

1. Create `receiver/receiver.py` using the structure provided in Section 4.A.
2. Replace `tests/test_cases.py` with the code provided in Section 4.C.
3. Install dependencies:
   ```bash
   pip install -r tests/requirements.txt
   ```
4. Run the test suite:
   ```bash
   python tests/run_tests.py
   ```
5. Check that all 17 tests pass successfully.

### Invalidation Conditions:
- The WebSocket port `8080` is blocked or bound.
- The `stdout` stream is buffered (prevented by the `-u` option in subprocess creation).
- Malformed payloads do not trigger error messages on `stderr`.
