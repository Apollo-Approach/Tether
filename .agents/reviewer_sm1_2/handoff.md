# Handoff Report — Reviewer SM1-2

This handoff report evaluates the Test Infrastructure & Design (Milestone SM1) files: `TEST_INFRA.md`, `tests/run_tests.py`, and `tests/test_cases.py`.

---

## 1. Observation

### File Structure and Presence
The following files exist in `c:\Development\Monolith`:
- `TEST_INFRA.md` (documented design and tiers)
- `tests/run_tests.py` (test runner entrypoint)
- `tests/test_cases.py` (test definitions)
- `tests/requirements.txt` (dependencies)

### File Content Inspection
- **`tests/run_tests.py`** uses `unittest.TestLoader().discover` to discover test cases (lines 14-15):
  ```python
  loader = unittest.TestLoader()
  suite = loader.discover(start_dir=os.path.join(project_root, 'tests'), pattern='test_*.py')
  ```
- **`tests/test_cases.py`** defines a sanity test (lines 3-6):
  ```python
  class TestE2ESanity(unittest.IsolatedAsyncioTestCase):
      async def test_setup_sanity(self):
          """A dummy test case that passes immediately to verify the test discovery works."""
          self.assertTrue(True)
  ```
- **`tests/requirements.txt`** contains:
  ```text
  websockets>=14.2
  ```
- **`receiver/`** directory and `receiver.py` do not yet exist in the codebase.

### Test Executions
1. Running `python tests/run_tests.py` from the project root (`c:\Development\Monolith`):
   - Command: `python tests/run_tests.py`
   - Output:
     ```text
     test_setup_sanity (test_cases.TestE2ESanity.test_setup_sanity)
     A dummy test case that passes immediately to verify the test discovery works. ... ok

     ----------------------------------------------------------------------
     Ran 1 test in 0.011s

     OK
     Discovering and running tests...
     ```
2. Running `python tests/run_tests.py` from outside the project root (`C:\Users\devon`):
   - Command: `python c:\Development\Monolith\tests\run_tests.py`
   - Output:
     ```text
     test_setup_sanity (test_cases.TestE2ESanity.test_setup_sanity)
     A dummy test case that passes immediately to verify the test discovery works. ... ok

     ----------------------------------------------------------------------
     Ran 1 test in 0.016s

     OK
     Discovering and running tests...
     ```
3. Running `pytest tests/` from the project root:
   - Command: `pytest tests/`
   - Output:
     ```text
     collected 1 item
     tests\test_cases.py .                                                    [100%]
     ============================== 1 passed in 0.29s ==============================
     ```

### Environment Checks
- **Python version**: 3.12.10
- **websockets package**: version 14.2 is installed and importable.

---

## 2. Logic Chain

1. **Milestone SM1 Conformance**: According to `c:\Development\Monolith\.agents\sub_orch_testing\SCOPE.md`, the goal of milestone SM1 (Test Infra & Design) is to:
   > "Write TEST_INFRA.md and create tests/ directory structure, run_tests.py framework."
   The functional test cases (Tier 1-4) are explicitly planned for SM2, SM3, SM4, and SM5. Therefore, having only `TestE2ESanity` in `tests/test_cases.py` is conformant and is not a facade or cheating implementation.
2. **Runner Robustness**:
   - The test runner `run_tests.py` successfully resolves the `project_root` relative to `__file__` (using `os.path.dirname(os.path.dirname(os.path.abspath(__file__)))`), which allows executing the runner from any working directory without path failures.
   - However, since `top_level_dir` is omitted in `loader.discover()`, it defaults to `start_dir` (which is `c:\Development\Monolith\tests`). Discovered modules are loaded as top-level modules (e.g. `test_cases`) rather than package modules (e.g. `tests.test_cases`). This prevents using relative imports inside the `tests` directory (e.g., `from .utils import helper`).
   - Additionally, without `__init__.py` files in subdirectories, `unittest`'s discovery will not recurse into nested folders. If future milestones organize tests into directories like `tests/tier1/` and `tests/tier2/`, discovery will fail unless `__init__.py` files are present.
3. **Subprocess & Port Binding Challenges**:
   - The test lifecycle in `TEST_INFRA.md` relies on spawning `receiver.py` on port 8080. If another process is already using port 8080, the spawned process will fail to bind, and the test client might connect to the *wrong* (already running) server, causing false test outcomes.
   - If the test runner is aborted (e.g. Ctrl+C), the subprocesses may not be terminated, leaving orphaned background processes listening on port 8080.
   - If the spawned process writes heavily to `stderr` and it is not read concurrently with `stdout`, the OS pipe buffer can fill up, causing the server process to hang.

---

## 3. Caveats

- **No Target Under Test**: The `receiver/receiver.py` WebSocket server script has not been implemented yet. Thus, the subprocess spawning, WebSocket client connection retry loop, and real-time log capturing logic described in `TEST_INFRA.md` could not be dynamically verified.
- **Port Usage**: The analysis assumes the receiver server is bound to a single, static port (8080) as defined in the contracts.

---

## 4. Conclusion

### Review Summary

**Verdict**: APPROVE

The test infrastructure framework is set up correctly, conforms to the milestone SM1 specification, and the test runner executes successfully.

### Findings

#### [Minor] Finding 1: Lack of `top_level_dir` in `loader.discover`
- **What**: Discovered tests are loaded as top-level modules instead of package-level modules.
- **Where**: `tests/run_tests.py`, line 15:
  `suite = loader.discover(start_dir=os.path.join(project_root, 'tests'), pattern='test_*.py')`
- **Why**: Prevents the use of relative imports (e.g. `from .utils import x`) within the `tests` directory.
- **Suggestion**: Change to:
  `suite = loader.discover(start_dir=os.path.join(project_root, 'tests'), pattern='test_*.py', top_level_dir=project_root)`

#### [Minor] Finding 2: Missing `__init__.py` in the `tests/` directory
- **What**: No `__init__.py` file is present in `tests/`.
- **Where**: `tests/`
- **Why**: Standard Python `unittest` discovery requires subdirectories to contain `__init__.py` to be scanned recursively. If tests are split into subdirectories (e.g. `tests/tier1`), they will not be discovered.
- **Suggestion**: Create empty `__init__.py` files in `tests/` and any future subdirectories.

### Verified Claims

- **Sanity test execution** &rarr; verified via `python tests/run_tests.py` and `pytest tests/` &rarr; **PASS**
- **Robust working directory resolution** &rarr; verified by running `run_tests.py` from `C:\Users\devon` &rarr; **PASS**
- **Websockets dependency version** &rarr; verified via `python -c "import websockets; print(websockets.__version__)"` &rarr; **PASS (v14.2)**

### Coverage Gaps
- **Target Receiver Verification** — risk level: low (for SM1, but high for SM2) — recommendation: accept risk for SM1 as `receiver.py` is planned for M2/SM2.

---

## 5. Adversarial Review

### Challenge Summary

**Overall risk assessment**: MEDIUM

### Challenges

#### [High] Challenge 1: Port conflict / socket leakage
- **Assumption challenged**: The test runner assumes port 8080 is always available and can be bound by the spawned receiver script.
- **Attack scenario**: A previous crashed test run or a running instance of the receiver server keeps port 8080 bound. A new test run starts, spawns a new process (which fails to bind), but the test client connects to the *old* server instance. Stderr/stdout checks on the spawned process fail or capture incorrect data.
- **Blast radius**: Cascading test failures, false positives/negatives, and hung test runner sessions.
- **Mitigation**:
  1. The test runner/setup should check if port 8080 is in use before launching the server, or allow dynamic port configuration (e.g. passing `--port <port>` to `receiver.py`).
  2. The connection loop should check if the spawned subprocess has exited unexpectedly (e.g. `process.poll() is not None`) and abort immediately.

#### [Medium] Challenge 2: Orphaned subprocesses on interruption
- **Assumption challenged**: The test runner assumes `tearDown()` will always run and terminate the subprocess.
- **Attack scenario**: The test run is aborted mid-test via Ctrl+C, or the Python test runner process crashes. The spawned `receiver.py` processes remain active as background processes.
- **Blast radius**: Subsequent test runs will fail due to port 8080 being locked by the orphaned processes.
- **Mitigation**: Register exit handlers using `atexit` or signal handlers in `run_tests.py` to forcefully kill any spawned subprocesses on abrupt exit.

#### [Medium] Challenge 3: Pipe buffer deadlock
- **Assumption challenged**: The test runner assumes the subprocess will not block on writing logs.
- **Attack scenario**: The receiver process writes logs to both `stdout` and `stderr`. If the test client reads only `stdout` and leaves `stderr` unbuffered and unread, the `stderr` pipe buffer will eventually fill up (typically 4KB-64KB depending on OS), causing the subprocess to hang on a write call.
- **Blast radius**: The test case will hang and eventually time out.
- **Mitigation**: Redirect `stderr` to `stdout` (e.g. `stderr=subprocess.STDOUT` or `asyncio.subprocess.STDOUT`) when spawning the subprocess, or run a concurrent task to read and drain the `stderr` stream.

### Stress Test Results

- **Run tests from different Cwd** &rarr; Expected: runner resolves paths and succeeds &rarr; Actual: resolved successfully and executed sanity test &rarr; **PASS**
- **Pytest discovery integration** &rarr; Expected: pytest successfully discovers and runs test cases &rarr; Actual: 1 test passed &rarr; **PASS**

### Unchallenged Areas
- **Subprocess lifecycle and network interactions** — reason: `receiver/receiver.py` is not yet implemented, so dynamic subprocess management and socket lifecycle could not be stress-tested.

---

## 6. Verification Method

To verify the test infrastructure and runner:
1. Open a PowerShell/Cmd prompt in `c:\Development\Monolith`.
2. Run:
   ```powershell
   python tests/run_tests.py
   ```
3. Ensure the output shows that 1 test ran and passed successfully.
4. Run:
   ```powershell
   pytest tests/
   ```
5. Ensure that pytest similarly discovers and runs the 1 test successfully.
