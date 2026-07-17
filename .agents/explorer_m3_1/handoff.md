# Handoff Report: Android UI and Input Capture Analysis (M3)

This report details the read-only investigation of the Android remote control application for Milestone M3. It analyzes the current input capture logic, identifies core defects, and provides precise code designs to correct them.

---

## 1. Observation

Direct observations from the codebase are listed below:

### A. Touch/Gesture Capture (MainActivity.kt, Lines 64-91)
```kotlin
64:         Box(
65:             modifier = Modifier
66:                 .fillMaxWidth()
67:                 .weight(1f)
68:                 .background(Color.DarkGray)
69:                 .pointerInput(Unit) {
70:                     detectTapGestures(
71:                         onTap = { offset ->
72:                             logText = "Tapped at: $offset (Left Click)"
73:                         },
74:                         onLongPress = { offset ->
75:                             logText = "Long pressed at: $offset (Right Click)"
76:                         }
77:                     )
78:                 }
79:                 .pointerInput(Unit) {
80:                     detectDragGestures(
81:                         onDragStart = { logText = "Drag started" },
82:                         onDragEnd = { logText = "Drag ended" },
83:                         onDragCancel = { logText = "Drag cancelled" },
84:                         onDrag = { change, dragAmount ->
85:                             change.consume()
86:                             logText = "Dragged dx: ${dragAmount.x}, dy: ${dragAmount.y}"
87:                         }
88:                     )
89:                 },
90:             contentAlignment = Alignment.Center
91:         )
```

### B. Keyboard Input Capture (MainActivity.kt, Lines 100-116)
```kotlin
100:         OutlinedTextField(
101:             value = textInputState,
102:             onValueChange = { newValue ->
103:                 // Check if text changed or backspace, etc.
104:                 val oldText = textInputState.text
105:                 val newText = newValue.text
106:                 if (newText.length > oldText.length) {
107:                     val addedChar = newText.substring(oldText.length)
108:                     logText = "Typed: $addedChar"
109:                 } else if (newText.length < oldText.length) {
110:                     logText = "Typed: Backspace"
111:                 }
112:                 textInputState = newValue
113:             },
114:             label = { Text("Keyboard Input") },
115:             modifier = Modifier.fillMaxWidth()
116:         )
```

### C. Network Protocol & Missing Transmission
- In `MainActivity.kt` (Lines 1-137), there is no implementation of a WebSocket network client, connection logic, or data serialization to the server. The connection status state variable `connectionStatus` is hardcoded to `"Disconnected"` (Line 40), and no actual transmissions are executed in the callbacks.
- The Python WebSocket server `receiver/receiver.py` is fully operational and passes all 69 E2E test cases, validating the protocol contracts defined in `PROJECT.md`.

---

## 2. Logic Chain

The logic tracing from observations to conclusions is outlined below:

### A. Gesture Detector Conflict
1. **Observation 1-A**: Two separate `pointerInput` modifiers are chained together on the trackpad `Box`: `detectTapGestures` (lines 69-78) followed by `detectDragGestures` (lines 79-89).
2. **Framework Behavior**: In Jetpack Compose, multiple pointer input modifiers on the same component execute sequentially (outer to inner). If the outer gesture detector (`detectTapGestures`) intercepts the down event to check for a tap/long-press, it processes/consumes the down event.
3. **Implication**: The inner gesture detector (`detectDragGestures`) requires an unconsumed pointer down event to initiate tracking (based on the default parameter `requireUnconsumed = true` in Compose's underlying `awaitDownAndSlop`). Because `detectTapGestures` processes the down event, the drag gesture detector fails to start.
4. **Conclusion**: Chaining these detectors is a defect; they will conflict, resulting in unresponsive or highly buggy drag/drag-start detection. Taps and drags must be unified in a single custom gesture detector.

### B. Broken Text Diffing & Unbounded Growth
1. **Observation 1-B**: `onValueChange` computes the input changes via length comparison: `newText.substring(oldText.length)` (line 107) for typing and length reduction for backspace (line 109).
2. **Issue 1 (Cursor Insertion)**: If the cursor is placed in the middle of a string (e.g. inserting `'x'` into `"abc"` to get `"axbc"`), the expression `newText.substring(oldText.length)` resolves to `newText.substring(3)` which returns `"c"`, not `"x"`.
3. **Issue 2 (Unbounded Text Growth)**: The state `textInputState` is never cleared or reset (it is updated directly: `textInputState = newValue` at line 112). The text field will grow indefinitely as the user types.
4. **Issue 3 (Reset Loop)**: If the state is reset to `""` after each character to prevent growth, the next invocation of `onValueChange` receives `newValue.text` as `""` and `oldText` as the typed character. Since `newText.length < oldText.length`, it triggers a false `"Backspace"` event.
5. **Issue 4 (Special Keys)**: When the user presses Enter, the keyboard adds `"\n"` to the text. The current diffing logs `"Typed: \n"`, whereas the backend contract expects `"Enter"` (Observation 1-C).
6. **Conclusion**: The current soft keyboard input capture is broken. An IME-resettable state with a placeholder value (e.g. `" "`) and a programmatic resetting lock is required.

---

## 3. Caveats

- **Hardware vs Soft Keyboards**: Physical/hardware keyboard key events (e.g. modifier keys like `Ctrl`, `Shift`, `Alt`, and navigation keys like arrow keys) are not processed through IME/`onValueChange`. A separate `onKeyEvent` or `onPreviewKeyEvent` modifier is required on the text field to capture these events.
- **WebSocket Integration**: While the Android client currently lacks WebSocket networking (which is correct as per the project plan, since integration is scheduled for Milestone M4), the event capture callbacks must be designed to eventually pass their events to a network helper.

---

## 4. Conclusion & Proposed Code Modifications

The UI layout in `MainActivity.kt` captures gestures and text inputs incorrectly. To meet the requirements of Milestone M3, the following modifications are recommended for implementation:

### Proposed Modification 1: Custom Unified Gesture Detector
Replace the chained `.pointerInput` modifiers with a single custom gesture detector using `awaitPointerEventScope` to cleanly separate taps, long presses, and drags.

```kotlin
// Import additions:
// import androidx.compose.foundation.gestures.awaitFirstDown
// import androidx.compose.ui.input.pointer.positionChange
// import kotlinx.coroutines.delay
// import kotlinx.coroutines.coroutineScope

.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragTriggered = false
            var isLongPress = false
            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
            
            // Spawn a coroutine to check for long press
            val longPressJob = launch {
                delay(longPressTimeout)
                isLongPress = true
                logText = "Long pressed at: ${down.position} (Right Click)"
                // TODO: Send WebSocket event: {"event": "mouse_click", "button": "right"}
            }
            
            val pointerId = down.id
            var totalDrag = androidx.compose.ui.geometry.Offset.Zero
            
            do {
                val event = awaitPointerEvent()
                val dragChange = event.changes.firstOrNull { it.id == pointerId }
                
                if (dragChange != null) {
                    if (dragChange.pressed) {
                        val positionChange = dragChange.positionChange()
                        totalDrag += positionChange
                        
                        if (totalDrag.getDistance() > viewConfiguration.touchSlop) {
                            longPressJob.cancel() // Cancel long press if movement starts
                            
                            if (!dragTriggered) {
                                dragTriggered = true
                                logText = "Drag started"
                            }
                            
                            dragChange.consume()
                            logText = "Dragged dx: ${positionChange.x}, dy: ${positionChange.y}"
                            // TODO: Send WebSocket event: {"event": "mouse_move", "dx": positionChange.x, "dy": positionChange.y}
                        }
                    } else {
                        // Pointer released
                        longPressJob.cancel()
                        if (dragTriggered) {
                            logText = "Drag ended"
                        } else if (!isLongPress) {
                            logText = "Tapped at: ${dragChange.position} (Left Click)"
                            // TODO: Send WebSocket event: {"event": "mouse_click", "button": "left"}
                        }
                    }
                }
            } while (dragChange?.pressed == true)
        }
    }
}
```

### Proposed Modification 2: IME-Resettable TextField & Hardware Key Capture
Implement a resettable `OutlinedTextField` using a space character placeholder `" "` and a guard flag `isResetting` to prevent feedback loops, along with `onKeyEvent` to capture hardware keys.

```kotlin
// Import additions:
// import androidx.compose.ui.input.key.*
// import androidx.compose.ui.text.TextRange

var textInputState by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
var isResetting by remember { mutableStateOf(false) }

OutlinedTextField(
    value = textInputState,
    onValueChange = { newValue ->
        if (isResetting) {
            isResetting = false
            return@OutlinedTextField
        }
        
        val oldText = textInputState.text
        val newText = newValue.text
        
        if (newText.length > oldText.length) {
            val added = newText.substring(oldText.length)
            if (added == "\n") {
                logText = "Typed: Enter"
                // TODO: Send WebSocket: {"event": "keyboard_input", "key": "Enter"}
            } else {
                logText = "Typed: $added"
                // TODO: Send WebSocket: {"event": "keyboard_input", "key": added}
            }
        } else if (newText.length < oldText.length) {
            logText = "Typed: Backspace"
            // TODO: Send WebSocket: {"event": "keyboard_input", "key": "Backspace"}
        }
        
        // Reset state to placeholder to prevent unbounded growth and ensure backspace detection
        isResetting = true
        textInputState = TextFieldValue(" ", selection = TextRange(1))
    },
    label = { Text("Keyboard Input") },
    modifier = Modifier
        .fillMaxWidth()
        .onKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                val key = when (keyEvent.key) {
                    Key.Enter -> "Enter"
                    Key.Backspace -> "Backspace"
                    Key.Spacebar -> "Space"
                    Key.ShiftLeft, Key.ShiftRight -> "Shift"
                    Key.CtrlLeft, Key.CtrlRight -> "Ctrl"
                    Key.AltLeft, Key.AltRight -> "Alt"
                    Key.Escape -> "Escape"
                    Key.DirectionUp -> "ArrowUp"
                    Key.DirectionDown -> "ArrowDown"
                    Key.DirectionLeft -> "ArrowLeft"
                    Key.DirectionRight -> "ArrowRight"
                    else -> null
                }
                if (key != null) {
                    logText = "Hardware Key: $key"
                    // TODO: Send WebSocket: {"event": "keyboard_input", "key": key}
                    true // Consume event
                } else {
                    false
                }
            } else {
                false
            }
        }
)
```

---

## 5. Verification Method

- **Compilation / App Build**:
  Build the Android app from `c:\Development\Monolith\android\` using Gradle:
  ```bash
  cd android
  ./gradlew assembleDebug
  ```
- **Execution / Interactive Verification**:
  1. Deploy the APK to an Android 16 (API 36) emulator.
  2. Perform taps, long-presses, and drags on the trackpad area. Verify the logs shown in the feedback card update correctly:
     - Tapping updates log to `Tapped at: ... (Left Click)`.
     - Long pressing updates log to `Long pressed at: ... (Right Click)`.
     - Dragging updates log to `Dragged dx: ..., dy: ...`.
  3. Type into the text field (both from the soft keyboard and hardware keyboard) and check the feedback card updates to `"Typed: <character>"`, `"Typed: Enter"`, or `"Typed: Backspace"`.
