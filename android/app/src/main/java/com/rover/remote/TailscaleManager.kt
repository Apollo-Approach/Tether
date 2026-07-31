package com.rover.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import tsnet_wrapper.Tsnet_wrapper
import tsnet_wrapper.AuthCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

object TailscaleManager {
    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status
    
    private val _peers = MutableStateFlow<List<TailscalePeer>>(emptyList())
    val peers: StateFlow<List<TailscalePeer>> = _peers
    
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var isStarted = false
    private var pollingJob: kotlinx.coroutines.Job? = null
    private var isPolling = false

    fun start(context: Context) {
        if (isStarted) return
        isStarted = true // Set immediately to prevent race conditions
        
        _status.value = "Starting..."
        val stateDir = context.getDir("tailscale", Context.MODE_PRIVATE).absolutePath
        
        Thread {
            try {
                // Initialize the Go tsnet wrapper
                Tsnet_wrapper.startTailscale(Config.TAILSCALE_HOSTNAME, stateDir, object : AuthCallback {
                    override fun onAuthURL(url: String) {
                        _status.value = "Needs Login"
                        // Automatically open the browser for Google login
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    
                    override fun onLog(msg: String) {
                        android.util.Log.d("TSNET_INTERNAL", msg)
                    }
                })
                
                // Once startTailscale returns, it means the node is up and authenticated,
                // and the local proxy is running on 127.0.0.1 (Config.SOCKS_PROXY_PORT)
                _status.value = "Connected"
            } catch (e: Exception) {
                e.printStackTrace()
                _status.value = "Error: ${e.message}"
                isStarted = false
            }
        }.start()
    }
    
    fun startPolling() {
        if (!isStarted || isPolling) return
        isPolling = true
        pollingJob = scope.launch {
            while (isPolling) {
                try {
                    val jsonString = Tsnet_wrapper.getPeers()
                    val jsonArray = JSONArray(jsonString)
                    val newPeers = mutableListOf<TailscalePeer>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        newPeers.add(TailscalePeer(
                            hostname = obj.getString("hostname"),
                            online = obj.getBoolean("online"),
                            ip = obj.optString("ip", "")
                        ))
                    }
                    _peers.value = newPeers
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(Config.TAILSCALE_PEER_POLL_INTERVAL_MS)
            }
        }
    }
    
    fun stopPolling() {
        isPolling = false
        pollingJob?.cancel()
        pollingJob = null
    }
    
    fun stop() {
        if (!isStarted) return
        try {
            Tsnet_wrapper.stopTailscale()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isStarted = false
        _status.value = "Disconnected"
    }
    
    fun getLocalProxyUrl(targetPort: Int): String {
        return "${Config.WS_SCHEME}${Config.LOOPBACK_IP}:$targetPort"
    }
}
