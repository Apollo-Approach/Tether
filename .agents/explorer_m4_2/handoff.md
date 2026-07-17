# Milestone M4: Client-Server WebSocket Integration Strategy and Recommendations

This report outlines the analysis and recommendations for integrating the Android client with the Python WebSocket server for Milestone M4.

---

## 1. Observation

### Codebase and Layout Structure
1. **MainActivity Layout**:
   - `MainActivity.kt` (lines 53-203) uses a Compose `Column` to arrange screen elements.
   - The Trackpad `Box` (lines 73-137) uses `.weight(1f)` to occupy all remaining vertical space:
     ```kotlin
     Box(
         modifier = Modifier
             .fillMaxWidth()
             .weight(1f)
             .background(Color.DarkGray)
             .pointerInput(Unit) { ... }
     )
     ```
   - Other elements in the column include a header/title (`Text` on line 61), status label (`Text` on line 66), an `OutlinedTextField` for keyboard input (line 140), and a feedback card (`Card` on line 185).

2. **Dependencies**:
   - `android/app/build.gradle.kts` (line 47) includes OkHttp:
     ```kotlin
     implementation(libs.okhttp)
     ```
   - `android/gradle/libs.versions.toml` (line 16, 40) defines the OkHttp version and module:
     ```toml
     okhttp = "4.12.0"
     okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
     ```

3. **Android Manifest Permissions**:
   - `android/app/src/main/AndroidManifest.xml` (line 4) contains the Internet permission:
     ```xml
     <uses-permission android:name="android.permission.INTERNET" />
     ```
   - It does **not** declare `android:usesCleartextTraffic="true"` under the `<application>` element.

4. **Event Serialization and Protocol Contracts**:
   - `PROJECT.md` (lines 30-59) specifies the JSON structures:
     - **Trackpad Move**: `{"event": "mouse_move", "dx": 15.5, "dy": -10.2}`
     - **Mouse Click**: `{"event": "mouse_click", "button": "left" | "right" | "middle"}`
     - **Keyboard Input**: `{"event": "keyboard_input", "key": "a" | "Enter" | "Backspace" | "Shift"}`
   - `receiver/receiver.py` (lines 25-91) decodes JSON, handles validation, and prints to stdout/stderr:
     - `mouse_move`: requires `dx` and `dy` as finite numbers, clamping them to `[-2000.0, 2000.0]`. Prints `[MOUSE_MOVE] dx: <dx>, dy: <dy>`.
     - `mouse_click`: requires `button` in `("left", "right", "middle")`. Prints `[MOUSE_CLICK] button: <button>`.
     - `keyboard_input`: requires `key` as string, length 1 to 100. Prints `[KEYBOARD_INPUT] key: <key>`.

5. **Input Callbacks in MainActivity**:
   - **Trackpad movements**: Intercepted in `pointerInput` on lines 102-114, logging `"Dragged dx: ${positionChange.x}, dy: ${positionChange.y}"`.
   - **Trackpad clicks**: Left click is intercepted on line 122: `"Tapped at: ${dragChange.position} (Left Click)"`. Right click is intercepted on line 90: `"Long pressed at: ${down.position} (Right Click)"`.
   - **Keyboard input (soft keyboard)**: Intercepted in `onValueChange` on lines 140-165. Logs `"Typed: Enter"` if `added == "\n"`, `"Typed: Backspace"` if `newText.length < oldText.length`, and `"Typed: $added"` for general additions.
   - **Keyboard input (hardware/physical keys)**: Intercepted in `onKeyEvent` on lines 169-181, mapped via `KeyMapper.mapKey(keyEvent.key)`, and logs `"Hardware Key: $mappedKey"`.

---

## 2. Logic Chain

1. **Layout Integrity**:
   - Since the Trackpad Area `Box` uses `.weight(1f)`, any additional siblings introduced into the parent `Column` will cause the Trackpad Area to dynamically shrink to fit the remaining space.
   - Therefore, introducing a control Row for connection (server URL text input and connect/disconnect button) at the top of the column is completely safe and will not break the layout or cause overflow.

2. **WebSocket Client Dependency**:
   - OkHttp is already declared as a project dependency in `build.gradle.kts` and `libs.versions.toml`. No additional configurations are required to utilize OkHttp's WebSocket capability (`okhttp3.WebSocket` and `okhttp3.WebSocketListener`).

3. **Built-in Android JSON Processing**:
   - `org.json.JSONObject` is standard and built directly into the Android SDK.
   - Using `JSONObject` for compiling and formatting the client events to JSON ensures robust escaping (preventing malformed payload errors in `receiver.py`) without introducing third-party libraries (like `Gson` or `kotlinx.serialization`) that would clutter `build.gradle.kts`.

4. **Cleartext Security Constraints**:
   - On Android (API level 28+), all cleartext (non-HTTPS/non-WSS) traffic is blocked by default.
   - Since the local Python receiver runs on cleartext `ws://` (e.g., `ws://10.0.2.2:8080`), connection attempts will fail with a cleartext traffic policy exception unless `android:usesCleartextTraffic="true"` is declared on the `<application>` element in `AndroidManifest.xml`.

5. **Thread Safety**:
   - OkHttp's `WebSocketListener` callbacks (`onOpen`, `onClosed`, `onFailure`) execute asynchronously on OkHttp's internal background thread pool.
   - Direct modifications to Jetpack Compose mutable states (like `connectionStatus` and `logText`) from these callbacks must be scheduled or run in a way that respects Compose's rendering cycle. Wrapping state changes in a coroutine launched on `rememberCoroutineScope()` (which binds to the Main/UI thread dispatcher) guarantees safe updates.

---

## 3. Caveats

1. **Localhost Network Routing**:
   - When running the Android app in an emulator, `localhost` or `127.0.0.1` resolves to the emulator's internal loopback.
   - To connect to the host computer hosting the Python receiver, the client must use the loopback IP **`10.0.2.2`** (e.g., `ws://10.0.2.2:8080`) instead of `localhost`. This should be set as the default text input value.

2. **Event Duplication (Soft Keyboard vs Physical Keys)**:
   - Some keyboard actions (like Backspace and Enter) could be captured by both `onValueChange` and `onKeyEvent` depending on the system's IME state.
   - However, the existing implementation already mitigates this: `onKeyEvent` returns `true` (consuming the event) for recognized hardware keys, which prevents double processing. Recommendation: retain this structure.

3. **Software Keyboard Unicode & Multi-character Input**:
   - In Compose's `OutlinedTextField`, if a user types rapidly, uses autocorrect, or copy-pastes, the `added` text in `onValueChange` can contain multiple characters.
   - The receiver expects individual key strings (e.g., `key: "a"`). Therefore, if `added` has multiple characters, the app must iterate through `added` and transmit each character as an individual `keyboard_input` event.

---

## 4. Conclusion and Recommendations

### A. Android Manifest Configuration
Modify `android/app/src/main/AndroidManifest.xml` to include `android:usesCleartextTraffic="true"` inside the `<application>` tag:

```xml
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:windowSoftInputMode="adjustResize"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.Antigravity">
```

### B. MainActivity Imports
Add the following imports to `android/app/src/main/java/com/antigravity/remote/MainActivity.kt`:

```kotlin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
```

### C. WebSocket Client & State Management
In `RemoteControlScreen` (inside `MainActivity.kt`), set up the states, helper methods, and OkHttp client:

```kotlin
@Composable
fun RemoteControlScreen() {
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var textInputState by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
    var isResetting by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("Logs will appear here...") }
    val scope = rememberCoroutineScope()

    // 1. Connection states and OkHttp client
    var serverUrl by remember { mutableStateOf("ws://10.0.2.2:8080") }
    val okHttpClient = remember { OkHttpClient() }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }

    // 2. Helper to send JSON payloads safely
    fun sendEvent(eventJson: String) {
        webSocket?.let { ws ->
            val sent = ws.send(eventJson)
            if (!sent) {
                logText = "Send failed (buffer full or closed)"
            }
        } ?: run {
            logText = "Not connected. Event dropped."
        }
    }

    // 3. Connect/Disconnect actions
    fun connectWebSocket(url: String) {
        connectionStatus = "Connecting"
        logText = "Connecting to $url..."
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    connectionStatus = "Connected"
                    logText = "Connected to $url"
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    logText = "Received: $text"
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    connectionStatus = "Disconnected"
                    logText = "Connection closed: $reason"
                    webSocket = null
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    connectionStatus = "Disconnected"
                    logText = "Connection failed: ${t.message}"
                    webSocket = null
                }
            }
        }
        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "Disconnect by user")
        webSocket = null
        connectionStatus = "Disconnected"
        logText = "Disconnected"
    }

    // Clean up connection when Composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            webSocket?.close(1000, "Screen disposed")
        }
    }
```

### D. UI Layout Integration (URL Input & Status Label)
Modify the top of `Column` to replace the standalone status `Text` with a unified layout row containing the URL Input and Connect/Disconnect Button. Note the status label is color-coded (`Green` for connected, `Yellow` for connecting, `Red` for disconnected):

```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status & Connection Header
        Text(
            text = "Antigravity Remote",
            fontSize = 24.sp,
            style = MaterialTheme.typography.headlineMedium
        )
        
        Text(
            text = "Status: $connectionStatus",
            color = when (connectionStatus) {
                "Connected" -> Color.Green
                "Connecting" -> Color(0xFFFFA500) // Orange/Yellow
                else -> Color.Red
            },
            style = MaterialTheme.typography.bodyLarge
        )

        // New Server URL & Connection Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = connectionStatus == "Disconnected"
            )
            Button(
                onClick = {
                    if (connectionStatus == "Connected") {
                        disconnectWebSocket()
                    } else if (connectionStatus == "Disconnected") {
                        connectWebSocket(serverUrl)
                    }
                },
                enabled = connectionStatus != "Connecting"
            ) {
                Text(if (connectionStatus == "Connected") "Disconnect" else "Connect")
            }
        }
```

### E. Event Serialization and Transmission Wiring

1. **Trackpad Dragging (Relative Move)**:
   Inside the drag condition (under `Box` modifier pointerInput, line 114):
   ```kotlin
   val json = JSONObject().apply {
       put("event", "mouse_move")
       put("dx", positionChange.x)
       put("dy", positionChange.y)
   }.toString()
   sendEvent(json)
   ```

2. **Trackpad Left Click (Tap)**:
   Inside the tap handler (line 122):
   ```kotlin
   val json = JSONObject().apply {
       put("event", "mouse_click")
       put("button", "left")
   }.toString()
   sendEvent(json)
   ```

3. **Trackpad Right Click (Long Press)**:
   Inside the long press coroutine block (line 90):
   ```kotlin
   val json = JSONObject().apply {
       put("event", "mouse_click")
       put("button", "right")
   }.toString()
   sendEvent(json)
   ```

4. **Keyboard Soft Input (OutlinedTextField `onValueChange`)**:
   Inside the `onValueChange` block:
   - **Enter** (line 154):
     ```kotlin
     val json = JSONObject().apply {
         put("event", "keyboard_input")
         put("key", "Enter")
     }.toString()
     sendEvent(json)
     ```
   - **Backspace** (line 159):
     ```kotlin
     val json = JSONObject().apply {
         put("event", "keyboard_input")
         put("key", "Backspace")
     }.toString()
     sendEvent(json)
     ```
   - **Normal character input** (line 156):
     ```kotlin
     added.forEach { char ->
         val json = JSONObject().apply {
             put("event", "keyboard_input")
             put("key", char.toString())
         }.toString()
         sendEvent(json)
     }
     ```

5. **Keyboard Physical Keys (`onKeyEvent`)**:
   Inside `onKeyEvent` block (line 173):
   ```kotlin
   val json = JSONObject().apply {
       put("event", "keyboard_input")
       put("key", mappedKey)
   }.toString()
   sendEvent(json)
   ```

---

## 5. Verification Method

To verify the integration, execute the following steps:

1. **Android Build Verification**:
   Verify that the Android project compiles successfully without any syntax errors:
   - Open terminal and navigate to `/android` directory.
   - Propose command: `cmd.exe /c "cd android && gradlew.bat assembleDebug"`
   - Ensure the build completes with `BUILD SUCCESSFUL`.

2. **Unit Tests Verification**:
   Verify that the existing keyboard mapping unit tests still pass:
   - Propose command: `cmd.exe /c "cd android && gradlew.bat test"`
   - Ensure that the tests pass.

3. **Python Test Suite Verification**:
   Verify that the receiver's JSON parser and contract validation are sound:
   - Propose command: `python tests/run_tests.py`
   - Ensure that all 4 tiers of tests pass.

4. **E2E Integration Verification**:
   - Start the Python receiver: `python receiver/receiver.py --port 8080`
   - Launch the Android app in the emulator.
   - Input `ws://10.0.2.2:8080` in the server URL field and click **Connect**.
   - Verify that the status label updates to **Status: Connected** in green.
   - Perform relative dragging on the Trackpad, click, long press, and enter keystrokes.
   - Verify that the receiver logs events corresponding to the inputs:
     - `[MOUSE_MOVE] dx: <x>, dy: <y>`
     - `[MOUSE_CLICK] button: <left/right>`
     - `[KEYBOARD_INPUT] key: <key>`
