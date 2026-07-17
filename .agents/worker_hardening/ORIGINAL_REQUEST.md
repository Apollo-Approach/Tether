## 2026-07-15T03:00:37Z
You are a Worker subagent tasked with implementing final hardening fixes to address bugs identified by the Challengers:

Your working directory is: c:\Development\Monolith\.agents\worker_hardening\

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Please do the following tasks:
1. Fix UnicodeEncodeError on Windows stdout/stderr in `receiver/receiver.py`:
   - In `main()` of `receiver/receiver.py`, reconfigure `sys.stdout` and `sys.stderr` to use UTF-8 encoding using `sys.stdout.reconfigure(encoding='utf-8')` and `sys.stderr.reconfigure(encoding='utf-8')` to prevent crashes when printing emojis or other non-ASCII characters.

2. Fix Subprocess Leak in Test TearDown:
   - In `tests/test_cases.py`, `tests/test_adversarial.py`, and `tests/test_stress.py`, modify `asyncTearDown` to ensure the receiver subprocess is *always* terminated and waited on, even if closing the websocket client raises an exception. Wrap the websocket close in a try-except block, and use a try-finally or try-except-finally block to ensure that the process termination and `process.wait()` executes under all circumstances.

3. Relax Timeouts for CPU Stress:
   - In `tests/test_cases.py`, `tests/test_adversarial.py`, and `tests/test_stress.py`:
     - Increase the startup process log readline timeout from `5.0s` to `15.0s`.
     - Increase the test log read assertion timeout from `1.0s` to `3.0s` in `send_and_assert_log` and `send_raw_and_assert_err` to prevent timeouts when running under simulated CPU load.

4. Run all E2E and stress tests:
   - Run `python tests/run_tests.py` and `python -m unittest tests/test_stress.py` to ensure all tests pass cleanly.

Deliver a handoff report with details of your changes and test execution logs.
