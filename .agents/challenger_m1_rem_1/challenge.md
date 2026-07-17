# Adversarial Challenge Report: M1 Remediation

## Challenge Summary

**Overall risk assessment**: MEDIUM

*Invalid UTF-8 bytes and non-finite coordinates (Infinity/NaN) are successfully caught and ignored by the Python receiver without crashing the server process. However, a system-dependent crash was discovered during testing when valid non-ASCII characters (e.g., emojis like `🚀`) are sent to the keyboard handler in a redirected stdout environment on Windows.*

---

## Challenges

### [Medium] Challenge 1: Non-ASCII Character Printing Crash on Windows (UnicodeEncodeError)
- **Assumption challenged**: The print statement `print(f"[KEYBOARD_INPUT] key: {key}", flush=True)` is completely safe to run in all environments.
- **Attack scenario**: A client sends a valid UTF-8 payload containing a non-ASCII key, such as a rocket emoji `🚀` (`\U0001f680`), in a redirected stdout environment on Windows (e.g., running under tests or a CI process).
- **Blast radius**: The connection handler throws a `UnicodeEncodeError` when trying to encode the emoji to `cp1252` (Windows ANSI) for the stdout pipe. This crashes the websocket connection handler for that client, causing the connection to drop abruptly.
- **Mitigation**: Reconfigure the standard output streams to use `utf-8` encoding at the start of the receiver script:
  ```python
  if sys.platform == "win32":
      sys.stdout.reconfigure(encoding='utf-8')
      sys.stderr.reconfigure(encoding='utf-8')
  ```

### [Low] Challenge 2: Transient Test Process Spawning Timeout
- **Assumption challenged**: Spawning a new Python process for each test case is fast and reliable.
- **Attack scenario**: When the system is under CPU load, the `asyncSetUp` hook of `unittest.IsolatedAsyncioTestCase` can time out (limit `5.0s`) while waiting for the spawned `receiver.py` process to write its startup signature to stdout.
- **Blast radius**: Test cases fail with `RuntimeError: Failed to read server startup log in time`.
- **Mitigation**: Increase the startup timeout from `5.0` seconds to `10.0` or `15.0` seconds in the test suite setup functions.

---

## Stress Test Results

| Scenario / Input | Expected Behavior | Actual Behavior | Pass/Fail |
|---|---|---|---|
| **Invalid UTF-8 (Binary Frame)** <br>Sending `b'\xff\xff'` | Caught as JSON/Unicode decode error; server prints warning to stderr and continues. | Caught `UnicodeDecodeError`, printed `Error: Malformed JSON payload received` on stderr. | **PASS** |
| **Invalid UTF-8 (Text Frame)** <br>Sending raw `b'\xff\xff'` in text frame | WebSocket protocol violation caught; connection closed, server stays alive. | Server closed connection with code 1007. Server accepted new connections successfully. | **PASS** |
| **NaN Coordinate Literal** <br>`{"event": "mouse_move", "dx": NaN, "dy": 5.0}` | Caught by `math.isfinite()` check; server logs error and ignores event. | Caught by `math.isfinite()`, printed error to stderr, stayed alive. | **PASS** |
| **Infinity Coordinate Literal** <br>`{"event": "mouse_move", "dx": 5.0, "dy": Infinity}` | Caught by `math.isfinite()` check; server logs error and ignores event. | Caught by `math.isfinite()`, printed error to stderr, stayed alive. | **PASS** |
| **Overflow Coordinate** <br>`{"event": "mouse_move", "dx": 1e1000, "dy": 5.0}` | Parsed as `inf` and caught by `math.isfinite()`. | Caught by `math.isfinite()`, printed error to stderr, stayed alive. | **PASS** |
| **Valid Non-ASCII (Emoji)** <br>`{"event": "keyboard_input", "key": "🚀"}` | Logged to stdout as key event. | Crashed connection handler with `UnicodeEncodeError` ('charmap' codec can't encode). | **FAIL (Windows)** |

---

## Unchallenged Areas

- **OS Emulation Hooks** — OS-level mouse and keyboard emulation (e.g. `pyautogui` or `pynput` dependencies) was not tested because the tests run in `--mock` mode to prevent disrupting host environment.
