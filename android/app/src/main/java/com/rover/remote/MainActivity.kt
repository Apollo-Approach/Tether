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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
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
data class RoverHost(val name: String, val ip: String, val port: Int, val os: String = "")
data class ApprovalRequest(val title: String, val options: List<String>)

class NsdDiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_rover._tcp."
    
    private val _hosts = MutableStateFlow<List<RoverHost>>(emptyList())
    val hosts: StateFlow<List<RoverHost>> = _hosts
    
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    fun startDiscovery() {
        if (discoveryListener != null) return
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == serviceType || service.serviceType == "_rover._tcp.local.") {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val hostName = serviceInfo.serviceName
                            val ip = serviceInfo.host.hostAddress ?: return
                            val port = serviceInfo.port
                            
                            var os = ""
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                val osBytes = serviceInfo.attributes["os"]
                                if (osBytes != null) {
                                    os = String(osBytes, Charsets.UTF_8)
                                }
                            }
                            
                            val host = RoverHost(hostName, ip, port, os)
                            _hosts.update { current ->
                                if (current.none { it.name == host.name }) {
                                    val newList = current + host
                                    ConnectionRepository.updateDiscoveredHosts(newList)
                                    newList
                                } else current
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                val hostName = service.serviceName
                _hosts.update { current ->
                    val newList = current.filterNot { it.name == hostName }
                    ConnectionRepository.updateDiscoveredHosts(newList)
                    newList
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {}
        }
        discoveryListener = null
    }
}

class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SecurityManager.init(this)

        // Request notification and mic permission
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val ungranted = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) {
            requestPermissions(ungranted.toTypedArray(), 101)
        }

        // Start the background service
        val serviceIntent = Intent(this, RoverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        val nsdManager = NsdDiscoveryManager(this)
        nsdManager.startDiscovery()
        
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
    
    BackHandler(enabled = currentArtifact != null) {
        ConnectionRepository.setArtifact(null)
    }
    
    val scope = rememberCoroutineScope()
    val webSocketManager = ConnectionRepository.webSocketManager
    
    var urlInput by remember { mutableStateOf("ws://10.10.10.10:8080") }
    var showMoreProjects by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val discoveredHosts = state.discoveredHosts
    
    var isTrackpadVisible by remember { mutableStateOf(false) }
    var showVoiceSettings by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf(TextFieldValue("")) }
    val context = LocalContext.current
    
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && connectionStatus == "Connected") {
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    
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
                        
                        val json = JSONObject().apply {
                            put("event", "image")
                            put("data", base64Str)
                        }
                        webSocketManager?.send(json.toString())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Spacer(modifier = Modifier.height(16.dp))
                
                val tsStatus by TailscaleManager.status.collectAsState()
                
                Text("Remote Access", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { 
                        Column {
                            Text("Tailscale")
                            Text(tsStatus, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    },
                    selected = false,
                    onClick = {
                        TailscaleManager.start(context)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("Local Connection (Wi-Fi)", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                if (discoveredHosts.isEmpty()) {
                    Text("Scanning for hosts...", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                } else {
                    discoveredHosts.forEach { host ->
                        val hostUrl = "ws://${host.ip}:${host.port}"
                        NavigationDrawerItem(
                            label = { Text(host.name) },
                            selected = urlInput == hostUrl && connectionStatus == "Connected",
                            onClick = {
                                urlInput = hostUrl
                                ConnectionRepository.updateConnectionStatus("Connecting...")
                                val intent = Intent(context, RoverService::class.java).apply {
                                    action = "com.rover.remote.CONNECT"
                                    putExtra("url", hostUrl)
                                }
                                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                
                val peers by TailscaleManager.peers.collectAsState()
                if (peers.isNotEmpty()) {
                    Text("Remote Connection (Tailscale)", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                    val disableTailscalePeers = discoveredHosts.isNotEmpty() && connectionStatus == "Connected"
                    peers.filter { it.online }.forEach { peer ->
                        NavigationDrawerItem(
                            label = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val indicatorColor = if (disableTailscalePeers) TextTertiary else StatusConnected
                                    val textColor = if (disableTailscalePeers) TextTertiary else Color.Unspecified
                                    Box(modifier = Modifier.size(8.dp).background(indicatorColor, shape = androidx.compose.foundation.shape.CircleShape))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(peer.hostname, color = textColor)
                                }
                            },
                            selected = false,
                            onClick = {
                                if (disableTailscalePeers) return@NavigationDrawerItem
                                try {
                                    android.util.Log.e("RoverClick", "Clicked peer: ${peer.hostname}, IP: ${peer.ip}")
                                    if (connectionStatus == "Connected") {
                                        webSocketManager?.disconnect()
                                        ConnectionRepository.updateConnectionStatus("Disconnected")
                                    }
                                    ConnectionRepository.updateConnectionStatus("Connecting...")
                                    val targetHost = if (peer.ip.isNotBlank()) peer.ip else peer.hostname
                                    android.util.Log.e("RoverClick", "Target host: $targetHost")
                                    val intent = Intent(context, RoverService::class.java).apply {
                                        action = "com.rover.remote.CONNECT"
                                        putExtra("url", "ws://$targetHost:8765")
                                    }
                                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                    android.util.Log.e("RoverClick", "Started service, closing drawer...")
                                    scope.launch { 
                                        drawerState.close() 
                                        android.util.Log.e("RoverClick", "Drawer closed.")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("RoverClick", "Exception in onClick", e)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                
                if (showNewProjectDialog) {
                    AlertDialog(
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
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
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
                            TextButton(onClick = { showNewProjectDialog = false }) {
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
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    
                    if (remainingProjects.isNotEmpty()) {
                        NavigationDrawerItem(
                            label = { Text(if (showMoreProjects) "Show Less" else "More Projects...") },
                            selected = false,
                            onClick = { showMoreProjects = !showMoreProjects },
                            modifier = Modifier.padding(horizontal = 12.dp)
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
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
                
                val availableConversations = state.availableConversations
                if (availableConversations.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    Text("Active Agents", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    val orderedList = mutableListOf<Pair<ConversationInfo, Int>>()
                    fun addNode(node: ConversationInfo, depth: Int) {
                        orderedList.add(node to depth)
                        availableConversations.filter { it.parentId == node.id }.forEach { addNode(it, depth + 1) }
                    }
                    availableConversations.filter { it.parentId.isEmpty() }.forEach { addNode(it, 0) }
                    val addedIds = orderedList.map { it.first.id }.toSet()
                    availableConversations.filter { it.id !in addedIds }.forEach { orderedList.add(it to 0) }

                    orderedList.forEach { (conv, depth) ->
                        val horizontalPad = 12 + (depth * 24)
                        NavigationDrawerItem(
                            label = { 
                                Column {
                                    val isSubagent = depth > 0
                                    val prefix = if (isSubagent) "↳ " else ""
                                    Text(prefix + conv.id.take(8) + "...", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        conv.firstMessage, 
                                        style = MaterialTheme.typography.bodySmall, 
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            selected = (conv.id == state.activeConversationId),
                            onClick = {
                                val payload = org.json.JSONObject().apply {
                                    put("event", "select_conversation")
                                    put("conversationId", conv.id)
                                }
                                ConnectionRepository.webSocketManager?.send(payload.toString())
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(start = horizontalPad.dp, end = 12.dp)
                        )
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
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Manual WebSocket URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = connectionStatus == "Disconnected"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (connectionStatus == "Connected") {
                                webSocketManager?.disconnect()
                                ConnectionRepository.updateConnectionStatus("Disconnected")
                            } else {
                                ConnectionRepository.updateConnectionStatus("Connecting...")
                                val intent = Intent(context, RoverService::class.java).apply {
                                    action = "com.rover.remote.CONNECT"
                                    putExtra("url", urlInput)
                                }
                                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (connectionStatus == "Connected") "Disconnect" else "Connect")
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { 
                            Column {
                                Text(
                                    text = if (currentProject.isNotEmpty()) currentProject else "Tether",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Turbo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Switch(
                                    checked = state.isTurboMode,
                                    onCheckedChange = { isChecked ->
                                        ConnectionRepository.updateTurboMode(isChecked)
                                    },
                                    modifier = Modifier.scale(0.7f)
                                )
                            }
                            IconButton(onClick = { showVoiceSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                            IconButton(onClick = { isTrackpadVisible = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Trackpad")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = DarkBackground
                        )
                    )
                    // Slim status bar
                    val statusBarColor = when (connectionStatus) {
                        "Connected" -> StatusConnected
                        "Connecting..." -> StatusConnecting
                        else -> StatusDisconnected
                    }
                    val statusText = when {
                        connectionStatus == "Connected" && currentProject.isNotEmpty() -> {
                            var text = "Connected · $currentProject"
                            if (state.activeConversationId.isNotEmpty()) {
                                text += " · Agent: ${state.activeConversationId.take(8)}"
                            }
                            text
                        }
                        connectionStatus == "Connected" -> {
                            var text = "Connected"
                            if (state.activeConversationId.isNotEmpty()) {
                                text += " · Agent: ${state.activeConversationId.take(8)}"
                            }
                            text
                        }
                        connectionStatus == "Connecting..." -> "Connecting..."
                        else -> "Disconnected"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(statusBarColor, shape = androidx.compose.foundation.shape.CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
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
                
                if (showSettingsDialog) {
                    var biometricsEnabled by remember { mutableStateOf(SecurityManager.isBiometricsEnabled) }
                    AlertDialog(
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
                                        }
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSettingsDialog = false }) {
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
                            modifier = Modifier.align(Alignment.Center)
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
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    if (connectionStatus == "Connected") "Ready for Rover" else "Connecting...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (connectionStatus == "Connected") Amber else TextSecondary
                                )
                            }
                        }

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
                                        shape = if (isUser) {
                                            RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                                        } else {
                                            RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Attach Image")
                        }
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message Rover...") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (chatInput.text.isNotBlank() && connectionStatus == "Connected") {
                                        val json = JSONObject().apply {
                                            put("event", "chat")
                                            put("message", chatInput.text)
                                            put("project", ConnectionRepository.state.value.currentProject)
                                        }
                                        ConnectionRepository.webSocketManager?.send(json.toString())
                                        chatInput = TextFieldValue("")
                                        ConnectionRepository.setThinking(true)
                                    }
                                }
                            )
                        )
                        IconButton(
                            onClick = {
                                if (chatInput.text.isNotBlank() && connectionStatus == "Connected" && !isThinking) {
                                    val json = JSONObject().apply {
                                        put("event", "chat")
                                        put("message", chatInput.text)
                                        put("project", ConnectionRepository.state.value.currentProject)
                                    }
                                    ConnectionRepository.webSocketManager?.send(json.toString())
                                    chatInput = TextFieldValue("")
                                    ConnectionRepository.setThinking(true)
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isThinking) DarkSurfaceVariant else MaterialTheme.colorScheme.primary,
                                disabledContainerColor = DarkSurfaceVariant
                            ),
                            enabled = !isThinking && connectionStatus == "Connected"
                        ) {
                            AnimatedContent(
                                targetState = isThinking,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                                },
                                label = "sendButtonIcon"
                            ) { thinking ->
                                if (thinking) {
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
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.92f)
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
                                        maxLines = 2
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = { ConnectionRepository.setArtifact(null) }) {
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
                            ) {
                                MarkdownText(
                                    markdown = currentArtifact?.content ?: "",
                                    textColor = TextPrimary,
                                    codeBackground = DarkSurfaceElevated
                                )
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
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Remote Control", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { isTrackpadVisible = false }) {
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
                                for (i in 0 until backspacesNeeded) {
                                    if (connectionStatus == "Connected") {
                                        webSocketManager?.send(JSONObject().apply { put("event", "keyboard_input"); put("key", "Backspace") }.toString())
                                    }
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
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Trackpad area
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
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
                                                            
                                                            if (connectionStatus == "Connected") {
                                                                webSocketManager?.send(JSONObject().apply {
                                                                    put("event", "mouse_move")
                                                                    put("dx", positionChange.x.toDouble())
                                                                    put("dy", positionChange.y.toDouble())
                                                                }.toString())
                                                            }
                                                        }
                                                    } else {
                                                        longPressJob.cancel()
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
    private var client: OkHttpClient = OkHttpClient()

    fun connect(url: String, listener: WebSocketListener) {
        disconnect()
        
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)

        var finalUrl = url
        if (url.contains(":8765") || url.contains("100.")) {
            try {
                val hostPort = url.substringAfter("://").substringBefore("/")
                tsnet_wrapper.Tsnet_wrapper.setProxyTarget(hostPort)
                finalUrl = url.replace(hostPort, "127.0.0.1:1080")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        client = builder.build()
        
        val request = Request.Builder().url(finalUrl).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnect")
        webSocket = null
    }

    fun isCurrentWebSocket(ws: WebSocket): Boolean {
        return webSocket == ws
    }

    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
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
        sheetState = sheetState
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
                onExpandedChange = { expanded = !expanded }
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
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = previewText,
                onValueChange = { previewText = it },
                label = { Text("Preview Text") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { 
                    ttsManager?.speak(previewText.text)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Play Preview")
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

