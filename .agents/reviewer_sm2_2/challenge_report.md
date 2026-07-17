## Challenge Summary

**Overall risk assessment**: MEDIUM

## Challenges

### Medium Challenge 1: Lack of Input Sanitization/Clamping for Coordinates

- **Assumption challenged**: The coordinate delta `dx` and `dy` are always reasonable offsets.
- **Attack scenario**: An attacker or malfunctioning client sends extremely large delta values (e.g. `dx: 1e9, dy: -1e9`).
- **Blast radius**: While the mock receiver handles this by printing it, real OS emulation (e.g., with PyAutoGUI or Windows API calls) might raise an out-of-bounds exception, crash, or move the cursor to an unrecoverable position.
- **Mitigation**: Add bounds checking to clamp `dx` and `dy` to reasonable limits before logging or processing.

### Medium Challenge 2: Long Key Strings / Memory Denial of Service

- **Assumption challenged**: Keyboard keys are single characters or standard control strings.
- **Attack scenario**: A client sends a massive key payload (e.g., millions of characters).
- **Blast radius**: Standard Python string operations or stdout flushing on extremely long strings could consume significant CPU/memory, causing a denial of service. (Although websockets limits max frame sizes to some extent, it's safer to have application-level checks).
- **Mitigation**: Enforce a maximum key string length (e.g., 20 characters for modifiers/special keys, or 1 character for standard typing).

### Low Challenge 3: Empty Key Input Accepted

- **Assumption challenged**: Keyboard inputs always contain a key name.
- **Attack scenario**: Sending `{"event": "keyboard_input", "key": ""}`.
- **Blast radius**: The server accepts and prints `[KEYBOARD_INPUT] key: `, which could result in empty keystroke emulation attempts in non-mock mode.
- **Mitigation**: Reject empty key strings in validation.

## Stress Test Results

- **Rapid client connections** -> 5 concurrent sessions -> pass
- **Connection drops** -> Client dropped abruptly and server remained healthy -> pass
- **Malformed JSON streams** -> Received malformed payloads, printed errors, and stayed alive -> pass
- **Massive payload size** -> Checked 2MB payload, websockets connection closed, server recovered -> pass

## Unchallenged Areas

- **Host-level OS input emulation** — reason not challenged: Emulation is bypassed via mock mode in tests and unimplemented in the receiver.
