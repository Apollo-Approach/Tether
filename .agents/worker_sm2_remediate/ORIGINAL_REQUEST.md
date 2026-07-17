## 2026-07-14T22:20:20-04:00
You are a Worker subagent tasked with implementing dynamic port allocation, port discovery, and schema/type validation in receiver/receiver.py, and updating the tests to use dynamic port allocation and assert the validation rules.

Your working directory is: c:\Development\Monolith\.agents\worker_sm2_remediate\

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Please do the following:
1. In `receiver/receiver.py`:
   - Retrieve the actual listening port from the websockets server (e.g., `server.sockets[0].getsockname()[1]`) and print the startup log message with the actual port: `Server listening on ws://{host}:{actual_port}`.
   - Implement schema and type validation in `handle_client(websocket)`:
     - Verify that the parsed JSON payload is a dictionary (`isinstance(data, dict)`). If not, log `Error: Invalid payload format, expected JSON object` to stderr and continue.
     - For `mouse_move`: check that `dx` and `dy` are numeric types (`int` or `float`, but not `bool`). If not, log `Error: Invalid coordinates type in mouse_move event` to stderr.
     - For `mouse_click`: check that `button` is a string and is one of "left", "right", "middle". If not, log `Error: Invalid button type or value in mouse_click event` to stderr.
     - For `keyboard_input`: check that `key` is a string. If not, log `Error: Invalid key type in keyboard_input event` to stderr.

2. In `tests/test_cases.py`, `tests/test_adversarial.py`, and `tests/stress_tests.py`:
   - Update the setup code to spawn the receiver with `--port 0`.
   - Read the startup stdout line from the receiver subprocess, parse out the dynamically allocated port, and use that port to connect the websockets client.
   - In `tests/test_adversarial.py`, update `test_mouse_move_invalid_types`, `test_mouse_click_invalid_button_type`, and `test_keyboard_input_invalid_key_type` to assert that they print the correct validation errors to stderr.

3. Run the test runner:
   - Run `python tests/run_tests.py` and ensure all tests pass.
   - Provide the run command and its execution output in your handoff report.

Write your final handoff report containing the list of modified files, code changes, and test execution results.
