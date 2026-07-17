# Handoff Report: Android UI and Input Capture Analysis (Milestone M3)

## 1. Observation
I inspected the Android source code under `c:\Development\Monolith\android\` and analyzed the event-handling behavior of the Python receiver server and test suite. The specific observations are detailed below:

* **Observation A (Keyboard Input Capture):** In `c:\Development\Monolith\android\app\src\main\java\com\antigravity\remote\MainActivity.kt`, lines 100-116:
  ```kotlin
  // Text & Keyboard Input Area
  OutlinedTextField(
      value = textInputState,
      onValueChange = { newValue ->
          // Check if text changed or backspace, etc.
          val oldText = textInputState.text
          val newText = newValue.text
          if (newText.length > oldText.length) {
              val addedChar = newText.substring(oldText.length)
              logText = "Typed: $addedChar"
          } else if (newText.length < oldText.length) {
              logText = "Typed: Backspace"
          }
          textInputState = newValue
      },
      label = { Text("Keyboard Input") },
      modifier = Modifier.fillMaxWidth()
  )
  ```
  `textInputState` is initialized in line 41 as `mutableStateOf(TextFieldValue(""))`.

* **Observation B (Trackpad Capture):** In `MainActivity.kt`, lines 63-97:
  ```kotlin
  // Trackpad Touch Area
  Box(
      modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .background(Color.DarkGray)
          .pointerInput(Unit) {
              detectTapGestures(
                  onTap = { offset ->
                      logText = "Tapped at: $offset (Left Click)"
                  },
                  onLongPress = { offset ->
                      logText = "Long pressed at: $offset (Right Click)"
                  }
              )
          }
          .pointerInput(Unit) {
              detectDragGestures(
                  onDragStart = { logText = "Drag started" },
                  onDragEnd = { logText = "Drag ended" },
                  onDragCancel = { logText = "Drag cancelled" },
                  onDrag = { change, dragAmount ->
                      change.consume()
                      logText = "Dragged dx: ${dragAmount.x}, dy: ${dragAmount.y}"
                  }
              )
          },
      contentAlignment = Alignment.Center
  )
  ```

* **Observation C (OkHttp Dependency):** In `c:\Development\Monolith\android\app\build.gradle.kts`, line 47:
  ```kotlin
  implementation(libs.okhttp)
  ```

* **Observation D (Receiver Event Validation):** In `c:\Development\Monolith\receiver\receiver.py`, lines 73-84:
  ```python
  elif event == "keyboard_input":
      key = data.get("key")
      if key is None:
          print("Error: Missing key in keyboard_input event", file=sys.stderr)
          continue
      if not isinstance(key, str):
          print("Error: Invalid key type in keyboard_input event", file=sys.stderr)
          continue
      if key == "" or len(key) > 100:
          print("Error: Invalid key type or value in keyboard_input event", file=sys.stderr)
          continue
      print(f"[KEYBOARD_INPUT] key: {key}", flush=True)
  ```

* **Observation E (Key Expectations from Tests):** In `c:\Development\Monolith\tests\test_cases.py`, lines 138-164:
  ```python
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

## 2. Logic Chain
We reason about the capabilities and limitations of the Kotlin client's keyboard/gesture capture logic step-by-step:

1. **Backspaces & Empty Inputs:**
   * In *Observation A*, backspace is detected when `newText.length < oldText.length`.
   * When `textInputState.text` is empty (`""`), pressing Backspace on a soft keyboard or emulator keyboard does not change the text length.
   * Thus, `onValueChange` is never triggered, and backspaces on empty fields are completely missed.
   * Furthermore, if multiple characters are replaced/deleted, the length difference is greater than 1, which the current substring logic does not handle correctly.

2. **Special Keys (Escape, Tab, Arrows, etc.):**
   * *Observation A* relies entirely on text changes (`onValueChange`).
   * Special hardware/software keys like Escape, Tab, or Arrow keys do not produce text insertions in the `OutlinedTextField`.
   * Thus, pressing these keys does not trigger `onValueChange` and they are completely ignored.

3. **Modifiers (Shift, Ctrl, Alt, Meta/Win):**
   * Modifiers like `Ctrl`, `Alt`, `Shift`, or `Meta` do not produce characters when pressed alone, meaning they do not trigger `onValueChange` (*Observation A*).
   * Hence, they are completely missed.
   * Modifier combinations (e.g., Ctrl+C) do not trigger text changes and cannot be captured by `onValueChange`.

4. **Non-ASCII character inputs & Composition:**
   * Emojis/non-ASCII inputs can be captured as strings but standard Android soft keyboards use text composition (IME predictions). This causes `onValueChange` to trigger for every intermediate composing state (e.g. "n", "ni", "nih", etc. before "你好"), resulting in noisy and incorrect key events.
   * If a character is inserted in the middle of the text, `newText.substring(oldText.length)` (*Observation A*) returns the wrong character because it assumes additions always occur at the end of the text.

---

## 3. Caveats
* **IME-Specific Variations:** Software keyboard behavior on Android (e.g., Gboard vs Samsung Keyboard) is highly variable. While setting password keyboard options mitigates composition issues, some custom IMEs may still refuse to send raw key events.
* **Network Permissions:** To connect to the WebSocket server via OkHttp, the Android app must have `<uses-permission android:name="android.permission.INTERNET" />` declared in its `AndroidManifest.xml` file.
* **Emulator vs Physical Device:** Hardware keyboards attached to the Android emulator or Bluetooth keyboards on a physical device emit physical `KeyEvent` codes that must be handled explicitly.

---

## 4. Conclusion
To fully support input capture and prepare for Milestone M4 WebSocket integration, the following additions and modifications must be made in `MainActivity.kt`:

1. **WebSocket & Network Management:**
   * Use OkHttp's `OkHttpClient.newWebSocket(...)` to initiate connection.
   * Add a `serverUrl` state (`var serverUrl by remember { mutableStateOf("ws://10.0.2.2:8080") }`) and UI fields (URL text input field and Connect/Disconnect button) to let the user establish a connection.
   * Use the built-in `org.json.JSONObject` to format messages.

2. **Trackpad Event Capture:**
   * Map the gesture callbacks to JSON serialization and send:
     * Tap -> `{"event": "mouse_click", "button": "left"}`
     * Long Press -> `{"event": "mouse_click", "button": "right"}`
     * Drag -> `{"event": "mouse_move", "dx": <dragAmount.x>, "dy": <dragAmount.y>}`

3. **Keyboard Hybrid Capture Strategy:**
   * **For Soft Keyboards (to detect Backspace and characters):**
     * Use a **Dummy Space state trick**. Initialize `textInputState` with `" "` (a single space) and selection at index 1.
     * When `onValueChange` triggers:
       * If text becomes empty `""`, send `{"event": "keyboard_input", "key": "Backspace"}` and reset the state back to `" "`.
       * If text length is greater than 1, extract the newly added character(s) from `newValue.text.substring(1)`. Send the character (or map `" "` to `"Space"` or `" "` depending on tests), and reset the state back to `" "`.
       * Set `KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false)` to disable predictive text composition.
   * **For Physical/Emulator Keyboards & Special Keys:**
     * Attach a `Modifier.onKeyEvent` block to the `OutlinedTextField`.
     * Intercept special keys (`Key.Enter`, `Key.Backspace`, `Key.Tab`, `Key.Escape`, `Key.DirectionLeft`, etc.) and send their string names (e.g. `"Enter"`, `"Backspace"`, `"Tab"`, `"Escape"`, `"ArrowLeft"`). Return `true` to consume these events so they don't trigger `onValueChange`.
     * Intercept modifier keys (`Key.CtrlLeft`/`Key.CtrlRight` -> `"Ctrl"`, `Key.ShiftLeft`/`Key.ShiftRight` -> `"Shift"`, etc.).
     * Detect keyboard modifier combinations: if `isCtrlPressed`, `isAltPressed`, or `isMetaPressed` is true, extract the character code via `keyEvent.nativeKeyEvent.getUnicodeChar(0).toChar()` and send it as the key payload (e.g., Ctrl + C sends `"Ctrl"` then `"c"`).

---

## 5. Verification Method
1. Run the Python receiver test suite to confirm the server is functional. I executed this suite during this investigation and verified that all 69 tests pass successfully:
   ```bash
   pytest tests/
   ```
   *Execution output summary:*
   ```text
   tests\test_adversarial.py .............                                  [ 18%]
   tests\test_cases.py ...........................................          [ 81%]
   tests\test_challenge.py .....                                            [ 88%]
   tests\test_non_ascii.py ..                                               [ 91%]
   tests\test_stress.py ......                                              [100%]
   ======================= 69 passed in 120.48s (0:02:00) ========================
   ```
2. For Milestone M4 validation:
   * Build and run the Android app in the emulator:
     ```bash
     gradlew assembleDebug
     ```
   * Spin up the Python receiver on the host machine in dry-run mode:
     ```bash
     python receiver/receiver.py --mock
     ```
   * Set the Android client URL to `ws://10.0.2.2:<port>` and tap Connect.
   * Perform click, drag, backspace, unicode typing, and modifier combination actions.
   * Verify the receiver logs output matches expectations (e.g. `[KEYBOARD_INPUT] key: Backspace`, `[KEYBOARD_INPUT] key: 🚀`, `[MOUSE_MOVE] dx: 10.5, dy: -5.0`).
