# Explorer 2 Handoff Report: E2E Test Infra & Design (Milestone SM1)

## 1. Observation

Direct observations from the workspace files and metadata:
- **Codebase State**: The directory `c:\Development\Monolith\` currently only contains `.agents/`, `PROJECT.md`, and a root `ORIGINAL_REQUEST.md`. No code files exist in `/receiver/`, `/android/`, or `/tests/` yet.
- **WebSocket Protocol Specifications (`PROJECT.md` lines 30-59)**:
  - **Trackpad Move Event**:
    ```json
    {
      "event": "mouse_move",
      "dx": 15.5,
      "dy": -10.2
    }
    ```
  - **Mouse Click Event**:
    ```json
    {
      "event": "mouse_click",
      "button": "left" | "right" | "middle"
    }
    ```
  - **Keyboard Input Event**:
    ```json
    {
      "event": "keyboard_input",
      "key": "a" | "Enter" | "Backspace" | "Shift"
    }
    ```
- **Scope Milestones (`.agents\sub_orch_testing\SCOPE.md` lines 9-18)**:
  - `SM1`: Test Infra & Design (Write `TEST_INFRA.md`, create `tests/` directory structure, `run_tests.py` framework).
  - `SM2`: Tier 1 (Feature Coverage) - ≥15 tests covering mouse_move, mouse_click, keyboard_input happy paths.
  - `SM3`: Tier 2 (Boundary & Corner) - ≥15 tests covering boundary/edge/error cases.
  - `SM4`: Tier 3 (Cross-Feature) - Pairwise feature interaction tests.
  - `SM5`: Tier 4 (Real-World Scenarios) - ≥5 complex real-world workload tests.
- **`TEST_INFRA.md` Template**: Found in the system database `C:\Users\devon\.gemini\antigravity\agyhub_summaries_proto.pb` (index 250484), prescribing the sections: Test Philosophy, Feature Inventory, Test Architecture, Real-World Application Scenarios (Tier 4), and Coverage Thresholds.

---

## 2. Logic Chain

From the observations above, the design and infrastructure recommendations are derived step-by-step:
1. **Asynchronous Framework Selection**:
   - The communication protocol relies on WebSockets (`ws://localhost:8080`).
   - The test harness needs to spawn the receiver process, write to its socket, and simultaneously read its `stdout` streams.
   - Using standard Python `unittest` synchronously would require running loops or spawning worker threads to prevent blocking during socket and process output reads.
   - *Conclusion*: Utilizing Python 3.8+ `unittest.IsolatedAsyncioTestCase` coupled with standard library `asyncio` is the optimal approach. It allows writing native `async def test_...` methods, letting the testing runtime manage the event loop seamlessly.
2. **Subprocess Lifecycle Management**:
   - Spawning the receiver script (`receiver/receiver.py`) is done using `asyncio.create_subprocess_exec(sys.executable, "receiver/receiver.py", ...)` to ensure the current Python environment's interpreter is used.
   - To achieve full test isolation, we recommend spawning and terminating the subprocess *per test case* (`asyncSetUp` and `asyncTearDown`). This guarantees no state or residual connection leaks carry over between tests.
   - Since starting a network server might take a brief period, the test runner client should implement a connection retry loop in `asyncSetUp` (e.g., trying to connect via WebSockets every 0.1s up to a maximum of 2.0s) before starting test assertions.
   - Clean shutdown is performed by sending `process.terminate()`, waiting for completion, and failing back to `process.kill()` if a timeout is reached.
3. **Connection and Output Verification**:
   - The `websockets` library (external) is selected for the WebSocket client due to its simple, robust async API.
   - Capturing and verifying receiver output is done by reading lines from `process.stdout` (redirected via `asyncio.subprocess.PIPE`).
   - To prevent test suite hanging if the receiver fails to log or crashes, the stdout read must be wrapped in `asyncio.wait_for(..., timeout=1.0)`.

---

## 3. Caveats

- **External Dependency**: The tests depend on the external `websockets` Python library. It must be installed in the target running environment (`pip install websockets`).
- **Server Port Collisions**: The test runner assumes `receiver.py` binds to port `8080`. If another process on the host machine occupies this port, the tests will fail to start. We suggest making the port configurable via CLI/environment variables for both the server and client.
- **Boot and Shutdown Overhead**: Spawning the receiver subprocess per test case adds small latency (~50-100ms per test). While negligible for 50 tests, if the test count grows excessively, starting the subprocess once per test class (`asyncSetUpClass` / `asyncTearDownClass`) could be considered, though it introduces state pollution risks.

---

## 4. Conclusion

### A. Recommended Code Layout & Architecture
```
/receiver/
└── receiver.py        # Python WebSocket server (listens on port 8080)
/tests/
├── run_tests.py       # Main E2E test runner (executes/discovers tests)
└── test_cases.py      # Contains test classes for Tiers 1-4
```

### B. Recommended Code Structure for Test Runner
```python
# tests/run_tests.py
import unittest
import asyncio
import sys
import os

# Ensure the root project path is in sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

def main():
    # Discovers and runs all tests under tests/
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir="tests", pattern="test_*.py")
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)

if __name__ == "__main__":
    main()
```

```python
# tests/test_cases.py
import unittest
import asyncio
import sys
import json
import websockets

class E2EBaseTestCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        # 1. Start receiver.py as a subprocess
        # Assumes receiver.py is in the parent directory's 'receiver' folder
        self.process = await asyncio.create_subprocess_exec(
            sys.executable, "receiver/receiver.py",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        # 2. Wait for the WebSocket server to become ready by attempting connections
        connected = False
        for _ in range(20):  # Retry up to 2 seconds
            try:
                self.websocket = await websockets.connect("ws://localhost:8080")
                connected = True
                break
            except Exception:
                await asyncio.sleep(0.1)
                
        if not connected:
            self.process.terminate()
            await self.process.wait()
            raise RuntimeError("Failed to connect to receiver WebSocket server at ws://localhost:8080")

    async def asyncTearDown(self):
        # 1. Close WebSocket client
        if hasattr(self, 'websocket') and self.websocket:
            await self.websocket.close()
            
        # 2. Terminate receiver subprocess
        if hasattr(self, 'process') and self.process:
            self.process.terminate()
            try:
                await asyncio.wait_for(self.process.wait(), timeout=1.0)
            except asyncio.TimeoutError:
                self.process.kill()
                await self.process.wait()

    async def send_event_and_verify_stdout(self, payload: dict, expected_substrings: list):
        """Helper to send a JSON payload and assert receiver stdout contains expected strings."""
        await self.websocket.send(json.dumps(payload))
        
        # Read a line from the receiver's stdout with a 1-second timeout
        line_bytes = await asyncio.wait_for(self.process.stdout.readline(), timeout=1.0)
        line = line_bytes.decode("utf-8").strip()
        
        # Verify content
        for substring in expected_substrings:
            self.assertIn(substring, line)
```

### C. Draft of `TEST_INFRA.md`
This is the recommended draft to be placed at the project root:

```markdown
# E2E Test Infra: Antigravity Remote Control

## Test Philosophy
- Opaque-box, requirement-driven. No dependency on implementation design.
- Methodology: Category-Partition + BVA (Boundary Value Analysis) + Pairwise + Workload Testing.

## Feature Inventory
| # | Feature | Source (requirement) | Tier 1 | Tier 2 | Tier 3 |
|---|---------|---------------------|:------:|:------:|:------:|
| 1 | Mouse Move | PROJECT.md §1 & ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 2 | Mouse Click | PROJECT.md §2 & ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 3 | Keyboard Input | PROJECT.md §3 & ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |

## Test Architecture
- **Test Runner**: `tests/run_tests.py` using Python's standard `unittest` framework with `IsolatedAsyncioTestCase` for native asynchronous execution. It discovers all test cases defined in `tests/test_cases.py`.
- **Subprocess Management**: Spawns `receiver/receiver.py` using `asyncio.create_subprocess_exec` before each test case, monitors the server availability, and safely terminates the subprocess after the test concludes.
- **WebSocket connection**: Connects to `ws://localhost:8080` using the `websockets` library, transmits JSON payloads, and reads redirected stdout streams to verify correctness.
- **Test Case Format**: Sends structured JSON payloads representing UI actions, and asserts that stdout captures matching decoded actions.
- **Directory Layout**:
  ```
  /tests/
  ├── run_tests.py       # Main test entry point and discovery runner
  └── test_cases.py      # End-to-end tests for Tiers 1-4
  ```

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Text Editor Navigation & Editing | Mouse Move, Mouse Click, Keyboard Input | High |
| 2 | Context Menu Action | Mouse Move, Mouse Click | Medium |
| 3 | Keyboard Shortcut Trigger | Keyboard Input | Medium |
| 4 | Web Browsing & Interaction | Mouse Move, Mouse Click, Keyboard Input | High |
| 5 | Interleaved Mouse Drawing | Mouse Move, Mouse Click | Medium |

## Coverage Thresholds
- **Tier 1 (Feature Coverage)**: ≥5 tests per feature (happy paths).
- **Tier 2 (Boundary & Corner Cases)**: ≥5 tests per feature covering extreme values, invalid/missing types, and schema violations.
- **Tier 3 (Cross-Feature Combinations)**: Pairwise coverage of mouse movement, click actions, and keystroke events.
- **Tier 4 (Real-World Workloads)**: ≥5 realistic scenarios reproducing typical user remote interactions.
```

---

## 5. Verification Method

To independently verify the test infrastructure design once the files are implemented:
1. **Compilation & Discovery**: Run `python tests/run_tests.py` to confirm that the test suite is successfully discovered and runs.
2. **Subprocess Management**: Run `python tests/run_tests.py` and verify that the `receiver/receiver.py` process is successfully spawned and terminated by inspecting the process table (no orphaned python processes matching `receiver/receiver.py` remain).
3. **Execution Verification**: Check the logs or console output to verify that tests output successful connection establishment logs and mock event transmissions.
4. **Invalidation conditions**:
   - If `websockets` library is missing or cannot connect, the runner should exit with status `1` and raise a descriptive traceback.
   - If `receiver/receiver.py` outputs errors on `stderr`, the test runner should raise assertions containing the relevant traceback.
