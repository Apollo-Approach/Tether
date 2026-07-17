# Test Coverage Audit & Adversarial Gap Report - Keyboard Input, Key Mapping, and Unicode Handling

## 1. Executive Summary
As part of the Adversarial Hardening (Tier 5) Phase, a white-box audit was performed on the Keyboard Input, Key Mapping, and Unicode/Non-ASCII Handling components across the Android client (`MainActivity.kt`, `KeyMapper.kt`), the Python receiver (`receiver.py`), and the existing E2E test suite.

The audit revealed several critical and high-priority gaps, including:
- **WebSocket connection crashes** triggered by unpaired UTF-16 surrogates.
- **Missing modifier keys (Meta/Windows/Cmd)** and essential special keys.
- **Log injection and terminal escape sequence vulnerabilities** due to lack of output sanitization on the receiver.
- **Mismatched string length checks** between client-side Java/Kotlin (UTF-16) and server-side Python (UTF-8/Unicode code points).
- **Stateless keyboard event protocol** preventing modifier-key holding or combinations (e.g. Ctrl/Shift clicks).

A new adversarial integration test suite (`tests/test_keyboard_adversarial.py`) has been added to target and verify these vulnerabilities.

---

## 2. Discovered Gaps

### Gap 1: Unhandled Unpaired UTF-16 Surrogates (Connection Drop Vulnerability)
- **Component**: `receiver/receiver.py` (specifically `handle_client` logging)
- **Severity**: **HIGH**
- **Description**: The receiver reconfigures standard streams to UTF-8 to handle Emoji prints. However, it does not specify an error-handling surrogate policy (e.g., `errors='replace'`). When the JSON decoder receives an unpaired surrogate (such as `"\uD83D"`), it translates it into a Python surrogate character (`\ud83d`). When the receiver attempts to print this to `sys.stdout` (UTF-8), standard Python throws a `UnicodeEncodeError`. Since this exception is not caught within the client handler loop, the entire WebSocket connection handler task crashes, abruptly terminating the connection with internal error 1011.
- **Impact**: Attacking clients can crash their own (or potentially others) connection handler task simply by typing or pasting malformed Unicode/unpaired surrogates.
- **Remediation**:
  1. Wrap logs in `try-except UnicodeEncodeError` blocks or use a sanitization utility.
  2. Configure standard stream error handlers: `sys.stdout.reconfigure(encoding='utf-8', errors='replace')`.

### Gap 2: Missing Meta Modifier Key Mapping
- **Component**: `android/app/src/main/java/com/antigravity/remote/KeyMapper.kt`
- **Severity**: **MEDIUM**
- **Description**: The Compose `Key` class supports `Key.MetaLeft` and `Key.MetaRight` (representing the Win/Command keys). However, `KeyMapper.mapKey` fails to recognize them, returning `null`.
- **Impact**: Users cannot execute OS-level shortcuts on the receiver host that require the Win/Command key (e.g., Win+D to show desktop, Win+R to open Run).
- **Remediation**: Update `KeyMapper.mapKey` to map `Key.MetaLeft` and `Key.MetaRight` to `"Meta"` or `"Win"`.

### Gap 3: Missing Essential Special/Control Keys Mapping
- **Component**: `android/app/src/main/java/com/antigravity/remote/KeyMapper.kt`
- **Severity**: **MEDIUM**
- **Description**: The client mapper completely lacks mappings for standard special keys:
  - `Key.Tab` (navigation)
  - `Key.CapsLock` / `Key.NumLock` / `Key.ScrollLock` (toggles)
  - `Key.Insert`
  - `Key.Delete` (note that Backspace is mapped, but Delete is not!)
  - `Key.Home` / `Key.End` / `Key.PageUp` / `Key.PageDown`
  - `Key.PrintScreen`
  - `Key.F1` to `Key.F12` (function keys)
- **Impact**: Incomplete keyboard coverage. Users cannot navigate UI with Tab, delete text forward, or use function key shortcuts.
- **Remediation**: Expand `KeyMapper.mapKey` to cover the full set of standard desktop keys.

### Gap 4: Log Injection & Terminal Escape Sequence Vulnerability
- **Component**: `receiver/receiver.py` (specifically stdout/stderr printing)
- **Severity**: **MEDIUM**
- **Description**: The receiver logs the key value directly without sanitization: `print(f"[KEYBOARD_INPUT] key: {key}", flush=True)`. If a malicious client sends a key string containing terminal control characters (e.g. `\u0007` BEL or `\u001b[2J` ANSI Clear Screen), the receiver will output them directly to the terminal stdout.
- **Impact**: Allows attackers to trigger local terminal beeps, clear screen buffers, spoof log entries, or potentially exploit terminal emulator escape code vulnerabilities.
- **Remediation**: Sanitize key inputs before logging them (e.g. escaping non-printable characters or control sequences using `repr()` or a custom escaper).

### Gap 5: Mismatched String Length Validation (Java/Kotlin UTF-16 vs Python UTF-8)
- **Component**: `receiver/receiver.py` vs `android/.../MainActivity.kt`
- **Severity**: **LOW**
- **Description**: The receiver enforces a length limit of `len(key) > 100`. In Python, `len` counts Unicode code points. In Java/Kotlin, `length` counts UTF-16 code units (surrogate pairs count as 2). For example, a sequence of 10 family ZWJ emojis (`👨‍👩‍👧‍👦`) has a Java string length of 110 but a Python string length of 70. 
- **Impact**: Visually short emoji strings can exceed Java/Kotlin length limits or be parsed/clamped inconsistently between client and server, causing unexpected validation errors or mismatches.
- **Remediation**: Ensure length validation counts actual Unicode code points on both client and server sides consistently.

### Gap 6: Stateless Keyboard Event Protocol (No KeyUp / KeyDown Separation)
- **Component**: Protocol / Interface Contract
- **Severity**: **MEDIUM**
- **Description**: The JSON protocol only transmits `keyboard_input` with a `key` value, which is sent on client KeyDown. There are no separate events for KeyUp/KeyDown, nor is there a stateful tracking mechanism on the server.
- **Impact**: It is impossible to hold down a modifier key (Shift, Ctrl, Alt) while executing other actions (e.g., mouse clicks for multi-select, mouse drags, or holding down a key for continuous movement).
- **Remediation**: Redesign the protocol to support stateful keyboard inputs:
  - `{"event": "key_down", "key": "..."}`
  - `{"event": "key_up", "key": "..."}`
  
### Gap 7: Inefficient Text Pasting
- **Component**: `android/app/src/main/java/com/antigravity/remote/MainActivity.kt`
- **Severity**: **LOW**
- **Description**: When text is entered or pasted into the input field, the client splits it character-by-character using `splitIntoUnicodeCharacters` and sends each character as a separate WebSocket event.
- **Impact**: Significant network and server processing overhead for pasted sentences or paragraphs.
- **Remediation**: Introduce a text chunk event type (e.g. `{"event": "text_input", "text": "..."}`) to send whole strings at once.

---

## 3. Adversarial Test Coverage

A new test file `tests/test_keyboard_adversarial.py` was created to empirically verify the vulnerabilities and check receiver behavior. It covers:
1. `test_unpaired_surrogate_utf16`: Verified that sending an unpaired surrogate `\uD83D` crashes the receiver client task with `UnicodeEncodeError` and abruptly drops the connection.
2. `test_zwj_joined_emoji_length_rejection`: Verified Python length checks on complex ZWJ emojis.
3. `test_control_character_log_injection`: Verified that BEL and ANSI escape codes are written raw to stdout without sanitization.
4. `test_null_key` & `test_boolean_key`: Verified proper rejection of invalid JSON value types.
