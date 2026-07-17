# M4: Client-Server WebSocket Integration Strategy

## 1. Observation
We observed the following files and configuration in the codebase:
- **OkHttp Dependency**: 
  - `android/app/build.gradle.kts` line 47 contains:
    ```kotlin
    implementation(libs.okhttp)
    ```
  - `android/gradle/libs.versions.toml` defines the dependency on lines 16 and 40:
    ```toml
    okhttp = "4.12.0"
    ...
    okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
    ```
- **Internet Permission**: 
  - `android/app/src/main/AndroidManifest.xml` line 4 contains:
    ```xml
    <uses-permission android:name="android.permission.INTERNET" />
    ```
- **MainActivity UI & Input Capture**:
  - `android/app/src/main/java/com/antigravity/remote/MainActivity.kt` (lines 46–203) contains the `RemoteControlScreen` layout, gesture listener, and keyboard input handler.
- **Python WebSocket Receiver**:
  - `receiver/receiver.py` (lines 21–91) implements the WebSocket client handler which parses JSON payloads and prints:
    - Mouse movement: `[MOUSE_MOVE] dx: {dx}, dy: {dy}`
    - Mouse click: `[MOUSE_CLICK] button: {button}`
    - Keyboard input: `[KEYBOARD_INPUT] key: {key}`
  - It handles standard loopback connection on port `8080` by default.

## 2. Logic Chain
- **Step 1**: The client requires a WebSocket connection. Since OkHttp (`libs.okhttp`) is already declared in `build.gradle.kts`, no new dependencies are required.
- **Step 2**: The target SDK is 36 (Android 16). Android restricts cleartext HTTP and WebSocket traffic (`ws://`) by default for API level 28+. Connecting to `ws://10.0.2.2:8080` will fail with a `SocketException` or security exception unless cleartext is explicitly allowed. Therefore, `android:usesCleartextTraffic="true"` must be added to the `<application>` tag in `AndroidManifest.xml` (or custom network security rules configured).
- **Step 3**: To serialize events into the JSON format expected by `receiver/receiver.py`, the built-in Android `org.json.JSONObject` class should be used. This avoids adding a dependency on `kotlinx.serialization` or GSON and keeps the APK size small and build times fast.
- **Step 4**: OkHttp's `WebSocketListener` callbacks are executed on background thread pools. Since state variables (`connectionStatus`, `logText`) affect Compose UI composition, state changes should be scheduled on the main application loop. This can be cleanly solved using `Handler(Looper.getMainLooper()).post` or through Compose coroutines.
- **Step 5**: Controls for server URL entry and Connect/Disconnect actions can be added to the top of `RemoteControlScreen`. Once connected, the WebSocket client will be kept active, and user inputs (trackpad drag, click, typing) will immediately be wrapped in JSON and dispatched to the receiver.

## 3. Caveats
- **Local IP alias**: `10.0.2.2` is a special routing address used in Android Emulators to reach the host's loopback interface (`127.0.0.1`). If testing on a physical Android device, the user must specify the host machine's LAN IP address (e.g. `ws://192.168.1.10:8080`).
- **Cleartext Traffic Scope**: Enabling `android:usesCleartextTraffic="true"` globally exposes the app to cleartext traffic for all domains. For a production app, it is better to restrict cleartext to loopback domains using a `network_security_config.xml`.
- **Drag event volume**: Touch movement events are fired at high frequency. Although OkHttp sends them asynchronously, sending hundreds of small coordinate updates per second can consume CPU/network bandwidth. A throttling mechanism (e.g., sending only when movement exceeds a time/distance threshold, or buffering) is not strictly required for this milestone, but should be considered if latency or congestion occurs during end-to-end testing.

## 4. Conclusion
We recommend a three-part integration plan:
1. **Allow Cleartext Traffic**: Modify `AndroidManifest.xml` to include `android:usesCleartextTraffic="true"`.
2. **Implement WebSocketManager**: Create `WebSocketManager.kt` using OkHttp to handle connection lifecycle and dispatch events.
3. **Update MainActivity UI & Event Bindings**: Add the URL text field, a Connect/Disconnect button, and bind Compose gestures/keystrokes to the `WebSocketManager`.

### Strategy & Code Outlines

#### Part A: AndroidManifest.xml
Update `<application>` tag to allow cleartext traffic:
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

#### Part B: Create `WebSocketManager.kt`
Create `android/app/src/main/java/com/antigravity/remote/WebSocketManager.kt` with thread-safe callbacks routed to the main thread:
```kotlin
package com.antigravity.remote

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class WebSocketManager(
    private val onStatusChanged: (String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(url: String) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            onLog("Invalid URL format: ${e.localizedMessage}")
            return
        }

        onStatusChanged("Connecting")
        onLog("Connecting to $url...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    onStatusChanged("Connected")
                    onLog("Connected to $url")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    onStatusChanged("Disconnected")
                    onLog("Disconnected: $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    onStatusChanged("Disconnected")
                    onLog("Connection error: ${t.localizedMessage}")
                }
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnected by user")
        webSocket = null
        onStatusChanged("Disconnected")
        onLog("Disconnected")
    }

    fun sendMouseMove(dx: Float, dy: Float) {
        val payload = JSONObject().apply {
            put("event", "mouse_move")
            put("dx", dx.toDouble())
            put("dy", dy.toDouble())
        }
        sendPayload(payload.toString())
    }

    fun sendMouseClick(button: String) {
        val payload = JSONObject().apply {
            put("event", "mouse_click")
            put("button", button)
        }
        sendPayload(payload.toString())
    }

    fun sendKeyboardInput(key: String) {
        val payload = JSONObject().apply {
            put("event", "keyboard_input")
            put("key", key)
        }
        sendPayload(payload.toString())
    }

    private fun sendPayload(json: String) {
        webSocket?.let { ws ->
            ws.send(json)
        }
    }
}
```

#### Part C: Integrate into `MainActivity.kt`
Replace the local states and UI inside `RemoteControlScreen`:

```kotlin
@Composable
fun RemoteControlScreen() {
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var textInputState by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
    var isResetting by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("Logs will appear here...") }
    var serverUrl by remember { mutableStateOf("ws://10.0.2.2:8080") }
    
    val scope = rememberCoroutineScope()

    // Instantiate and remember WebSocketManager
    val webSocketManager = remember {
        WebSocketManager(
            onStatusChanged = { status -> connectionStatus = status },
            onLog = { msg -> logText = msg }
        )
    }

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
        
        // Server URL input & connection buttons
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
                enabled = connectionStatus != "Connected" && connectionStatus != "Connecting"
            )
            Button(
                onClick = {
                    if (connectionStatus == "Connected" || connectionStatus == "Connecting") {
                        webSocketManager.disconnect()
                    } else {
                        webSocketManager.connect(serverUrl)
                    }
                }
            ) {
                Text(
                    text = when (connectionStatus) {
                        "Connected" -> "Disconnect"
                        "Connecting" -> "Cancel"
                        else -> "Connect"
                    }
                )
            }
        }

        Text(
            text = "Status: $connectionStatus",
            color = when (connectionStatus) {
                "Connected" -> Color.Green
                "Connecting" -> Color.Yellow
                else -> Color.Red
            },
            style = MaterialTheme.typography.bodyLarge
        )

        // Trackpad Touch Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.DarkGray)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragTriggered = false
                            var isLongPress = false
                            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                            
                            val longPressJob = scope.launch {
                                delay(longPressTimeout)
                                isLongPress = true
                                logText = "Long pressed at: ${down.position} (Right Click)"
                                if (connectionStatus == "Connected") {
                                    webSocketManager.sendMouseClick("right")
                                }
                            }
                            
                            val pointerId = down.id
                            var totalDrag = Offset.Zero
                            
                            do {
                                val event = awaitPointerEvent()
                                val dragChange = event.changes.firstOrNull { it.id == pointerId }
                                
                                if (dragChange != null) {
                                    if (dragChange.pressed) {
                                        val positionChange = dragChange.positionChange()
                                        totalDrag += positionChange
                                        
                                        if (totalDrag.getDistance() > viewConfiguration.touchSlop) {
                                            longPressJob.cancel()
                                            
                                            if (!dragTriggered) {
                                                dragTriggered = true
                                                logText = "Drag started"
                                            }
                                            
                                            dragChange.consume()
                                            logText = "Dragged dx: ${positionChange.x}, dy: ${positionChange.y}"
                                            if (connectionStatus == "Connected") {
                                                webSocketManager.sendMouseMove(positionChange.x, positionChange.y)
                                            }
                                        }
                                    } else {
                                        longPressJob.cancel()
                                        if (dragTriggered) {
                                            logText = "Drag ended"
                                        } else if (!isLongPress) {
                                            logText = "Tapped at: ${dragChange.position} (Left Click)"
                                            if (connectionStatus == "Connected") {
                                                webSocketManager.sendMouseClick("left")
                                            }
                                        }
                                    }
                                }
                            } while (dragChange?.pressed == true)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Trackpad Area\n(Drag for movement, Tap for click)",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Text & Keyboard Input Area
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
                        if (connectionStatus == "Connected") {
                            webSocketManager.sendKeyboardInput("Enter")
                        }
                    } else {
                        logText = "Typed: $added"
                        if (connectionStatus == "Connected") {
                            webSocketManager.sendKeyboardInput(added)
                        }
                    }
                } else if (newText.length < oldText.length) {
                    logText = "Typed: Backspace"
                    if (connectionStatus == "Connected") {
                        webSocketManager.sendKeyboardInput("Backspace")
                    }
                }
                
                isResetting = true
                textInputState = TextFieldValue(" ", selection = TextRange(1))
            },
            label = { Text("Keyboard Input") },
            modifier = Modifier
                .fillMaxWidth()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        val mappedKey = KeyMapper.mapKey(keyEvent.key)
                        if (mappedKey != null) {
                            logText = "Hardware Key: $mappedKey"
                            if (connectionStatus == "Connected") {
                                webSocketManager.sendKeyboardInput(mappedKey)
                            }
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        )

        // Logs/Feedback console area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = logText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
```

## 5. Verification Method
1. **Verify Baseline compilation and unit tests**:
   Run the Gradle test runner command in terminal to ensure the existing project builds and key mapper tests pass:
   ```cmd
   cd android
   .\gradlew.bat test
   ```
2. **Execute Receiver server locally on the host**:
   Ensure `websockets` dependency is installed (defined in `tests/requirements.txt`). Start the receiver server in mock mode:
   ```cmd
   python receiver/receiver.py --mock --port 8080
   ```
3. **Verify E2E protocol compliance via test suite**:
   Verify that the communication protocol parses correctly by running the python-based tests:
   ```cmd
   python tests/run_tests.py
   ```
4. **Manual Connection and Event check**:
   - Run the Android app in the emulator.
   - Enter `ws://10.0.2.2:8080` in the "Server URL" field and click "Connect".
   - Drag/tap on the Trackpad, and type in the Keyboard Input text box.
   - Verify that the terminal running `receiver.py` prints the expected `[MOUSE_MOVE]`, `[MOUSE_CLICK]`, and `[KEYBOARD_INPUT]` lines in real-time.
