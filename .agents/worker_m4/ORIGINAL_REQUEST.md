## 2026-07-15T03:59:56Z

You are the Worker for Milestone M4 (Client-Server WebSocket Integration).
Your working directory is: c:\Development\Monolith\.agents\worker_m4\
Your task is to implement the Client-Server WebSocket Integration (Milestone M4) following the Explorer recommendations.

Specifically:
1. Modify c:\Development\Monolith\android\app\src\main\AndroidManifest.xml to include android:usesCleartextTraffic="true" inside the <application> tag, so cleartext ws:// connections are permitted.
2. In c:\Development\Monolith\android\app\src\main\java\com\antigravity\remote\MainActivity.kt:
   - Implement the OkHttp WebSocket client. You can do this by implementing a WebSocketManager class/helper or integrating it directly into MainActivity.kt.
   - Use org.json.JSONObject (built-in Android SDK class) to format mouse move, mouse click, and keyboard input events.
   - Update the UI to include a URL input OutlinedTextField (default value: "ws://10.0.2.2:8080") and a Connect/Disconnect button at the top of RemoteControlScreen.
   - Wire the input event captures (trackpad dragging, tapping, long-press, keyboard typing, hardware keys) so they serialize and send JSON payloads to the WebSocket server whenever the connection status is "Connected".
   - When sending keyboard inputs from the soft keyboard (OutlinedTextField onValueChange), if a string with multiple characters is added at once, iterate over each character and transmit it as an individual keyboard_input event. Special keys like "Enter" and "Backspace" must be transmitted as expected.
   - Make sure all Compose UI state updates from background WebSocket listener threads are correctly executed on the Main/UI thread (e.g. using coroutine scopes).
3. Verify your implementation by running:
   - gradle build command in c:\Development\Monolith\android:
     .\gradlew.bat assembleDebug
   - gradle test command in c:\Development\Monolith\android:
     .\gradlew.bat test
   Ensure both compile successfully and all unit tests pass.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Write your implementation report to c:\Development\Monolith\.agents\worker_m4\handoff.md.
Report back (send_message) when your task is complete with details of the changes and build/test results.
