package com.rover.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Context
import androidx.core.view.WindowCompat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.net.Proxy
import java.net.InetSocketAddress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.rover.remote.ui.theme.*
import com.rover.remote.ui.components.MarkdownText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.json.JSONObject

data class ChatMessage(val role: String, val message: String)
data class ArtifactMessage(val title: String, val content: String)
data class ApprovalRequest(val title: String, val options: List<String>)


class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionRepository.init(applicationContext)
        TailscaleManager.start(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SecurityManager.init(this)

        // Request notification and mic permission
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val ungranted = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) {
            requestPermissions(ungranted.toTypedArray(), 101)
        } else {
            startRoverService()
        }

        val btRoutingManager = BluetoothVoiceRoutingManager(this)
    }

    override fun onResume() {
        super.onResume()
        // If the user returns to the app and the WebSocket is silently dead due to Doze,
        // force a disconnect. The background service will instantly attempt a fresh reconnect.
        // We MUST NOT call TailscaleManager.stop() because tsnet on Android hangs if restarted 
        // in the same process. We rely on Tailscale's magicsock to recover the tunnel.
        if (ConnectionRepository.state.value.connectionStatus != "Connected") {
            ConnectionRepository.webSocketManager?.disconnect()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            startRoverService()
        }
    }

    private fun startRoverService() {
        // Start the background service
        val serviceIntent = Intent(this, RoverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Auto-start Tailscale if it was already authenticated previously
        // TailscaleManager.start(this)

        setContent {
            var showBiometricPrompt by remember { mutableStateOf(SecurityManager.requiresBiometricAuth()) }

            if (showBiometricPrompt) {
                LaunchedEffect(Unit) {
                    val executor = androidx.core.content.ContextCompat.getMainExecutor(this@MainActivity)
                    val biometricPrompt = androidx.biometric.BiometricPrompt(this@MainActivity, executor,
                        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                super.onAuthenticationError(errorCode, errString)
                                // If they cancel or fail, maybe finish the app
                                if (errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED) {
                                    finish()
                                }
                            }

                            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                                super.onAuthenticationSucceeded(result)
                                SecurityManager.markAuthenticated()
                                showBiometricPrompt = false
                            }
                        })
                    val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Authentication required")
                        .setSubtitle("Log in using your biometric credential")
                        .setNegativeButtonText("Cancel")
                        .build()

                    biometricPrompt.authenticate(promptInfo)
                }
            }

            TetherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!showBiometricPrompt) {
                        RemoteControlScreen()
                    }
                }
            }
        }
    }
}

data class SlashCommand(val command: String, val description: String)

val SYSTEM_COMMANDS = listOf(
    SlashCommand("/goal", "Run a long-running task to completion"),
    SlashCommand("/schedule", "Set a recurring schedule or one-time timer"),
    SlashCommand("/browser", "Delegate a task requiring web browsing"),
    SlashCommand("/grill-me", "Interactive interview for design decisions"),
    SlashCommand("/teamwork-preview", "Preview autonomous agents working together"),
    SlashCommand("/learn", "Persist a learned behavior for future tasks")
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen() {
    val state by ConnectionRepository.state.collectAsState()
    val connectionStatus = state.connectionStatus
    val chatMessages = state.chatMessages
    val currentArtifact = state.currentArtifact
    val currentApprovalRequest = state.currentApprovalRequest
    val allProjects = state.allProjects
    val currentProject = state.currentProject
    val isThinking = state.isThinking
    val isMicListening = state.isMicListening
    
    BackHandler(enabled = currentArtifact != null) {
        ConnectionRepository.setArtifact(null)
    }
    
    val scope = rememberCoroutineScope()
    val webSocketManager = ConnectionRepository.webSocketManager
    

    var showMoreProjects by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val discoveredHosts = state.discoveredHosts
    
    var isTrackpadVisible by remember { mutableStateOf(false) }
    var showVoiceSettings by remember { mutableStateOf(false) }
    var showArtifactsSheet by remember { mutableStateOf(false) }
    var quoteDialogText by remember { mutableStateOf<String?>(null) }
    var chatInput by remember { mutableStateOf(TextFieldValue("")) }
    val context = LocalContext.current

    var showInitialNetworkPrompt by remember { mutableStateOf(state.trustedNetworks.isEmpty()) }
    var hasPromptedForNetwork by remember { mutableStateOf(false) }
    
    val initialNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            scope.launch {
                val ssid = ConnectionRepository.networkManager?.getCurrentSsid()
                if (ssid != null && ssid != "<unknown ssid>") {
                    val loc = LocationVerifier.getCurrentLocation(context)
                    if (loc != null) {
                        ConnectionRepository.addTrustedNetwork(TrustedNetwork(ssid, loc.latitude, loc.longitude))
                    }
                }
            }
        }
    }
    
    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && connectionStatus == "Connected") {
            scope.launch(Dispatchers.IO) {
                try {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                    
                    if (bitmap != null) {
                        val maxDim = 1024f
                        val scale = minOf(maxDim / bitmap.width, maxDim / bitmap.height)
                        val scaledBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        } else {
                            bitmap
                        }
                        
                        val outputStream = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                        val base64Str = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                        
                        selectedImageBitmap = scaledBitmap
                        selectedImageBase64 = base64Str
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val appState by ConnectionRepository.state.collectAsState()
    
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            TailscaleManager.start(context)
            TailscaleManager.startPolling()
        } else {
            TailscaleManager.stopPolling()
        }
    }

    val peers by TailscaleManager.peers.collectAsState()
    val combinedHosts = remember(discoveredHosts, peers) {
        val map = mutableMapOf<String, RoverHost>()
        discoveredHosts.forEach { host ->
            map[host.name] = host
        }
        peers.filter { it.online }.forEach { peer ->
            val existing = map[peer.hostname]
            if (existing != null) {
                map[peer.hostname] = existing.copy(tailscaleIp = peer.ip)
            } else {
                map[peer.hostname] = RoverHost(
                    name = peer.hostname,
                    tailscaleIp = peer.ip,
                    os = "Unknown"
                )
            }
        }
        map.values.toList().sortedBy { it.name }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("drawer_sheet")) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Hosts", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                if (combinedHosts.isEmpty()) {
                    Text("Scanning for hosts...", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("txt_scanning_hosts"), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                } else {
                    combinedHosts.forEach { host ->
                        val isConnected = appState.connectedHostName == host.name && appState.connectionStatus == "Connected"
                        
                        NavigationDrawerItem(
                            label = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val indicatorColor = if (isConnected) StatusConnected else TextTertiary
                                    val textColor = if (isConnected) MaterialTheme.colorScheme.primary else Color.Unspecified
                                    Box(modifier = Modifier.size(8.dp).background(indicatorColor, shape = androidx.compose.foundation.shape.CircleShape))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(host.name, color = textColor)
                                    
                                    if (host.localIp != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Wi-Fi", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                                    } else if (host.tailscaleIp != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Tailscale", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            },
                            selected = isConnected,
                            onClick = {
                                if (isConnected) {
                                    scope.launch { drawerState.close() }
                                    return@NavigationDrawerItem
                                }
                                
                                val targetUrl = if (host.localIp != null) {
                                    "ws://${host.localIp}:${host.localPort}"
                                } else if (host.tailscaleIp != null) {
                                    val formattedHost = if (host.tailscaleIp.contains(":") && !host.tailscaleIp.startsWith("[")) "[${host.tailscaleIp}]" else host.tailscaleIp
                                    "${Config.WS_SCHEME}$formattedHost:${Config.TSNET_PROXY_PORT}"
                                } else {
                                    ""
                                }
                                
                                if (targetUrl.isNotEmpty()) {
                                    if (appState.connectionStatus == "Connected") {
                                        webSocketManager?.disconnect()
                                        ConnectionRepository.updateConnectionStatus("Disconnected")
                                    }
                                    

                                    ConnectionRepository.updateConnectedHostName(host.name)
                                    ConnectionRepository.updateConnectionStatus("Connecting...")
                                    
                                    val intent = Intent(context, RoverService::class.java).apply {
                                        action = "com.rover.remote.CONNECT"
                                        putExtra("url", targetUrl)
                                    }
                                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                    scope.launch { drawerState.close() }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("drawer_host_${host.name}")
                        )
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Text("Projects", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                NavigationDrawerItem(
                    label = { Text("+ New Project") },
                    selected = false,
                    onClick = { 
                        newProjectName = ""
                        showNewProjectDialog = true 
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("btn_new_project"),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                
                if (showNewProjectDialog) {
                    AlertDialog(
                        modifier = Modifier.testTag("dialog_create_project"),
                        onDismissRequest = { showNewProjectDialog = false },
                        title = { Text("Create Project") },
                        text = {
                            Column {
                                Text("A new folder will be created in C:\\Development\\")
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = newProjectName,
                                    onValueChange = { newProjectName = it },
                                    label = { Text("Project Name") },
                                    modifier = Modifier.testTag("input_create_project_name"),
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                modifier = Modifier.testTag("btn_create_project_confirm"),
                                onClick = { 
                                    if (newProjectName.isNotBlank()) {
                                        val trimmedName = newProjectName.trim()
                                        // 1. Create the project
                                        val createPayload = org.json.JSONObject().apply {
                                            put("event", "create_project")
                                            put("name", trimmedName)
                                        }
                                        ConnectionRepository.webSocketManager?.send(createPayload.toString())
                                        
                                        // 2. Switch to the new project immediately
                                        ConnectionRepository.setCurrentProject(trimmedName)
                                        val selectPayload = org.json.JSONObject().apply {
                                            put("event", "select_project")
                                            put("project", trimmedName)
                                        }
                                        ConnectionRepository.webSocketManager?.send(selectPayload.toString())
                                        
                                        showNewProjectDialog = false
                                        scope.launch { drawerState.close() }
                                    }
                                }
                            ) {
                                Text("Create")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNewProjectDialog = false }, modifier = Modifier.testTag("btn_create_project_cancel")) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                val topProjects = allProjects.take(5)
                val remainingProjects = allProjects.drop(5)
                
                if (allProjects.isEmpty()) {
                    Text("No projects found", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
                } else {
                    topProjects.forEach { proj ->
                        NavigationDrawerItem(
                            label = { Text(proj) },
                            selected = (proj == currentProject),
                            onClick = { 
                                ConnectionRepository.setCurrentProject(proj)
                                val payload = org.json.JSONObject().apply {
                                    put("event", "select_project")
                                    put("project", proj)
                                }
                                ConnectionRepository.webSocketManager?.send(payload.toString())
                                scope.launch { drawerState.close() } 
                            },
                            modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer_project_${proj}")
                        )
                    }
                    
                    if (remainingProjects.isNotEmpty()) {
                        NavigationDrawerItem(
                            label = { Text(if (showMoreProjects) "Show Less" else "More Projects...") },
                            selected = false,
                            onClick = { showMoreProjects = !showMoreProjects },
                            modifier = Modifier.padding(horizontal = 12.dp).testTag("btn_more_projects_toggle")
                        )
                        
                        if (showMoreProjects) {
                            remainingProjects.forEach { proj ->
                                NavigationDrawerItem(
                                    label = { Text(proj) },
                                    selected = (proj == currentProject),
                                    onClick = { 
                                        ConnectionRepository.setCurrentProject(proj)
                                        val payload = org.json.JSONObject().apply {
                                            put("event", "select_project")
                                            put("project", proj)
                                        }
                                        ConnectionRepository.webSocketManager?.send(payload.toString())
                                        scope.launch { drawerState.close() } 
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer_project_${proj}")
                                )
                            }
                        }
                    }
                }
                

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = { 
                        scope.launch { drawerState.close() }
                        showSettingsDialog = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("btn_drawer_settings")
                )
                Spacer(modifier = Modifier.weight(1f))
                

            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        modifier = Modifier.testTag("top_app_bar"),
                        title = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (currentProject.isNotEmpty()) currentProject else "Tether",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("txt_top_app_bar_title")
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                AnimatedContent(
                                    targetState = connectionStatus,
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                    },
                                    label = "connectionBadge"
                                ) { status ->
                                    if (status == "Connected") {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(StatusConnected)
                                                .testTag("status_indicator_dot")
                                        )
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = if (status == "Connecting...") StatusConnecting.copy(alpha=0.2f) else StatusDisconnected.copy(alpha=0.2f),
                                        ) {
                                            Text(
                                                text = status,
                                                color = if (status == "Connecting...") StatusConnecting else StatusDisconnected,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.testTag("btn_drawer_menu")) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Turbo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("txt_turbo_mode_label")
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Switch(
                                    checked = state.isTurboMode,
                                    onCheckedChange = { isChecked ->
                                        ConnectionRepository.updateTurboMode(isChecked)
                                    },
                                    modifier = Modifier.scale(0.7f).testTag("switch_turbo_mode")
                                )
                            }
                            IconButton(onClick = { showVoiceSettings = true }, modifier = Modifier.testTag("btn_voice_settings")) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                            IconButton(onClick = { showArtifactsSheet = true }, modifier = Modifier.testTag("btn_artifacts_sheet")) {
                                Icon(Icons.Default.List, contentDescription = "Artifacts")
                            }
                            IconButton(onClick = { isTrackpadVisible = true }, modifier = Modifier.testTag("btn_trackpad_toggle")) {
                                Icon(Icons.Default.Edit, contentDescription = "Trackpad")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = DarkBackground
                        )
                    )
                    // Slim status bar removed
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val wireframeAlpha by animateFloatAsState(
                    targetValue = if (chatMessages.isEmpty()) 1.0f else 0.15f,
                    animationSpec = tween(1000),
                    label = "wireframeAlpha"
                )
                AnimatedWireframeBackground(modifier = Modifier.fillMaxSize().alpha(wireframeAlpha))
                
                if (showVoiceSettings) {
                    VoiceSettingsBottomSheet(onDismiss = { showVoiceSettings = false })
                }
                if (showArtifactsSheet) {
                    ArtifactsBottomSheet(
                        artifacts = state.artifactHistory.values.toList(),
                        onDismiss = { showArtifactsSheet = false },
                        onArtifactSelected = {
                            ConnectionRepository.setArtifact(it)
                            showArtifactsSheet = false
                        }
                    )
                }
                val infiniteTransition = rememberInfiniteTransition()
                val color1 by infiniteTransition.animateColor(
                    initialValue = AuroraAmber,
                    targetValue = AuroraOrange,
                    animationSpec = infiniteRepeatable(animation = tween(3000), repeatMode = RepeatMode.Reverse),
                    label = "c1"
                )
                val color2 by infiniteTransition.animateColor(
                    initialValue = AuroraOrange,
                    targetValue = AuroraMagenta,
                    animationSpec = infiniteRepeatable(animation = tween(4500), repeatMode = RepeatMode.Reverse),
                    label = "c2"
                )
                val color3 by infiniteTransition.animateColor(
                    initialValue = AuroraMagenta,
                    targetValue = AuroraAmber,
                    animationSpec = infiniteRepeatable(animation = tween(6000), repeatMode = RepeatMode.Reverse),
                    label = "c3"
                )
                
                val auroraHeight by animateFloatAsState(
                    targetValue = if (isThinking) 0.5f else 0.05f,
                    animationSpec = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
                    label = "height"
                )
                val auroraAlpha by animateFloatAsState(
                    targetValue = if (isThinking) 0.6f else 0.1f,
                    animationSpec = tween(durationMillis = 1500),
                    label = "alpha"
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(auroraHeight)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    color1.copy(alpha = auroraAlpha),
                                    color2.copy(alpha = auroraAlpha * 0.7f),
                                    color3.copy(alpha = auroraAlpha * 0.4f),
                                    Color.Transparent
                                )
                            )
                        )
                        .align(Alignment.TopCenter)
                )
                
                if (showInitialNetworkPrompt && !hasPromptedForNetwork) {
                    AlertDialog(
                        onDismissRequest = { 
                            showInitialNetworkPrompt = false
                            hasPromptedForNetwork = true
                        },
                        title = { Text("Configure Home Network?") },
                        text = { Text("It looks like you haven't set up a trusted home network yet. This is required for automatic local discovery of the Rover Receiver. Are you at home right now, and would you like to trust this Wi-Fi network?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showInitialNetworkPrompt = false
                                hasPromptedForNetwork = true
                                initialNetworkPermissionLauncher.launch(arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                ))
                            }) {
                                Text("Yes, Trust Network")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { 
                                showInitialNetworkPrompt = false
                                hasPromptedForNetwork = true
                            }) {
                                Text("Not Right Now")
                            }
                        }
                    )
                }
                
                if (showSettingsDialog) {
                    var biometricsEnabled by remember { mutableStateOf(SecurityManager.isBiometricsEnabled) }
                    
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                            scope.launch {
                                val ssid = ConnectionRepository.networkManager?.getCurrentSsid()
                                if (ssid != null && ssid != "<unknown ssid>") {
                                    val loc = LocationVerifier.getCurrentLocation(context)
                                    if (loc != null) {
                                        ConnectionRepository.addTrustedNetwork(TrustedNetwork(ssid, loc.latitude, loc.longitude))
                                    }
                                }
                            }
                        }
                    }

                    AlertDialog(
                        modifier = Modifier.testTag("dialog_settings"),
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text("Settings") },
                        text = {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Require Biometrics")
                                    Switch(
                                        checked = biometricsEnabled,
                                        onCheckedChange = { 
                                            biometricsEnabled = it
                                            SecurityManager.isBiometricsEnabled = it
                                        },
                                        modifier = Modifier.testTag("switch_biometrics")
                                    )
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                
                                Text("Geofenced Trusted Networks", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Your location data and trusted networks are stored securely on this device. They never leave your phone and are never sent to any cloud server.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                
                                appState.trustedNetworks.forEach { net ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(net.ssid, style = MaterialTheme.typography.bodyMedium)
                                            Text("Lat: ${"%.4f".format(net.lat)}, Lng: ${"%.4f".format(net.lng)}", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                                        }
                                        IconButton(onClick = { ConnectionRepository.removeTrustedNetwork(net.ssid) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                                        }
                                    }
                                }
                                
                                TextButton(
                                    onClick = {
                                        permissionLauncher.launch(arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        ))
                                    },
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add Current Wi-Fi")
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSettingsDialog = false }, modifier = Modifier.testTag("btn_settings_close")) {
                                Text("Close")
                            }
                        }
                    )
                }
                
                // Main Chat Area
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                
                androidx.compose.runtime.LaunchedEffect(chatMessages.size) {
                    if (chatMessages.isNotEmpty()) {
                        listState.animateScrollToItem(0)
                    }
                }
                
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        // Empty state
                        androidx.compose.animation.AnimatedVisibility(
                            visible = chatMessages.isEmpty(),
                            enter = androidx.compose.animation.fadeIn(animationSpec = tween(1000)),
                            exit = androidx.compose.animation.fadeOut(animationSpec = tween(500)),
                            modifier = Modifier.align(Alignment.Center).testTag("chat_empty_state")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "🚀",
                                    fontSize = 48.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Welcome to Tether",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary,
                                    modifier = Modifier.testTag("txt_welcome_title")
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    if (connectionStatus == "Connected") "Ready for Rover" else "Connecting...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (connectionStatus == "Connected") Amber else TextSecondary,
                                    modifier = Modifier.testTag("txt_welcome_subtitle")
                                )
                            }
                        }
                        SelectionContainer {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                reverseLayout = true,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                val reversed = chatMessages.asReversed()
                                items(reversed.size) { index ->
                                    val msg = reversed[index]
                                    val isUser = msg.role == "user"
                                    val prevMsg = if (index > 0) reversed[index - 1] else null
                                    val showRoleLabel = prevMsg == null || prevMsg.role != msg.role
                                    
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                                    ) {
                                        if (showRoleLabel) {
                                            Text(
                                                text = if (isUser) "You" else "Rover",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isUser) AccentBlue.copy(alpha = 0.7f) else Amber.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                            )
                                        }
                                        
                                        Surface(
                                            shape = if (showRoleLabel) {
                                                if (isUser) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                                            } else {
                                                RoundedCornerShape(16.dp)
                                            },
                                            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.fillMaxWidth(0.85f)
                                        ) {
                                            if (isUser) {
                                                Text(
                                                    text = msg.message,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            } else {
                                                MarkdownText(
                                                    markdown = msg.message,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    textColor = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } // End of Box wrapping Empty State and LazyColumn
                        
                    // Thinking UI
                    if (isThinking) {
                        val thinkingStartTime = ConnectionRepository.state.collectAsState().value.thinkingStartTime
                            val currentThoughts = ConnectionRepository.state.collectAsState().value.currentThoughts
                            var elapsedSeconds by remember { mutableStateOf(0L) }
                            
                            LaunchedEffect(thinkingStartTime) {
                                if (thinkingStartTime != null) {
                                    while (true) {
                                        elapsedSeconds = (System.currentTimeMillis() - thinkingStartTime) / 1000
                                        delay(1000)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Animated bouncing dots
                                val infiniteTransition = rememberInfiniteTransition(label = "dots")
                                (0..2).forEach { i ->
                                    val offset by infiniteTransition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = -8f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(400, easing = FastOutSlowInEasing, delayMillis = i * 120),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "dot$i"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 3.dp)
                                            .offset(y = offset.dp)
                                            .size(8.dp)
                                            .background(
                                                Amber.copy(alpha = 0.8f),
                                                shape = androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Thinking · ${elapsedSeconds}s",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Amber.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                // Stop button — sends Ctrl+D to Antigravity via CDP
                                IconButton(
                                    onClick = {
                                        val json = JSONObject().apply {
                                            put("event", "stop")
                                        }
                                        ConnectionRepository.webSocketManager?.send(json.toString())
                                        ConnectionRepository.setThinking(false)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Stop Agent",
                                        tint = Color(0xFFEF5350),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (currentThoughts.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DarkSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = currentThoughts,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(10.dp),
                                        maxLines = 3
                                    )
                                }
                            }
                        }
                        
                        // Approval Request UI
                        AnimatedVisibility(
                            visible = currentApprovalRequest != null,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            currentApprovalRequest?.let { request ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    tonalElevation = 8.dp,
                                    shadowElevation = 4.dp
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = request.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            request.options.forEachIndexed { index, optionText ->
                                                Button(
                                                    onClick = {
                                                        val responseJson = JSONObject().apply {
                                                            put("event", "approve")
                                                            put("option_index", index)
                                                            put("option_text", optionText)
                                                        }
                                                        ConnectionRepository.webSocketManager?.send(responseJson.toString())
                                                        ConnectionRepository.addChatMessage(ChatMessage("system", "📤 Sent approval: $optionText (index=$index)"))
                                                        ConnectionRepository.setApprovalRequest(null)
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.primary,
                                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                                    )
                                                ) {
                                                    Text(optionText)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    
                    // Slash Commands Drop-Up Menu
                    val query = chatInput.text
                    val isCommandMode = query.startsWith("/")
                    val filteredCommands = if (isCommandMode) {
                        SYSTEM_COMMANDS.filter { it.command.startsWith(query, ignoreCase = true) }
                    } else emptyList()

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isCommandMode && filteredCommands.isNotEmpty(),
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = DarkSurfaceVariant,
                            tonalElevation = 8.dp,
                            shadowElevation = 8.dp,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp)
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(filteredCommands) { cmd ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                chatInput = TextFieldValue(
                                                    text = "${cmd.command} ",
                                                    selection = TextRange(cmd.command.length + 1)
                                                )
                                            }
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = cmd.command,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Amber,
                                            modifier = Modifier.width(140.dp)
                                        )
                                        Text(
                                            text = cmd.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Mode Selector
                    val interactionMode = state.interactionMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        data class ModeOption(
                            val mode: InteractionMode,
                            val icon: androidx.compose.ui.graphics.vector.ImageVector,
                            val label: String
                        )
                        val modeOptions = listOf(
                            ModeOption(InteractionMode.HANDS_FREE, Icons.Default.Mic, "Hands-Free"),
                            ModeOption(InteractionMode.AUTO_READ, Icons.Filled.VolumeUp, "Auto-Read"),
                            ModeOption(InteractionMode.NOTIFY, Icons.Default.Notifications, "Notify"),
                            ModeOption(InteractionMode.SILENT, Icons.Filled.Chat, "Silent")
                        )
                        modeOptions.forEach { option ->
                            val isSelected = interactionMode == option.mode
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) Amber.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                border = if (isSelected) BorderStroke(1.dp, Amber) else null,
                                modifier = Modifier.clickable {
                                    ConnectionRepository.updateInteractionMode(option.mode)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = option.icon,
                                        contentDescription = option.label,
                                        tint = if (isSelected) Amber else TextSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = option.label,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Amber else TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // Model Selector (collapsible)
                    var modelSelectorExpanded by remember { mutableStateOf(false) }
                    val modelOptions = listOf(
                        "3.5 Flash" to "Gemini 3.5 Flash (High)",
                        "3.1 Pro" to "Gemini 3.1 Pro (High)",
                        "Sonnet 4.6" to "Claude Sonnet 4.6 (Thinking)",
                        "Opus 4.6" to "Claude Opus 4.6 (Thinking)"
                    )

                    // Expandable chip row (expands upward, above the toggle)
                    AnimatedVisibility(
                        visible = modelSelectorExpanded,
                        enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom),
                        exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            modelOptions.forEach { option ->
                                val isSelected = state.currentModel == option.second
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) Amber.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                    border = if (isSelected) BorderStroke(1.dp, Amber) else null,
                                    modifier = Modifier.clickable {
                                        ConnectionRepository.updateCurrentModel(option.second)
                                        modelSelectorExpanded = false
                                    }
                                ) {
                                    Text(
                                        text = option.first,
                                        fontSize = 12.sp,
                                        color = if (isSelected) Amber else TextSecondary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Toggle bar: divider + chevron + label
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { modelSelectorExpanded = !modelSelectorExpanded }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Divider(
                            modifier = Modifier.weight(1f),
                            color = TextSecondary.copy(alpha = 0.3f),
                            thickness = 0.5.dp
                        )
                        Icon(
                            imageVector = if (modelSelectorExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = "Toggle model selector",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp).padding(horizontal = 2.dp)
                        )
                        Text(
                            text = "Change model",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Divider(
                            modifier = Modifier.weight(1f),
                            color = TextSecondary.copy(alpha = 0.3f),
                            thickness = 0.5.dp
                        )
                    }
                    
                    // Running Tasks UI
                    val runningTasks = state.runningTasks
                    if (runningTasks.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Running Tasks",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            runningTasks.forEach { task ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DarkSurfaceVariant,
                                    border = BorderStroke(1.dp, DividerColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = task.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        
                                        Spacer(modifier = Modifier.width(8.dp))
                                        
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.Transparent,
                                            border = BorderStroke(1.dp, DividerColor),
                                            modifier = Modifier.clickable {
                                                ConnectionRepository.webSocketManager?.send(org.json.JSONObject().apply {
                                                    put("event", "stop_task")
                                                    put("taskId", task.id)
                                                }.toString())
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Stop,
                                                    contentDescription = "Stop Task",
                                                    tint = Color(0xFFF87171), // Red-400
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Stop",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFF87171)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Queued Messages UI
                    val queuedMessages = state.queuedMessages
                    if (queuedMessages.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Queued Messages",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            queuedMessages.forEachIndexed { index, msg ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DarkSurfaceVariant,
                                    border = BorderStroke(1.dp, DividerColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = msg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Edit Button
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                                modifier = Modifier.clickable {
                                                    chatInput = TextFieldValue(msg)
                                                    val json = JSONObject().apply {
                                                        put("event", "manage_queue")
                                                        put("index", index)
                                                        put("action", "delete")
                                                    }
                                                    ConnectionRepository.webSocketManager?.send(json.toString())
                                                }
                                            ) {
                                                Text(
                                                    "Edit",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                )
                                            }

                                            // Send Now Button
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Amber.copy(alpha = 0.2f),
                                                modifier = Modifier.clickable {
                                                    val json = JSONObject().apply {
                                                        put("event", "manage_queue")
                                                        put("index", index)
                                                        put("action", "send")
                                                    }
                                                    ConnectionRepository.webSocketManager?.send(json.toString())
                                                }
                                            ) {
                                                Text(
                                                    "Send Now",
                                                    fontSize = 11.sp,
                                                    color = Amber,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                )
                                            }
                                            
                                            Spacer(modifier = Modifier.weight(1f))

                                            // Delete Button
                                            IconButton(
                                                onClick = {
                                                    val json = JSONObject().apply {
                                                        put("event", "manage_queue")
                                                        put("index", index)
                                                        put("action", "delete")
                                                    }
                                                    ConnectionRepository.webSocketManager?.send(json.toString())
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete",
                                                    tint = Color(0xFFEF5350),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Chat Input box
                    if (selectedImageBitmap != null) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .size(120.dp)
                        ) {
                            Image(
                                bitmap = selectedImageBitmap!!.asImageBitmap(),
                                contentDescription = "Attached Image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            IconButton(
                                onClick = { 
                                    selectedImageBitmap = null
                                    selectedImageBase64 = null
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove image",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            try {
                                photoPickerLauncher.launch("image/*")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, modifier = Modifier.testTag("btn_attach_image")) {
                            Icon(Icons.Default.Add, contentDescription = "Attach Image")
                        }
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier.weight(1f).testTag("input_chat_message"),
                            placeholder = { Text("Message Rover...") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if ((chatInput.text.isNotBlank() || selectedImageBase64 != null) && connectionStatus == "Connected") {
                                        if (selectedImageBase64 != null) {
                                            val imageJson = JSONObject().apply {
                                                put("event", "image")
                                                put("data", selectedImageBase64)
                                            }
                                            ConnectionRepository.webSocketManager?.send(imageJson.toString())
                                            selectedImageBitmap = null
                                            selectedImageBase64 = null
                                        }
                                        if (chatInput.text.isNotBlank()) {
                                            val json = JSONObject().apply {
                                                put("event", "chat")
                                                put("message", chatInput.text)
                                                put("project", ConnectionRepository.state.value.currentProject)
                                            }
                                            ConnectionRepository.webSocketManager?.send(json.toString())
                                            chatInput = TextFieldValue("")
                                        }
                                        ConnectionRepository.setThinking(true)
                                    }
                                }
                            )
                        )
                        IconButton(onClick = {
                            val intent = Intent(context, RoverService::class.java).apply {
                                action = "com.rover.remote.TOGGLE_MIC"
                            }
                            context.startService(intent)
                        }, modifier = Modifier.testTag("btn_toggle_mic")) {
                            val iconColor = if (isMicListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            Icon(Icons.Default.Mic, contentDescription = "Toggle Mic", tint = iconColor)
                        }
                        IconButton(
                            onClick = {
                                if ((chatInput.text.isNotBlank() || selectedImageBase64 != null) && connectionStatus == "Connected") {
                                    if (selectedImageBase64 != null) {
                                        val imageJson = JSONObject().apply {
                                            put("event", "image")
                                            put("data", selectedImageBase64)
                                        }
                                        ConnectionRepository.webSocketManager?.send(imageJson.toString())
                                        selectedImageBitmap = null
                                        selectedImageBase64 = null
                                    }
                                    if (chatInput.text.isNotBlank()) {
                                        val json = JSONObject().apply {
                                            put("event", "chat")
                                            put("message", chatInput.text)
                                            put("project", ConnectionRepository.state.value.currentProject)
                                        }
                                        ConnectionRepository.webSocketManager?.send(json.toString())
                                        chatInput = TextFieldValue("")
                                    }
                                    ConnectionRepository.setThinking(true)
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isThinking && chatInput.text.isBlank() && selectedImageBase64 == null) DarkSurfaceVariant else MaterialTheme.colorScheme.primary,
                                disabledContainerColor = DarkSurfaceVariant
                            ),
                            enabled = connectionStatus == "Connected" && (chatInput.text.isNotBlank() || selectedImageBase64 != null),
                            modifier = Modifier.testTag("btn_send_chat")
                        ) {
                            AnimatedContent(
                                targetState = isThinking && chatInput.text.isBlank() && selectedImageBase64 == null,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                                },
                                label = "sendButtonIcon"
                            ) { showSpinner ->
                                if (showSpinner) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Amber,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                }
                
                // Artifact Panel (Slide in from right)
                AnimatedVisibility(
                    visible = currentArtifact != null,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.92f).testTag("artifact_panel")
                ) {
                    Surface(
                        color = DarkSurface,
                        shadowElevation = 16.dp,
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkSurfaceVariant)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentArtifact?.title ?: "",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        maxLines = 2,
                                        modifier = Modifier.testTag("txt_artifact_title")
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = { ConnectionRepository.setArtifact(null) }, modifier = Modifier.testTag("btn_close_artifact")) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                                }
                            }
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                            // Markdown content
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                                    .testTag("container_artifact_content")
                            ) {
                                val androidClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                androidx.compose.runtime.DisposableEffect(currentArtifact) {
                                    val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                                        val clip = androidClipboard.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            val text = clip.getItemAt(0).text?.toString()
                                            if (!text.isNullOrEmpty()) {
                                                quoteDialogText = text
                                            }
                                        }
                                    }
                                    androidClipboard.addPrimaryClipChangedListener(listener)
                                    onDispose {
                                        androidClipboard.removePrimaryClipChangedListener(listener)
                                    }
                                }
                                
                                SelectionContainer {
                                    MarkdownText(
                                        markdown = currentArtifact?.content ?: "",
                                        textColor = TextPrimary,
                                        codeBackground = DarkSurfaceElevated
                                    )
                                }
                                
                                if (quoteDialogText != null) {
                                    var noteText by remember { mutableStateOf("") }
                                    AlertDialog(
                                        onDismissRequest = { quoteDialogText = null },
                                        title = { Text("Reply to Section", color = MaterialTheme.colorScheme.onSurface) },
                                        text = {
                                            Column {
                                                Text(
                                                    text = "\"${quoteDialogText}\"",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                    modifier = Modifier.padding(bottom = 8.dp)
                                                )
                                                OutlinedTextField(
                                                    value = noteText,
                                                    onValueChange = { noteText = it },
                                                    label = { Text("Add a note...") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface)
                                                )
                                            }
                                        },
                                        confirmButton = {
                                            Button(onClick = {
                                                val msg = "> ${quoteDialogText?.replace("\n", "\n> ")}\n\n$noteText"
                                                val webSocketManager = ConnectionRepository.webSocketManager
                                                webSocketManager?.send(org.json.JSONObject().apply {
                                                    put("event", "chat")
                                                    put("message", msg)
                                                }.toString())
                                                ConnectionRepository.setArtifact(null) // Close artifact to show chat
                                                quoteDialogText = null
                                            }) {
                                                Text("Send")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { quoteDialogText = null }) {
                                                Text("Cancel")
                                            }
                                        },
                                        containerColor = DarkSurfaceElevated,
                                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                                        textContentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Trackpad Modal/Overlay
        if (isTrackpadVisible) {
            Dialog(onDismissRequest = { isTrackpadVisible = false }) {
                Surface(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f).testTag("dialog_trackpad"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Remote Control", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { isTrackpadVisible = false }, modifier = Modifier.testTag("btn_close_trackpad")) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        var remoteText by remember { mutableStateOf(TextFieldValue("")) }
                        
                        // Legacy live-typing input
                        OutlinedTextField(
                            value = remoteText,
                            onValueChange = { newValue ->
                                val oldText = remoteText.text
                                val newText = newValue.text
                                
                                var commonPrefixLength = 0
                                val minLength = minOf(oldText.length, newText.length)
                                while (commonPrefixLength < minLength && oldText[commonPrefixLength] == newText[commonPrefixLength]) {
                                    commonPrefixLength++
                                }
                                
                                val backspacesNeeded = oldText.length - commonPrefixLength
                                if (backspacesNeeded > 0 && connectionStatus == "Connected") {
                                    webSocketManager?.send(JSONObject().apply { 
                                        put("event", "keyboard_input_batch")
                                        put("key", "Backspace")
                                        put("count", backspacesNeeded)
                                    }.toString())
                                }
                                
                                val added = newText.substring(commonPrefixLength)
                                if (added.isNotEmpty()) {
                                    val characters = KeyMapper.splitIntoUnicodeCharacters(added)
                                    characters.forEach { symbol ->
                                        if (connectionStatus == "Connected") {
                                            webSocketManager?.send(JSONObject().apply { 
                                                put("event", "keyboard_input")
                                                put("key", if (symbol == "\n") "Enter" else symbol)
                                            }.toString())
                                        }
                                    }
                                }
                                remoteText = newValue
                            },
                            label = { Text("Live Typing (Mirrors to PC)") },
                            modifier = Modifier.fillMaxWidth().testTag("input_live_typing")
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Trackpad area
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .testTag("trackpad_touch_area")
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
                                                if (connectionStatus == "Connected") {
                                                    webSocketManager?.send(JSONObject().apply { put("event", "mouse_click"); put("button", "right") }.toString())
                                                }
                                            }
                                            
                                            val pointerId = down.id
                                            var totalDrag = Offset.Zero
                                            var accumulatedDx = 0f
                                            var accumulatedDy = 0f
                                            var lastSendTime = 0L
                                            
                                            do {
                                                val event = awaitPointerEvent()
                                                val dragChange = event.changes.firstOrNull { it.id == pointerId }
                                                
                                                if (dragChange != null) {
                                                    if (dragChange.pressed) {
                                                        val positionChange = dragChange.positionChange()
                                                        totalDrag += positionChange
                                                        
                                                        if (totalDrag.getDistance() > viewConfiguration.touchSlop) {
                                                            longPressJob.cancel()
                                                            if (!dragTriggered) dragTriggered = true
                                                            dragChange.consume()
                                                            
                                                            accumulatedDx += positionChange.x
                                                            accumulatedDy += positionChange.y
                                                            
                                                            val currentTime = System.currentTimeMillis()
                                                            if (currentTime - lastSendTime > 16) {
                                                                if (connectionStatus == "Connected") {
                                                                    webSocketManager?.send(JSONObject().apply {
                                                                        put("event", "mouse_move")
                                                                        put("dx", accumulatedDx.toDouble())
                                                                        put("dy", accumulatedDy.toDouble())
                                                                    }.toString())
                                                                }
                                                                accumulatedDx = 0f
                                                                accumulatedDy = 0f
                                                                lastSendTime = currentTime
                                                            }
                                                        }
                                                    } else {
                                                        longPressJob.cancel()
                                                        
                                                        // Send any remaining accumulated movement before processing click/up
                                                        if (accumulatedDx != 0f || accumulatedDy != 0f) {
                                                            if (connectionStatus == "Connected") {
                                                                webSocketManager?.send(JSONObject().apply {
                                                                    put("event", "mouse_move")
                                                                    put("dx", accumulatedDx.toDouble())
                                                                    put("dy", accumulatedDy.toDouble())
                                                                }.toString())
                                                            }
                                                        }
                                                        
                                                        if (!dragTriggered && !isLongPress) {
                                                            if (connectionStatus == "Connected") {
                                                                webSocketManager?.send(JSONObject().apply { put("event", "mouse_click"); put("button", "left") }.toString())
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
                            Text("Trackpad Area", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

class WebSocketManager {
    private var webSocket: WebSocket? = null
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.HTTP_CONNECT_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(Config.HTTP_READ_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(Config.HTTP_WRITE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(Config.WS_PING_INTERVAL_SEC, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun connect(url: String, listener: WebSocketListener) {
        disconnect()
        
        var finalUrl = url
        if (url.contains(Config.TAILSCALE_PORT_CHECK) || url.contains(Config.TAILSCALE_IP_PREFIX)) {
            try {
                val hostPort = url.substringAfter("://").substringBefore("/")
                synchronized(WebSocketManager::class.java) {
                    tsnet_wrapper.Tsnet_wrapper.setProxyTarget(hostPort)
                }
                finalUrl = url.replace(hostPort, Config.LOCAL_PROXY_ADDRESS)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        val request = Request.Builder().url(finalUrl).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        webSocket?.close(Config.WS_NORMAL_CLOSURE_CODE, "Disconnect")
        webSocket = null
    }

    fun isCurrentWebSocket(ws: WebSocket): Boolean {
        return webSocket == ws
    }

    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    fun sendBytes(data: okio.ByteString): Boolean {
        return webSocket?.send(data) ?: false
    }
}

// --- Added for Animated Wireframe Background and Voice Settings ---

data class Node(val relativeX: Float, val relativeY: Float, val icon: androidx.compose.ui.graphics.vector.ImageVector)
data class Edge(val start: Node, val end: Node)
data class Packet(val edge: Edge, val phaseOffset: Float)

@Composable
fun AnimatedWireframeBackground(modifier: Modifier = Modifier) {
    val nodes = remember {
        listOf(
            Node(0.1f, 0.2f, Icons.Default.Phone), 
            Node(0.8f, 0.1f, Icons.Default.Computer),
            Node(0.3f, 0.5f, Icons.Default.Router), 
            Node(0.9f, 0.6f, Icons.Default.Cloud),
            Node(0.2f, 0.8f, Icons.Default.Face), 
            Node(0.7f, 0.9f, Icons.Default.Storage)
        )
    }
    
    val edges = remember(nodes) {
        listOf(
            Edge(nodes[0], nodes[2]), Edge(nodes[2], nodes[4]),
            Edge(nodes[1], nodes[3]), Edge(nodes[3], nodes[5]),
            Edge(nodes[0], nodes[1]), Edge(nodes[2], nodes[3]),
            Edge(nodes[4], nodes[5])
        )
    }

    val packets = remember(edges) {
        edges.flatMap { edge ->
            listOf(
                Packet(edge, phaseOffset = 0.0f),
                Packet(edge, phaseOffset = 0.5f)
            )
        }
    }

    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    
    val globalPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 6000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        )
    )

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val density = androidx.compose.ui.platform.LocalDensity.current
        val wPx = with(density) { w.toPx() }
        val hPx = with(density) { h.toPx() }

        val wireColor = TextTertiary.copy(alpha = 0.15f)
        val packetColor = Color.Cyan.copy(alpha = 0.35f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            edges.forEach { edge ->
                drawLine(
                    color = wireColor,
                    start = Offset(edge.start.relativeX * wPx, edge.start.relativeY * hPx),
                    end = Offset(edge.end.relativeX * wPx, edge.end.relativeY * hPx),
                    strokeWidth = 6f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            packets.forEach { packet ->
                val currentProgress = (globalPhase + packet.phaseOffset) % 1.0f
                val startX = packet.edge.start.relativeX * wPx
                val startY = packet.edge.start.relativeY * hPx
                val endX = packet.edge.end.relativeX * wPx
                val endY = packet.edge.end.relativeY * hPx
                
                val currentX = startX + (endX - startX) * currentProgress
                val currentY = startY + (endY - startY) * currentProgress

                drawCircle(
                    color = packetColor,
                    radius = 14f,
                    center = Offset(currentX, currentY)
                )
            }
        }
        
        nodes.forEach { node ->
            Icon(
                imageVector = node.icon,
                contentDescription = null,
                tint = wireColor,
                modifier = Modifier
                    .offset(
                        x = w * node.relativeX - 12.dp,
                        y = h * node.relativeY - 12.dp
                    )
                    .size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsBottomSheet(
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    
    // Kokoro usually has voices mapped to indices 0-10 or similar
    // We will just provide a simple list of SIDs.
    val availableSids = (0..10).toList()
    var expanded by remember { mutableStateOf(false) }
    var selectedSid by remember { mutableStateOf(0) }
    var previewText by remember { mutableStateOf(TextFieldValue("This is a preview of my voice.")) }
    var showRestartDialog by remember { mutableStateOf(false) }
    
    val ttsManager = ConnectionRepository.ttsManager
    val context = androidx.compose.ui.platform.LocalContext.current

    if (showRestartDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Restart App") },
            text = { Text("Are you sure you want to fully restart the app?") },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        val componentName = intent?.component
                        val mainIntent = android.content.Intent.makeRestartActivityTask(componentName)
                        context.startActivity(mainIntent)
                        Runtime.getRuntime().exit(0)
                    }
                ) {
                    Text("Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("bottom_sheet_voice_settings")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Voice Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            
            // Dropdown for Voice SID
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.testTag("dropdown_voice_selector")
            ) {
                OutlinedTextField(
                    value = "Voice ID: $selectedSid",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Selected Voice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    availableSids.forEach { sid ->
                        DropdownMenuItem(
                            text = { Text("Voice ID: $sid") },
                            onClick = {
                                selectedSid = sid
                                ttsManager?.setVoice(sid)
                                expanded = false
                            },
                            modifier = Modifier.testTag("item_voice_sid_$sid")
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = previewText,
                onValueChange = { previewText = it },
                label = { Text("Preview Text") },
                modifier = Modifier.fillMaxWidth().testTag("input_voice_preview_text")
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { 
                    ttsManager?.speak(previewText.text)
                },
                modifier = Modifier.fillMaxWidth().testTag("btn_play_voice_preview")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Play Preview")
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun TailscaleAnimatedSwitch(
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnecting = status == "Connecting..."
    val isConnected = status == "Connected"
    val isTrackingOn = isConnecting || isConnected

    val thumbOffset by animateDpAsState(
        targetValue = if (isTrackingOn) 24.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "thumbOffset"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseColor by infiniteTransition.animateColor(
        initialValue = StatusConnecting.copy(alpha = 0.4f),
        targetValue = StatusConnecting,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseColor"
    )

    val trackColor by animateColorAsState(
        targetValue = when {
            isConnected -> StatusConnected
            isConnecting -> pulseColor
            else -> TextTertiary
        },
        animationSpec = tween(300),
        label = "trackColor"
    )

    Box(
        modifier = modifier
            .width(52.dp)
            .height(28.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(trackColor)
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .offset(x = thumbOffset)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White)
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsBottomSheet(
    artifacts: List<ArtifactMessage>,
    onDismiss: () -> Unit,
    onArtifactSelected: (ArtifactMessage) -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Artifact History",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            if (artifacts.isEmpty()) {
                Text(
                    "No artifacts received yet in this session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                    items(artifacts.reversed()) { artifact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArtifactSelected(artifact) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.List,
                                contentDescription = "Artifact",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Text(
                                text = artifact.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

