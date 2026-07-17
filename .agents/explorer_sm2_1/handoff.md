# E2E Test Design & Mock Receiver Recommendations (Milestone SM2)

This report outlines the proposed implementations for **Tier 1 (Feature Coverage)** E2E tests in `tests/test_cases.py` and the architecture of the mock receiver `receiver/receiver.py`.

---

## 1. Observation

I directly observed and verified the following configurations:

*   **`PROJECT.md`** Interface Contracts (lines 26-60):
    *   `mouse_move`: `{"event": "mouse_move", "dx": float, "dy": float}`
    *   `mouse_click`: `{"event": "mouse_click", "button": "left" | "right" | "middle"}`
    *   `keyboard_input`: `{"event": "keyboard_input", "key": string}`
*   **`TEST_INFRA.md`** Tier 1 Specifications (lines 69-87):
    *   Requires at least 15 tests.
    *   Specifies standard subprocess lifecycle: spawn with `sys.executable -u receiver/receiver.py --mock` and connect to `ws://localhost:8080`.
*   **`tests/test_cases.py`** (lines 1-7):
    *   Currently contains only a dummy sanity test (`test_setup_sanity`) using `unittest.IsolatedAsyncioTestCase`.
*   **`tests/run_tests.py`** and **`tests/requirements.txt`**:
    *   Test runner discoverable and executable via `python tests/run_tests.py` (which completed successfully).
    *   `websockets>=14.2` is specified as a runner dependency.

---

## 2. Logic Chain

1.  **Requirement**: The task mandates at least 15 tests in total for Tier 1, with at least 5 tests per feature (`mouse_move`, `mouse_click`, `keyboard_input`).
2.  **Implementation Selection**:
    *   To fulfill this, we design exactly **5 tests** for `mouse_move` (positive, negative, integers, zero, precision).
    *   We design exactly **5 tests** for `mouse_click` (left, right, middle, consecutive left clicks, and a sequence of all buttons).
    *   We design **7 tests** for `keyboard_input` (lowercase character, uppercase character, Enter, Backspace, Space, Shift, number key).
    *   Total tests proposed = 17 tests (which meets the ">= 15 total" and ">= 5 per feature" constraints).
3.  **Output Format Standardization**:
    *   The test runner checks receiver correctness by inspecting the receiver's `stdout` logs.
    *   Therefore, the mock receiver must output logs in a clean, predictable, and parseable format:
        *   `[EVENT] mouse_move dx=<float> dy=<float>`
        *   `[EVENT] mouse_click button=<button>`
        *   `[EVENT] keyboard_input key=<key>`
4.  **Subprocess Lifecycle**:
    *   To guarantee test isolation, each test must start the receiver subprocess, connect via WebSockets, execute the command(s), close the connection, terminate the receiver subprocess, read all stdout, and run assertions.
    *   Using a centralized `send_and_verify` helper in the test class dramatically reduces duplication and simplifies implementation.

---

## 3. Caveats

*   **Read-Only Scope**: In compliance with our role, no source or test files were modified. These recommendations are intended for the implementation agent.
*   **Emulation Bypass**: When executing tests with `--mock`, the receiver must bypass OS-level emulation and only print event summaries to stdout to avoid interfering with the host OS environment.

---

## 4. Conclusion & Recommendations

### A. Precise JSON Payload and Expected Stdout Log Structures

| Test Case Name | JSON Payload | Expected Receiver stdout Log |
|---|---|---|
| **`test_mouse_move_positive`** | `{"event": "mouse_move", "dx": 5.5, "dy": 10.2}` | `[EVENT] mouse_move dx=5.5 dy=10.2` |
| **`test_mouse_move_negative`** | `{"event": "mouse_move", "dx": -12.4, "dy": -8.1}` | `[EVENT] mouse_move dx=-12.4 dy=-8.1` |
| **`test_mouse_move_integers`** | `{"event": "mouse_move", "dx": 10, "dy": 20}` | `[EVENT] mouse_move dx=10.0 dy=20.0` |
| **`test_mouse_move_zero`** | `{"event": "mouse_move", "dx": 0.0, "dy": 0.0}` | `[EVENT] mouse_move dx=0.0 dy=0.0` |
| **`test_mouse_move_precision`** | `{"event": "mouse_move", "dx": 1.2345, "dy": -5.6789}` | `[EVENT] mouse_move dx=1.2345 dy=-5.6789` |
| **`test_mouse_click_left`** | `{"event": "mouse_click", "button": "left"}` | `[EVENT] mouse_click button=left` |
| **`test_mouse_click_right`** | `{"event": "mouse_click", "button": "right"}` | `[EVENT] mouse_click button=right` |
| **`test_mouse_click_middle`** | `{"event": "mouse_click", "button": "middle"}` | `[EVENT] mouse_click button=middle` |
| **`test_mouse_click_consecutive_left`** | Sequential: <br>1. `{"event": "mouse_click", "button": "left"}`<br>2. `{"event": "mouse_click", "button": "left"}` | `[EVENT] mouse_click button=left`<br>`[EVENT] mouse_click button=left` |
| **`test_mouse_click_all_buttons`** | Sequential: <br>1. `{"event": "mouse_click", "button": "left"}`<br>2. `{"event": "mouse_click", "button": "right"}`<br>3. `{"event": "mouse_click", "button": "middle"}` | `[EVENT] mouse_click button=left`<br>`[EVENT] mouse_click button=right`<br>`[EVENT] mouse_click button=middle` |
| **`test_keyboard_single_char`** | `{"event": "keyboard_input", "key": "a"}` | `[EVENT] keyboard_input key=a` |
| **`test_keyboard_uppercase_char`** | `{"event": "keyboard_input", "key": "Z"}` | `[EVENT] keyboard_input key=Z` |
| **`test_keyboard_enter`** | `{"event": "keyboard_input", "key": "Enter"}` | `[EVENT] keyboard_input key=Enter` |
| **`test_keyboard_backspace`** | `{"event": "keyboard_input", "key": "Backspace"}` | `[EVENT] keyboard_input key=Backspace` |
| **`test_keyboard_space`** | `{"event": "keyboard_input", "key": "Space"}` | `[EVENT] keyboard_input key=Space` |
| **`test_keyboard_shift`** | `{"event": "keyboard_input", "key": "Shift"}` | `[EVENT] keyboard_input key=Shift` |
| **`test_keyboard_number`** | `{"event": "keyboard_input", "key": "1"}` | `[EVENT] keyboard_input key=1` |

### B. Proposed `tests/test_cases.py` Implementation

This is the recommended code layout for `tests/test_cases.py`. It includes clean process handling and the 17 specified E2E test cases:

```python
import unittest
import asyncio
import sys
import os
import json
import websockets

class TestE2ESanity(unittest.IsolatedAsyncioTestCase):
    async def test_setup_sanity(self):
        """A dummy test case that passes immediately to verify the test discovery works."""
        self.assertTrue(True)

class TestTier1FeatureCoverage(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        # Locate project root and start receiver
        project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        receiver_script = os.path.join(project_root, "receiver", "receiver.py")
        
        # Spawn receiver/receiver.py with --mock argument
        self.process = await asyncio.create_subprocess_exec(
            sys.executable, "-u", receiver_script, "--mock",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        # Retry connection to the WebSocket server on port 8080
        self.websocket = None
        for _ in range(30):
            try:
                self.websocket = await websockets.connect("ws://localhost:8080")
                break
            except Exception:
                await asyncio.sleep(0.1)
                
        if not self.websocket:
            # Clean up if connection failed
            self.process.terminate()
            await self.process.wait()
            raise RuntimeError("Failed to connect to receiver WebSocket server at ws://localhost:8080")

    async def asyncTearDown(self):
        # Ensure resources are closed and cleaned up in case of failure
        if hasattr(self, 'websocket') and self.websocket:
            try:
                await self.websocket.close()
            except Exception:
                pass
        if hasattr(self, 'process') and self.process:
            if self.process.returncode is None:
                try:
                    self.process.terminate()
                    await asyncio.wait_for(self.process.wait(), timeout=1.0)
                except Exception:
                    try:
                        self.process.kill()
                        await self.process.wait()
                    except Exception:
                        pass

    async def stop_receiver(self):
        """Helper to cleanly stop the receiver and capture stdout/stderr."""
        if hasattr(self, 'websocket') and self.websocket:
            try:
                await self.websocket.close()
            except Exception:
                pass
            self.websocket = None
            
        if hasattr(self, 'process') and self.process:
            try:
                self.process.terminate()
                stdout, stderr = await asyncio.wait_for(self.process.communicate(), timeout=2.0)
                self.stdout = stdout.decode('utf-8')
                self.stderr = stderr.decode('utf-8')
            except Exception:
                try:
                    self.process.kill()
                    stdout, stderr = await self.process.communicate()
                    self.stdout = stdout.decode('utf-8')
                    self.stderr = stderr.decode('utf-8')
                except Exception:
                    self.stdout = ""
                    self.stderr = ""
            self.process = None

    async def send_and_verify(self, payloads, expected_logs):
        """Helper to send a payload (or list of payloads) and verify expected stdout logs are present."""
        if not isinstance(payloads, list):
            payloads = [payloads]
        if not isinstance(expected_logs, list):
            expected_logs = [expected_logs]
            
        for payload in payloads:
            await self.websocket.send(json.dumps(payload))
            
        # Allow processing time
        await asyncio.sleep(0.1)
        
        # Stop receiver to capture output
        await self.stop_receiver()
        
        # Verify stdout logs
        for log in expected_logs:
            self.assertIn(log, self.stdout)

    # ==========================================
    # Mouse Move Tests (Happy Paths)
    # ==========================================
    async def test_mouse_move_positive(self):
        """Send relative mouse movement with positive coordinates."""
        await self.send_and_verify(
            {"event": "mouse_move", "dx": 5.5, "dy": 10.2},
            "[EVENT] mouse_move dx=5.5 dy=10.2"
        )

    async def test_mouse_move_negative(self):
        """Send relative mouse movement with negative coordinates."""
        await self.send_and_verify(
            {"event": "mouse_move", "dx": -12.4, "dy": -8.1},
            "[EVENT] mouse_move dx=-12.4 dy=-8.1"
        )

    async def test_mouse_move_integers(self):
        """Send relative mouse movement with integer coordinates."""
        await self.send_and_verify(
            {"event": "mouse_move", "dx": 10, "dy": 20},
            "[EVENT] mouse_move dx=10.0 dy=20.0"
        )

    async def test_mouse_move_zero(self):
        """Send relative mouse movement with zero displacement."""
        await self.send_and_verify(
            {"event": "mouse_move", "dx": 0.0, "dy": 0.0},
            "[EVENT] mouse_move dx=0.0 dy=0.0"
        )

    async def test_mouse_move_precision(self):
        """Send relative mouse movement with high-precision coordinates."""
        await self.send_and_verify(
            {"event": "mouse_move", "dx": 1.2345, "dy": -5.6789},
            "[EVENT] mouse_move dx=1.2345 dy=-5.6789"
        )

    # ==========================================
    # Mouse Click Tests (Happy Paths)
    # ==========================================
    async def test_mouse_click_left(self):
        """Click the left mouse button."""
        await self.send_and_verify(
            {"event": "mouse_click", "button": "left"},
            "[EVENT] mouse_click button=left"
        )

    async def test_mouse_click_right(self):
        """Click the right mouse button."""
        await self.send_and_verify(
            {"event": "mouse_click", "button": "right"},
            "[EVENT] mouse_click button=right"
        )

    async def test_mouse_click_middle(self):
        """Click the middle mouse button."""
        await self.send_and_verify(
            {"event": "mouse_click", "button": "middle"},
            "[EVENT] mouse_click button=middle"
        )

    async def test_mouse_click_consecutive_left(self):
        """Click the left mouse button twice consecutively."""
        payloads = [
            {"event": "mouse_click", "button": "left"},
            {"event": "mouse_click", "button": "left"}
        ]
        logs = [
            "[EVENT] mouse_click button=left",
            "[EVENT] mouse_click button=left"
        ]
        await self.send_and_verify(payloads, logs)

    async def test_mouse_click_all_buttons(self):
        """Click all mouse buttons in a sequence (left, right, middle)."""
        payloads = [
            {"event": "mouse_click", "button": "left"},
            {"event": "mouse_click", "button": "right"},
            {"event": "mouse_click", "button": "middle"}
        ]
        logs = [
            "[EVENT] mouse_click button=left",
            "[EVENT] mouse_click button=right",
            "[EVENT] mouse_click button=middle"
        ]
        await self.send_and_verify(payloads, logs)

    # ==========================================
    # Keyboard Input Tests (Happy Paths)
    # ==========================================
    async def test_keyboard_single_char(self):
        """Send a single lowercase alphanumeric character."""
        await self.send_and_verify(
            {"event": "keyboard_input", "key": "a"},
            "[EVENT] keyboard_input key=a"
        )

    async def test_keyboard_uppercase_char(self):
        """Send a single uppercase character."""
        await self.send_and_verify(
            {"event": "keyboard_input", "key": "Z"},
            "[EVENT] keyboard_input key=Z"
        )

    async def test_keyboard_enter(self):
        """Send the special Enter key."""
        await self.send_and_verify(
            {"event": "keyboard_input", "key": "Enter"},
            "[EVENT] keyboard_input key=Enter"
        )

    async def test_keyboard_backspace(self):
        """Send the Backspace key."""
        await self.send_and_verify(
            {"event": "keyboard_input", "key": "Backspace"},
            "[EVENT] keyboard_input key=Backspace"
        )

    async def test_keyboard_space(self):
        """Send the Space key."""
        await self.send_and_verify(
            {"event": "keyboard_input", "key": "Space"},
            "[EVENT] keyboard_input key=Space"
        )

    async def test_keyboard_shift(self):
        """Send the Shift modifier key."""
        await self.send_and_verify(
            {"event": "keyboard_input", "key": "Shift"},
            "[EVENT] keyboard_input key=Shift"
        )

    async def test_keyboard_number(self):
        """Send a number key."""
        await self.send_and_verify(
            {"event": "keyboard_input", "key": "1"},
            "[EVENT] keyboard_input key=1"
        )
```

### C. Proposed `receiver/receiver.py` Structure

This is the recommended code layout for `receiver/receiver.py`. It starts the WebSocket server on port 8080 and handles mock logs as specified:

```python
import asyncio
import json
import argparse
import websockets

async def handler(websocket):
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                event = data.get("event")
                if event == "mouse_move":
                    dx = data.get("dx")
                    dy = data.get("dy")
                    # Ensure float representation
                    dx_val = float(dx) if dx is not None else 0.0
                    dy_val = float(dy) if dy is not None else 0.0
                    print(f"[EVENT] mouse_move dx={dx_val} dy={dy_val}", flush=True)
                elif event == "mouse_click":
                    button = data.get("button")
                    print(f"[EVENT] mouse_click button={button}", flush=True)
                elif event == "keyboard_input":
                    key = data.get("key")
                    print(f"[EVENT] keyboard_input key={key}", flush=True)
                else:
                    print(f"[ERROR] Unknown event: {event}", flush=True)
            except json.JSONDecodeError:
                print("[ERROR] Malformed JSON", flush=True)
            except Exception as e:
                print(f"[ERROR] Exception processing message: {e}", flush=True)
    except websockets.exceptions.ConnectionClosed:
        pass

async def main():
    parser = argparse.ArgumentParser(description="Antigravity Receiver Server")
    parser.add_argument("--mock", action="store_true", help="Run in mock mode (no OS emulation)")
    parser.add_argument("--dry-run", action="store_true", help="Run in dry-run mode")
    parser.add_argument("--port", type=int, default=8080, help="Port to bind to")
    args = parser.parse_args()

    async with websockets.serve(handler, "localhost", args.port):
        print(f"Receiver started on ws://localhost:{args.port}", flush=True)
        await asyncio.Future()  # Keep server running

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Receiver stopped.")
```

---

## 5. Verification Method

Once both files are implemented by the worker/implementer agent, the entire suite can be validated by executing the test command:
```bash
python tests/run_tests.py
```
Or using:
```bash
pytest tests/
```

**Invalidation Conditions**:
*   Failure of any test case in `tests/test_cases.py`.
*   Websocket connection timeouts (e.g. if the receiver takes more than 3.0s to start).
*   Logs mismatching the expected output structure.
