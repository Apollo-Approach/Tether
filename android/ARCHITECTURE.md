# Antigravity Remote Android App Architecture

This document provides a technical overview of the Antigravity Remote Android App, located in `C:\Development\Monolith\android`. It outlines the background notification architecture, network service discovery, state management, and the user interface layer.

## 1. Background Notification Architecture (`AntigravityService`)

The core background operations of the app are managed by the `AntigravityService`, an Android Foreground Service. 

- **Foreground Execution**: When started, the service elevates itself to a foreground service (`startForeground`) by displaying a persistent notification. This ensures the Android system does not kill the service when the app is in the background, allowing it to maintain an active WebSocket connection.
- **Notification Channels**: It sets up two distinct notification channels:
  - `Antigravity Service Channel` (Low Importance): Used for the persistent foreground notification indicating connection status.
  - `Antigravity Messages` (High Importance): Used for alerting the user about new chat messages, updated artifacts, or pending approval requests from the server.
- **WebSocket Lifecycle**: The service initializes and manages a `WebSocketManager`. It connects automatically when a server is discovered and handles incoming JSON payloads (e.g., `chat`, `artifact`, `projects`, `approval_request`). The incoming messages are parsed and pushed into the central `ConnectionRepository`.
- **System Alerts**: Upon receiving messages with role `"assistant"`, or updates on artifacts and approvals, the service fires off high-priority push notifications alerting the user immediately, even if the app UI is closed.

## 2. NSD Auto-Discovery Mechanism

Network Service Discovery (NSD) is heavily utilized to automatically find and connect to the Antigravity server on the local network. 

- **Service-Level Discovery**: Inside `AntigravityService`, an `NsdManager.DiscoveryListener` is started to look for services broadcasting via `_adb-tls-connect._tcp`. When a service containing `"adb-tls-connect"` in its name is resolved, the service retrieves the host IP and port, constructs the WebSocket URL (`ws://<ip>:<port>`), and automatically establishes a connection without user intervention.
- **UI-Level Discovery (`NsdDiscoveryManager`)**: In `MainActivity.kt`, there is a dedicated `NsdDiscoveryManager` class that listens for `_antigravity._tcp.` (or `_antigravity._tcp.local.`) services. Discovered hosts are emitted via a `StateFlow` and presented to the user in a Navigation Drawer, allowing them to manually select from multiple available Antigravity hosts on the network.

## 3. State Management (`ConnectionRepository`)

The application acts as a reactive system using Kotlin Coroutines and StateFlows, orchestrated through a singleton `ConnectionRepository`.

- **State Container (`AppState`)**: All relevant UI state is centralized in a data class `AppState`, which tracks:
  - `connectionStatus`: Current WebSocket state (e.g., "Disconnected", "Connecting...", "Connected").
  - `chatMessages`: A list of `ChatMessage` objects representing the conversation history.
  - `currentArtifact`: The latest `ArtifactMessage` holding generated code, plans, or documents.
  - `currentApprovalRequest`: The currently pending `ApprovalRequest` awaiting user consent.
  - `allProjects`: A list of available project directories.
  - `isThinking`: A boolean indicating if the assistant is currently processing a task.
- **Reactivity**: `ConnectionRepository` exposes a `StateFlow<AppState>` (`state`). Any updates (e.g., from incoming WebSocket messages in `AntigravityService`) are made by replacing the value of the `MutableStateFlow`.
- **Decoupling**: By placing the WebSocket state in this repository, `AntigravityService` can run headless and push updates, while `MainActivity` can observe the `StateFlow` and re-compose the UI automatically when the data changes.

## 4. UI Layer (`MainActivity.kt` Compose)

The user interface is built entirely using Jetpack Compose in `MainActivity.kt`.

- **Permissions & Service Initiation**: On creation, the Activity checks for `POST_NOTIFICATIONS` permission (required for Android 13+) and kicks off the `AntigravityService` either as a standard or foreground service based on the Android version.
- **Reactive UI**: The Compose `RemoteControlScreen` collects the `ConnectionRepository.state` via `collectAsState()`. This means any update to the connection status, new chat messages, or newly rendered artifacts trigger an immediate, seamless UI re-composition.
- **Components**:
  - **Navigation Drawer (`ModalNavigationDrawer`)**: Displays the list of discovered hosts from the `NsdDiscoveryManager` for manual connection selection.
  - **Chat Interface & Visual Inputs**: Includes tools for sending text and visual media. An `ActivityResultContracts.PickVisualMedia` launcher is used to let users select photos, scale them down, convert them to Base64, and transmit them via the active WebSocket connection.
  - **Artifact & Approval Modals**: (Inferred from state usage) The Compose UI reacts to non-null `currentArtifact` or `currentApprovalRequest` state values by likely rendering overlay dialogues or dedicated views to let the user review plans or grant execution permissions on the go.
