# Handoff Report - Communication Protocol Design (Explorer 2)

## 1. Observation
We observed and inspected the interface contracts in `PROJECT.md`, the WebSocket server implementation in `receiver/receiver.py`, and the test suites under `tests/`.

### A. Interface Contracts in `PROJECT.md`
`PROJECT.md` (lines 26-60) defines the contracts as follows:
```markdown
26: ## Interface Contracts
27: ### Android Client ↔ Receiver Server (WebSocket JSON Protocol)
...
30: #### 1. Trackpad Move Event
31: - Client sends relative mouse coordinates (dx, dy).
...
35:   "event": "mouse_move",
36:   "dx": 15.5,
37:   "dy": -10.2
...
41: #### 2. Mouse Click Event
42: - Client sends mouse click action.
...
45:   "event": "mouse_click",
46:   "button": "left" | "right" | "middle"
...
51: #### 3. Keyboard Input Event
52: - Client sends keystrokes or text chunks.
...
55:   "event": "keyboard_input",
56:   "key": "a" | "Enter" | "Backspace" | "Shift"
```

### B. Validation Logic in `receiver/receiver.py`
In `receiver/receiver.py` (lines 25-88), the validation is structured:
```python
25:     try:
26:         async for message in websocket:
27:             try:
28:                 data = json.loads(message)
29:             except (json.JSONDecodeError, UnicodeDecodeError):
30:                 print("Error: Malformed JSON payload received", file=sys.stderr)
31:                 continue
32:             
33:             if not isinstance(data, dict):
34:                 print("Error: Invalid payload format, expected JSON object", file=sys.stderr)
35:                 continue
36:             
37:             event = data.get("event")
38:             if not event:
39:                 print("Error: Missing event type in payload", file=sys.stderr)
40:                 continue
41:             
42:             if event == "mouse_move":
43:                 dx = data.get("dx")
44:                 dy = data.get("dy")
45:                 if dx is None or dy is None:
46:                     print("Error: Missing coordinates in mouse_move event", file=sys.stderr)
47:                     continue
48:                 if (not isinstance(dx, (int, float)) or isinstance(dx, bool) or
49:                     not isinstance(dy, (int, float)) or isinstance(dy, bool)):
50:                     print("Error: Invalid coordinates type in mouse_move event", file=sys.stderr)
51:                     continue
52:                 if not math.isfinite(dx) or not math.isfinite(dy):
53:                     print("Error: Invalid coordinates type in mouse_move event", file=sys.stderr)
54:                     continue
55:                 
56:                 # Clamp dx and dy to [-2000.0, 2000.0]
57:                 dx = max(-2000.0, min(2000.0, float(dx)))
58:                 dy = max(-2000.0, min(2000.0, float(dy)))
59:                 
60:                 # Print event to stdout for verification
61:                 print(f"[MOUSE_MOVE] dx: {dx}, dy: {dy}", flush=True)
62:                 
63:             elif event == "mouse_click":
64:                 button = data.get("button")
65:                 if button is None:
66:                     print("Error: Missing button in mouse_click event", file=sys.stderr)
67:                     continue
68:                 if not isinstance(button, str) or button not in ("left", "right", "middle"):
69:                     print("Error: Invalid button type or value in mouse_click event", file=sys.stderr)
70:                     continue
71:                 print(f"[MOUSE_CLICK] button: {button}", flush=True)
72:                 
73:             elif event == "keyboard_input":
74:                 key = data.get("key")
75:                 if key is None:
76:                     print("Error: Missing key in keyboard_input event", file=sys.stderr)
77:                     continue
78:                 if not isinstance(key, str):
79:                     print("Error: Invalid key type in keyboard_input event", file=sys.stderr)
80:                     continue
81:                 if key == "" or len(key) > 100:
82:                     print("Error: Invalid key type or value in keyboard_input event", file=sys.stderr)
83:                     continue
84:                 print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
```

### C. Test Executions
1. Spawning `python tests/run_tests.py` ran 69 tests across happy paths, boundary conditions, cross-feature interactions, real-world workflows, UTF-8 resilience, and NaN/Infinity values:
   ```
   Ran 69 tests in 103.878s
   OK
   ```
2. Running `python tests/verify_zombies.py` confirmed clean termination of spawned server processes on setup connection failures and timeout exceptions:
   ```
   ALL ZOMBIE TESTS PASSED.
   ```

---

## 2. Logic Chain
We analyzed each of the required validation categories:
1. **Bounds Checking**:
   - `mouse_move`: Coordinates `dx` and `dy` are clamped using `max(-2000.0, min(2000.0, float(...)))` (lines 57-58).
   - `mouse_click`: `button` values are verified using `button not in ("left", "right", "middle")` (line 68).
   - `keyboard_input`: `key` length is restricted using `len(key) > 100` (line 81).
2. **Empty Inputs**:
   - Checked via `is None` constraints for coordinates, buttons, and keys (lines 45, 65, 75).
   - Keys are also checked for empty string (`key == ""`, line 81).
3. **Type Handling**:
   - JSON format is explicitly verified as a dictionary (`isinstance(data, dict)`, line 33).
   - For `mouse_move`, `isinstance(dx, (int, float))` and `not isinstance(dx, bool)` (which is necessary because in Python `isinstance(True, int)` is True) is used (lines 48-49).
   - Floats are checked with `math.isfinite(dx)` and `math.isfinite(dy)` to block `NaN` and `Infinity` (line 52).
   - Buttons and keys check `isinstance(..., str)` (lines 68, 78).
4. **Malformed Inputs**:
   - Invalid JSON syntax/payload structure and invalid UTF-8 bytes trigger `JSONDecodeError` or `UnicodeDecodeError` (line 29), printing an error and continuing instead of crashing the server.
   - Missing or unknown event types are logged to stderr (lines 38, 87) and ignored safely.

### Discrepancy Found
`PROJECT.md` defines `key` in `keyboard_input` to be `"a" | "Enter" | "Backspace" | "Shift"`. However, `receiver/receiver.py` accepts *any* string of length 1 to 100. This is actually a positive extension, as the specification mentions "Client sends keystrokes or text chunks" and `tests/test_non_ascii.py` verifies sending emojis (e.g., `"🚀"`), but it constitutes a discrepancy with the strict enum definition in the spec file.

---

## 3. Caveats
- The receiver was investigated only in dry-run/mock mode (`--mock` flag set), which disables actual OS-level emulation calls (e.g., using `pyautogui` or other OS-specific modules). The implementation of actual emulation logic is outside the scope of this verification.
- The investigation assumes that the Android app client is developed to handle the broader string input protocol supported by the receiver rather than limiting itself strictly to the four enum values shown in `PROJECT.md`.

---

## 4. Conclusion
The current WebSocket receiver implementation in `receiver/receiver.py` is robust and fully covers the necessary validation logic (type checks, bounds clamping, empty and malformed input handling) without risk of crashes from malicious or invalid JSON payloads.

Apart from the key format discrepancy (where the receiver allows any string of length 1-100 instead of a strict enum of four keys), the implementation aligns with the specification. The discrepancy allows text chunks and emoji characters, which is a desirable feature.

---

## 5. Verification Method
To verify these findings independently:
1. Run the test discovery script:
   ```powershell
   python tests/run_tests.py
   ```
   All 69 test cases should report `OK`.
2. Run the zombie process clean-up verification:
   ```powershell
   python tests/verify_zombies.py
   ```
   Should report `ALL ZOMBIE TESTS PASSED`.
3. Invalidation conditions: Any test failures or uncaught exceptions leading to receiver crashes on malformed inputs or empty requests.
