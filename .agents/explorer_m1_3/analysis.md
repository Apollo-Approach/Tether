# Milestone M1 Analysis & Project Initialization Blueprint

## 1. Development Environment Investigation

### Emulator Setup and Validation
- **Available Emulator**: `Medium_Phone_API_36.1` is configured and available in the environment.
- **Specifications**: 
  - Target: Android 16 (API Level 36.1)
  - System Image: Google Play Intel x86_64 Atom System Image (`system-images/android-36.1/google_apis_playstore/x86_64`)
  - Skin: 1080x2400 (DPI 420x420)
  - Acceleration: Windows Hypervisor Platform (WHPX) is operational.
- **Execution & Lifetime Behavior**:
  - Running `android emulator start Medium_Phone_API_36.1` starts the emulator and blocks until it is fully booted.
  - However, on Windows, when the shell task that launched it finishes, the emulator process is terminated. 
  - To keep the emulator running for development and E2E testing, it must be launched directly:
    ```powershell
    C:\Users\devon\AppData\Local\Android\Sdk\emulator\emulator.exe "@Medium_Phone_API_36.1"
    ```
    This command must run as a persistent background task (re-quoted for PowerShell syntax) to keep it alive during testing.
  - The booted device serial is `emulator-5554`. It becomes queryable via ADB (using `adb devices`) after booting (approx. 90 seconds).
  - To clean up the emulator, use:
    ```powershell
    C:\Users\devon\AppData\Local\Android\Sdk\platform-tools\adb.exe -s emulator-5554 emu kill
    ```

### Python Environment
- **Version**: Python 3.12.10.
- **Key Libraries**: `websockets` (version 14.2) and `pytest` (version 9.0.3) are pre-installed in the environment.
- **WebSocket Verification**: The `websockets` library is verified as functional and ready to be imported.

---

## 2. Minimal Compilable Project Structure (Blueprint)

Below is the proposed layout and content for the initial codebase.

### Directory Layout
```
/ (Monolith Root)
├── android/
│   ├── gradle/
│   │   └── wrapper/
│   │       ├── gradle-wrapper.jar
│   │       └── gradle-wrapper.properties
│   ├── gradlew
│   ├── gradlew.bat
│   ├── gradle.properties
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle/
│   │   └── libs.versions.toml
│   └── app/
│       ├── build.gradle.kts
│       └── src/
│           └── main/
│               ├── AndroidManifest.xml
│               └── java/
│                   └── com/
│                       └── antigravity/
│                           └── remote/
│                               └── MainActivity.kt
├── receiver/
│   ├── receiver.py
│   └── requirements.txt
└── PROJECT.md
```

### Initial Configuration Files

#### 1. `android/gradle/wrapper/gradle-wrapper.properties`
Configures Gradle 9.1.0 to compile the project.
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.1.0-bin.zip
distributionSha256Sum=a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

#### 2. `android/gradle/libs.versions.toml`
Declares AGP 9.0.1, Kotlin 2.3.20, and OkHttp for WebSocket client communication.
```toml
[versions]
androidGradlePlugin = "9.0.1"
androidxCore = "1.18.0"
androidxLifecycle = "2.10.0"
androidxActivity = "1.13.0"
androidxComposeBom = "2026.03.01"
junit = "4.13.2"
kotlin = "2.3.20"
okhttp = "4.12.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidxCore" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidxActivity" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "androidxComposeBom" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3"}
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui"}
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling"}
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "androidxLifecycle" }
junit = { module = "junit:junit", version.ref = "junit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }

[plugins]
android-application = { id = "com.android.application", version.ref = "androidGradlePlugin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

#### 3. `android/build.gradle.kts`
```kotlin
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
}
```

#### 4. `android/settings.gradle.kts`
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AntigravityRemote"
include(":app")
```

#### 5. `android/app/build.gradle.kts`
Targets SDK 36 (Android 16). Requires Java 17 toolchain.
```kotlin
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.antigravity.remote"
    compileSdk = 36
    
    defaultConfig {
        applicationId = "com.antigravity.remote"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core-ktx)
    implementation(libs.androidx.lifecycle-runtime-ktx)
    implementation(libs.androidx.activity-compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui-tooling)
    testImplementation(libs.junit)
}
```

#### 6. `android/app/src/main/AndroidManifest.xml`
Critically declares the `INTERNET` permission to allow WebSocket client connections to the host.
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.antigravity.remote">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:label="Antigravity Remote"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

#### 7. `android/app/src/main/java/com/antigravity/remote/MainActivity.kt`
Minimal UI representing both sections: Trackpad Touch Area and Text/Keyboard input.
```kotlin
package com.antigravity.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RemoteControlScreen()
                }
            }
        }
    }
}

@Composable
fun RemoteControlScreen() {
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var textInput by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Antigravity Remote", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Status: $connectionStatus", style = MaterialTheme.typography.bodyLarge)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Touch / Trackpad Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.DarkGray)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // Capture relative mouse move: dragAmount.x and dragAmount.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Trackpad Touch Area", color = Color.White)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Keyboard Capture Area
        TextField(
            value = textInput,
            onValueChange = {
                textInput = it
                // Send text input event
            },
            label = { Text("Keyboard Input Area") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

---

## 3. Minimal Python Receiver

#### 1. `receiver/requirements.txt`
```
websockets==14.2
```

#### 2. `receiver/receiver.py`
A robust asyncio WebSocket server logging incoming control events.
```python
import asyncio
import json
import logging
import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

async def handler(websocket):
    logging.info(f"Client connected: {websocket.remote_address}")
    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                event_type = data.get("event")
                logging.info(f"Received event: {event_type} -> {data}")
                
                # Command execution placeholder (Milestone M2+)
                
            except json.JSONDecodeError:
                logging.warning(f"Malformed JSON received: {message}")
    except websockets.exceptions.ConnectionClosed as e:
        logging.info(f"Client disconnected: {e}")

async def main():
    # Bind to 0.0.0.0 to accept connections from Android Emulator via host IP loopback
    async with websockets.serve(handler, "0.0.0.0", 8080):
        logging.info("WebSocket Receiver started on ws://0.0.0.0:8080")
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logging.info("Receiver stopped.")
```

---

## 4. E2E Testing & Verification Protocol

### How to test the Android build:
1. **Compilation**: Run `./gradlew assembleDebug` in the `/android` directory to generate the debug APK.
   - APK output path: `/android/app/build/outputs/apk/debug/app-debug.apk`
2. **Start Emulator**: Run the emulator in the background (detaching it using a persistent task):
   ```powershell
   C:\Users\devon\AppData\Local\Android\Sdk\emulator\emulator.exe "@Medium_Phone_API_36.1"
   ```
3. **Deploy & Run**: Install and launch the app using `android run`:
   ```powershell
   android run --apks=/android/app/build/outputs/apk/debug/app-debug.apk --activity=com.antigravity.remote.MainActivity
   ```
4. **E2E Input Injection**:
   - The test script `/tests/run_tests.py` can simulate gestures by invoking ADB input commands:
     - Mouse Move simulation: `adb shell input swipe <x1> <y1> <x2> <y2> <duration_ms>`
     - Mouse Click simulation: `adb shell input tap <x> <y>`
     - Keyboard Input simulation: `adb shell input text "<text>"` or `adb shell input keyevent <key_code>`
   - The test runner verifies that the Python receiver logs show the matching decoded JSON packets for:
     - `mouse_move` with correct `dx`, `dy` relative values.
     - `mouse_click` with `left`, `right`, or `middle` button.
     - `keyboard_input` with matching keys.
