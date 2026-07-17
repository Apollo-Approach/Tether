# M1 Environment & Project Init Analysis

## 1. Environment Investigation Results

### 1.1 Android CLI and SDK Environment
- **Android CLI Tool**:
  - Version: `1.0.15498356` (Note: launcher version `1.0.15498356`. A new CLI version `1.0.15857036` is available to update via `android update`, but not required for M1).
  - SDK Location: `C:\Users\devon\AppData\Local\Android\Sdk`
- **Installed SDK Platforms & Tools**:
  - platforms: `platforms/android-34`, `platforms/android-35`, `platforms/android-36`, `platforms/android-36.1`
  - build-tools: `build-tools/34.0.0`, `build-tools/35.0.0`, `build-tools/36.0.0`, `build-tools/36.1.0`
  - platform-tools: `platform-tools 37.0.0`
  - system-images: `system-images/android-36.1/google_apis_playstore/x86_64` (Google Play Intel x86_64 Atom System Image)
- **Available Templates**:
  - `empty-activity` (default) - Empty Activity with Compose, Activity, and AGP 9 (`compose,activity,agp-9`).

### 1.2 Python Environment
- **Python Version**: `Python 3.12.10`
- **WebSockets Library**: Installed, version `14.2`

### 1.3 JDK & Gradle Environment
- **JDK Version**: `openjdk version "17.0.14" 2025-01-21` (OpenJDK Runtime Environment Temurin-17.0.14+7)
- **Gradle Version**:
  - Global `gradle` command is not present in PATH.
  - However, the `empty-activity` template packages a local Gradle wrapper (`gradlew.bat` / `gradlew`) running **Gradle 9.1.0**.
  - A test project generated using `android create` compile-checked successfully with this wrapper and JDK (`BUILD SUCCESSFUL` in 1m 9s).

---

## 2. Recommended Setup Plan for Milestone M1

### 2.1 Project Creation Command
Run the following command from the workspace root (`c:\Development\Monolith`) to create the Android 16 project directly in the `/android` directory:
```powershell
android create empty-activity --name="Antigravity" --minSdk=36 -o=android
```

### 2.2 Android Project Configuration (Android 16 / API 36)

#### 2.2.1 `android/gradle/libs.versions.toml`
Verify and update the versions file to include OkHttp for WebSocket client communication.
```toml
[versions]
androidGradlePlugin = "9.0.1"
androidxCore = "1.18.0"
androidxLifecycle = "2.10.0"
androidxActivity = "1.13.0"
androidxComposeBom = "2026.03.01"
androidxTest = "1.7.0"
androidxTestExt = "1.3.0"
androidxTestRunner = "1.7.0"
androidxTestEspresso = "3.7.0"
coroutines = "1.10.2"
junit = "4.13.2"
kotlin = "2.3.20"
nav3Core = "1.0.1"
lifecycleViewmodelNav3 = "2.10.0"
okhttp = "4.12.0" # Added for WebSockets client

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidxCore" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidxActivity" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "androidxComposeBom" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3"}
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui"}
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview"}
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4"}
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling"}
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest"}
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "androidxLifecycle" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "androidxLifecycle" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTest" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExt" }
androidx-test-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "androidxTestEspresso" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
junit = { module = "junit:junit", version.ref = "junit" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
androidx-navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "nav3Core" }
androidx-navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "nav3Core" }
androidx-lifecycle-viewmodel-navigation3 = { module = "androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycleViewmodelNav3" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" } # Added for WebSockets client

[plugins]
android-application = { id = "com.android.application", version.ref = "androidGradlePlugin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

#### 2.2.2 `android/app/build.gradle.kts`
Ensure standard configuration targeting compileSdk 36, targetSdk 36, minSdk 36, and JVM toolchain 17, and add the `okhttp` dependency:
```kotlin
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.antigravity"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.antigravity"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // WebSockets client
  implementation(libs.okhttp)
}
```

#### 2.2.3 Internet Permission (`android/app/src/main/AndroidManifest.xml`)
We must add the `<uses-permission android:name="android.permission.INTERNET" />` tag to the manifest file so that the client can connect to the receiver server via WebSockets:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        ...
```

---

## 2.3 Initial Receiver Structure
Create `/receiver/receiver.py` as a minimal WebSocket server listening on port `8080` (binds to `0.0.0.0` or `127.0.0.1` as appropriate). It will decode incoming control packets and log/simulate execution:

```python
import asyncio
import json
import logging
import websockets

# Setup logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")

async def handler(websocket):
    logging.info(f"Client connected: {websocket.remote_address}")
    try:
        async for message in websocket:
            logging.info(f"Received message: {message}")
            try:
                data = json.loads(message)
                event_type = data.get("event")
                if event_type == "mouse_move":
                    dx = data.get("dx")
                    dy = data.get("dy")
                    logging.info(f"Mouse move: dx={dx}, dy={dy}")
                elif event_type == "mouse_click":
                    button = data.get("button")
                    logging.info(f"Mouse click: {button}")
                elif event_type == "keyboard_input":
                    key = data.get("key")
                    logging.info(f"Keyboard input: {key}")
                else:
                    logging.warning(f"Unknown event type: {event_type}")
            except json.JSONDecodeError:
                logging.error("Failed to decode JSON message")
    except websockets.exceptions.ConnectionClosed as e:
        logging.info(f"Client disconnected: {websocket.remote_address} ({e.code})")
    except Exception as e:
        logging.error(f"Error occurred: {e}")

async def main():
    host = "0.0.0.0"
    port = 8080
    logging.info(f"Starting WebSocket server on ws://{host}:{port}")
    async with websockets.serve(handler, host, port):
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logging.info("Server stopped manually.")
```
