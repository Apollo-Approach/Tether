package com.antigravity.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
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
data class AntigravityHost(val name: String, val ip: String, val port: Int, val os: String = "")
data class ApprovalRequest(val title: String, val options: List<String>)

class NsdDiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_antigravity._tcp."
    
    private val _hosts = MutableStateFlow<List<AntigravityHost>>(emptyList())
    val hosts: StateFlow<List<AntigravityHost>> = _hosts
    
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    fun startDiscovery() {
        if (discoveryListener != null) return
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == serviceType || service.serviceType == "_antigravity._tcp.local.") {
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
                            
                            val host = AntigravityHost(hostName, ip, port, os)
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Start the background service
        val serviceIntent = Intent(this, AntigravityService::class.java)
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
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RemoteControlScreen()
                }
            }
        }
    }
}

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
    
    val scope = rememberCoroutineScope()
    val webSocketManager = ConnectionRepository.webSocketManager
    
    var urlInput by remember { mutableStateOf("ws://10.10.10.10:8080") }
    var showMoreProjects by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val discoveredHosts = state.discoveredHosts
    
    var isTrackpadVisible by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
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
                            Text(tsStatus, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    },
                    selected = false,
                    onClick = {
                        TailscaleManager.start(context)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("Available Hosts", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                if (discoveredHosts.isEmpty()) {
                    Text("Scanning for hosts...", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                } else {
                    discoveredHosts.forEach { host ->
                        val hostUrl = "ws://${host.ip}:${host.port}"
                        NavigationDrawerItem(
                            label = { Text(host.name) },
                            selected = urlInput == hostUrl && connectionStatus == "Connected",
                            onClick = {
                                urlInput = hostUrl
                                ConnectionRepository.updateConnectionStatus("Connecting...")
                                val intent = Intent(context, AntigravityService::class.java).apply {
                                    action = "com.antigravity.remote.CONNECT"
                                    putExtra("url", hostUrl)
                                }
                                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                val peers by TailscaleManager.peers.collectAsState()
                if (peers.isNotEmpty()) {
                    Text("Available PCs", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                    peers.filter { it.online }.forEach { peer ->
                        NavigationDrawerItem(
                            label = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(8.dp).background(Color.Green, shape = androidx.compose.foundation.shape.CircleShape))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(peer.hostname)
                                }
                            },
                            selected = false,
                            onClick = {
                                try {
                                    android.util.Log.e("AntigravityClick", "Clicked peer: ${peer.hostname}, IP: ${peer.ip}")
                                    if (connectionStatus == "Connected") {
                                        webSocketManager?.disconnect()
                                        ConnectionRepository.updateConnectionStatus("Disconnected")
                                    }
                                    ConnectionRepository.updateConnectionStatus("Connecting...")
                                    val targetHost = if (peer.ip.isNotBlank()) peer.ip else peer.hostname
                                    android.util.Log.e("AntigravityClick", "Target host: $targetHost")
                                    val intent = Intent(context, AntigravityService::class.java).apply {
                                        action = "com.antigravity.remote.CONNECT"
                                        putExtra("url", "ws://$targetHost:8765")
                                        putExtra("useProxy", true)
                                    }
                                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                    android.util.Log.e("AntigravityClick", "Started service, closing drawer...")
                                    scope.launch { 
                                        drawerState.close() 
                                        android.util.Log.e("AntigravityClick", "Drawer closed.")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("AntigravityClick", "Exception in onClick", e)
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
                
                val topProjects = allProjects.take(5)
                val remainingProjects = allProjects.drop(5)
                
                if (allProjects.isEmpty()) {
                    Text("No projects found", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                } else {
                    topProjects.forEach { proj ->
                        NavigationDrawerItem(
                            label = { Text(proj) },
                            selected = (proj == currentProject),
                            onClick = { 
                                ConnectionRepository.setCurrentProject(proj)
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
                                        scope.launch { drawerState.close() } 
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp)
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
                                val intent = Intent(context, AntigravityService::class.java).apply {
                                    action = "com.antigravity.remote.CONNECT"
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
                TopAppBar(
                    title = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Antigravity")
                            Spacer(modifier = Modifier.width(8.dp))
                            val indicatorColor = when (connectionStatus) {
                                "Connected" -> Color.Green
                                "Connecting..." -> Color.Yellow
                                else -> Color.Red
                            }
                            Box(modifier = Modifier.size(8.dp).background(indicatorColor, shape = androidx.compose.foundation.shape.CircleShape))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { isTrackpadVisible = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Trackpad")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val infiniteTransition = rememberInfiniteTransition()
                val color1 by infiniteTransition.animateColor(
                    initialValue = Color(0xFF00FFAA),
                    targetValue = Color(0xFF0055FF),
                    animationSpec = infiniteRepeatable(animation = tween(3000), repeatMode = RepeatMode.Reverse),
                    label = "c1"
                )
                val color2 by infiniteTransition.animateColor(
                    initialValue = Color(0xFF0055FF),
                    targetValue = Color(0xFFFF00FF),
                    animationSpec = infiniteRepeatable(animation = tween(4500), repeatMode = RepeatMode.Reverse),
                    label = "c2"
                )
                val color3 by infiniteTransition.animateColor(
                    initialValue = Color(0xFFFF00FF),
                    targetValue = Color(0xFF00FFAA),
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
                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text("Settings") },
                        text = { Text("Settings configuration coming soon.") },
                        confirmButton = {
                            TextButton(onClick = { showSettingsDialog = false }) {
                                Text("OK")
                            }
                        }
                    )
                }
                
                // Main Chat Area
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(8.dp),
                        reverseLayout = false
                    ) {
                        items(chatMessages) { msg ->
                            val isUser = msg.role == "user"
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.widthIn(max = 300.dp)
                                ) {
                                    Text(
                                        text = msg.message,
                                        modifier = Modifier.padding(12.dp),
                                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
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
                                                    val messageValue = (index + 1).toString()
                                                    val responseJson = JSONObject().apply {
                                                        put("event", "chat")
                                                        put("message", messageValue)
                                                    }
                                                    if (connectionStatus == "Connected") {
                                                        webSocketManager?.send(responseJson.toString())
                                                    }
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
                            placeholder = { Text("Message Antigravity...") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (chatInput.isNotBlank() && connectionStatus == "Connected") {
                                        val json = JSONObject().apply {
                                            put("event", "chat")
                                            put("message", chatInput)
                                            put("project", ConnectionRepository.state.value.currentProject)
                                        }
                                        webSocketManager?.send(json.toString())
                                        chatInput = ""
                                        ConnectionRepository.setThinking(true)
                                    }
                                }
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (chatInput.isNotBlank() && connectionStatus == "Connected") {
                                    val json = JSONObject().apply {
                                        put("event", "chat")
                                        put("message", chatInput)
                                        put("project", ConnectionRepository.state.value.currentProject)
                                    }
                                    webSocketManager?.send(json.toString())
                                    chatInput = ""
                                    ConnectionRepository.setThinking(true)
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                
                // Artifact Panel (Slide in from right)
                AnimatedVisibility(
                    visible = currentArtifact != null,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.85f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 8.dp
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(currentArtifact?.title ?: "", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                IconButton(onClick = { ConnectionRepository.setArtifact(null) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(currentArtifact?.content ?: "", modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
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
                                .background(Color.DarkGray, RoundedCornerShape(8.dp))
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
                            Text("Trackpad Area", color = Color.White)
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

    fun connect(url: String, listener: WebSocketListener, useTailscaleProxy: Boolean = false) {
        disconnect()
        
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)

        client = if (useTailscaleProxy) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1080)))
                .build()
        } else {
            builder.build()
        }
        
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnect")
        webSocket = null
    }

    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }
}
