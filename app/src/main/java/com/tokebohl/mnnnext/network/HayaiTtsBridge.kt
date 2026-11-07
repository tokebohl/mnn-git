package com.tokebohl.mnnnext.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.tokebohl.mnnnext.security.SecureCredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.net.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class NodeConnectionState {
    object Searching : NodeConnectionState()
    data class Connected(val host: String, val port: Int, val mode: String) : NodeConnectionState()
    data class Disconnected(val reason: String) : NodeConnectionState()
}

class HayaiTtsBridge(
    private val context: Context?,
    private val scope: CoroutineScope,
    private val credentialStore: SecureCredentialStore? = null,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "HayaiTtsBridge"
        const val STREAM_WS_PATH = "/v1/tts/stream"
        private const val SERVICE_TYPE = "_hayaitts._tcp."
        private const val UDP_DISCOVERY_PORT = 42110
        private const val UDP_MAGIC_PING = "JARVIS_DISCOVER_HAYAI_TTS"
        private const val UDP_MAGIC_PONG_PREFIX = "HAYAI_TTS_HERE:"
        private val CANDIDATE_PORTS = listOf(8000, 8080, 5000, 5002, 7860, 8888)
        private val SENTENCE_DELIMITERS = charArrayOf('.', '!', '?', '\n', ';', ':')

        fun buildStartFrame(voice: String = "default", speed: Float = 1.0f, sampleRate: Int = 24000): String {
            return JSONObject().apply {
                put("type", "start")
                put("voice", voice)
                put("speed", speed.toDouble())
                put("format", "pcm16le")
                put("sample_rate", sampleRate)
                // HayaiTTS must play on the 10T itself. Jarvis sends text only
                // and intentionally does not play returned PCM on the 12R.
                put("playback_target", "server")
            }.toString()
        }

        fun buildTextFrame(textChunk: String, seq: Int): String {
            return JSONObject().apply {
                put("type", "text")
                put("text", textChunk)
                put("seq", seq)
            }.toString()
        }

        fun buildEndFrame(): String {
            return JSONObject().apply {
                put("type", "end")
            }.toString()
        }

        fun buildCancelFrame(): String {
            return JSONObject().apply {
                put("type", "cancel")
            }.toString()
        }
    }

    private val wifiManager = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val nsdManager = context?.applicationContext?.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var targetEndpoint: Pair<String, Int>? = null
    private val isDiscovering = AtomicBoolean(false)
    private val streamSessionStarted = AtomicBoolean(false)

    private var currentSampleRate: Int = 24000

    @Volatile
    var apiKey: String = credentialStore?.hayaiApiKey ?: ""

    @Volatile
    var selectedVoice: String = credentialStore?.hayaiVoice ?: "default"

    private val _connectionState = MutableStateFlow<NodeConnectionState>(NodeConnectionState.Searching)
    val connectionState: StateFlow<NodeConnectionState> = _connectionState

    private val _receivedPcmFrames = MutableStateFlow(0)
    val receivedPcmFrames: StateFlow<Int> = _receivedPcmFrames

    private val sentenceQueue = Channel<Pair<String, Int>>(Channel.UNLIMITED)
    private val tokenBuffer = StringBuilder()
    private var currentSequenceId = 0

    init {
        startQueueDispatcher()
    }

    fun startAutoConnect() {
        acquireLowLatencyWifiLocks()
        if (isDiscovering.compareAndSet(false, true)) {
            _connectionState.value = NodeConnectionState.Searching
            startMdnsDiscovery()
            startUdpBroadcastDiscovery()
            startSubnetSweepDiscovery()
        }
    }

    fun connectManual(host: String, port: Int, customApiKey: String? = null) {
        isDiscovering.set(false)
        val cleanHost = host.trim()
        if (!customApiKey.isNullOrBlank()) {
            apiKey = customApiKey.trim()
            credentialStore?.hayaiApiKey = apiKey
        }
        credentialStore?.hayaiHost = cleanHost
        credentialStore?.hayaiPort = port
        targetEndpoint = cleanHost to port
        connectToNode(cleanHost, port)
    }

    @Synchronized
    fun startStreamSession(voice: String = selectedVoice, speed: Float = 1.0f) {
        tokenBuffer.clear()
        currentSequenceId = 0
        val ws = activeWebSocket
        if (ws != null && streamSessionStarted.compareAndSet(false, true)) {
            if (!ws.send(buildStartFrame(voice, speed, currentSampleRate))) {
                streamSessionStarted.set(false)
                triggerReconnect()
            }
        }
    }

    @Synchronized
    fun feedToken(token: String) {
        if (!streamSessionStarted.get() && activeWebSocket != null) {
            startStreamSession()
        }
        tokenBuffer.append(token)
        val delimiterIdx = findFlushIndex(tokenBuffer)
        if (delimiterIdx != -1) {
            val clause = tokenBuffer.substring(0, delimiterIdx + 1).trim()
            tokenBuffer.delete(0, delimiterIdx + 1)
            if (clause.isNotEmpty()) {
                sentenceQueue.trySend(clause to ++currentSequenceId)
            }
        }
    }

    @Synchronized
    fun flushRemainingTokens() {
        val remaining = tokenBuffer.toString().trim()
        tokenBuffer.clear()
        if (remaining.isNotEmpty()) {
            if (!streamSessionStarted.get() && activeWebSocket != null) {
                startStreamSession()
            }
            sentenceQueue.trySend(remaining to ++currentSequenceId)
        }
        sentenceQueue.trySend("" to -1)
        currentSequenceId = 0
    }

    @Synchronized
    fun cancelPlayback() {
        tokenBuffer.clear()
        currentSequenceId = 0
        streamSessionStarted.set(false)
        activeWebSocket?.send(buildCancelFrame())
    }

    private fun findFlushIndex(buf: StringBuilder): Int {
        if (buf.length < 10) return -1
        val lastSentIdx = buf.indexOfLast { it in SENTENCE_DELIMITERS }
        if (lastSentIdx >= 8) return lastSentIdx
        val commaIdx = buf.indexOfLast { it == ',' }
        if (commaIdx >= 26) return commaIdx
        if (buf.length >= 85) {
            val spaceIdx = buf.indexOfLast { it.isWhitespace() }
            if (spaceIdx >= 40) return spaceIdx
        }
        return -1
    }

    private fun startQueueDispatcher() {
        scope.launch(Dispatchers.IO) {
            for ((sentence, seqId) in sentenceQueue) {
                if (seqId == -1) {
                    if (streamSessionStarted.getAndSet(false)) {
                        activeWebSocket?.send(buildEndFrame())
                    }
                } else if (sentence.isNotEmpty()) {
                    dispatchSentenceTo10T(sentence, seqId)
                }
            }
        }
    }

    private suspend fun dispatchSentenceTo10T(sentence: String, sequenceId: Int) {
        val ws = activeWebSocket
        if (ws == null) {
            Log.w(TAG, "Ingen aktiv WebSocket til OnePlus 10T; genopretter forbindelse i stedet for lokal afspilning på Jarvis Brain.")
            triggerReconnect()
            return
        }
        if (!streamSessionStarted.get()) {
            if (!streamSessionStarted.compareAndSet(false, true)) return
            if (!ws.send(buildStartFrame(selectedVoice, 1.0f, currentSampleRate))) {
                streamSessionStarted.set(false)
                triggerReconnect()
                return
            }
        }
        if (!ws.send(buildTextFrame(sentence, sequenceId))) {
            Log.w(TAG, "Kunne ikke sende tekst til OnePlus 10T; genopretter forbindelse.")
            streamSessionStarted.set(false)
            triggerReconnect()
        }
    }

    private fun applyAuthHeaders(builder: Request.Builder) {
        val key = apiKey.trim()
        if (key.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $key")
            builder.addHeader("X-API-Key", key)
        }
    }

    private fun onNodeDiscovered(host: String, port: Int) {
        if (_connectionState.value is NodeConnectionState.Connected) return
        targetEndpoint = host to port
        connectToNode(host, port)
    }

    private fun connectToNode(host: String, port: Int) {
        activeWebSocket?.close(1000, "Reconnecting")
        activeWebSocket = null
        streamSessionStarted.set(false)

        val wsReqBuilder = Request.Builder()
            .url("ws://$host:$port$STREAM_WS_PATH")
        applyAuthHeaders(wsReqBuilder)

        activeWebSocket = okHttpClient.newWebSocket(wsReqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isDiscovering.set(false)
                _connectionState.value = NodeConnectionState.Connected(host, port, "WS $STREAM_WS_PATH")
                Log.i(TAG, "Forbundet til HayaiTTS-Next via $STREAM_WS_PATH på $host:$port")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    val rate = json.optInt("sample_rate", 0)
                    if (rate in 8000..96000) currentSampleRate = rate
                    when (json.optString("type", json.optString("event"))) {
                        "audio_start" -> Log.d(TAG, "HayaiTTS afspiller lokalt på OnePlus 10T (${currentSampleRate} Hz).")
                        "audio_end" -> Log.d(TAG, "Lokal afspilning på OnePlus 10T afsluttet.")
                        "error" -> Log.e(TAG, "HayaiTTS fejl: ${json.optString("detail", "Ukendt serverfejl")}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Server-side output mode does not send PCM to this device. Ignore
                // unexpected binary frames to prevent duplicate playback on Jarvis Brain.
                if (bytes.size > 0) {
                    Log.w(TAG, "Ignorerer uventet PCM-frame: TTS-output skal afspilles på OnePlus 10T.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                activeWebSocket = null
                streamSessionStarted.set(false)
                Log.w(TAG, "HayaiTTS WebSocket fejlede; lokal OnePlus 10T-afspilning er ikke tilgængelig før forbindelsen er genoprettet.", t)
                triggerReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeWebSocket = null
                streamSessionStarted.set(false)
                if (code != 1000) {
                    triggerReconnect()
                }
            }
        })
    }

    private fun probeHttpNode(host: String, port: Int): Boolean {
        val probePaths = listOf("/v1/health", "/health", "/v1/tts/stream", "/api/tts", "/docs", "/")
        for (path in probePaths) {
            val ok = runCatching {
                val reqBuilder = Request.Builder().url("http://$host:$port$path").get()
                applyAuthHeaders(reqBuilder)
                probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                    resp.code in 200..426
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun triggerReconnect() {
        _connectionState.value = NodeConnectionState.Disconnected("Søger efter OnePlus 10T...")
        if (isDiscovering.compareAndSet(false, true)) {
            startUdpBroadcastDiscovery()
            startSubnetSweepDiscovery()
        }
    }

    private fun startMdnsDiscovery() {
        runCatching {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
        }
    }

    private val nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.contains("_hayaitts") || service.serviceName.contains("Hayai", ignoreCase = true)) {
                nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        onNodeDiscovered(host, resolved.port)
                    }
                })
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) { triggerReconnect() }
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    private fun startUdpBroadcastDiscovery() {
        scope.launch(Dispatchers.IO) {
            while (isActive && isDiscovering.get()) {
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        socket.soTimeout = 1200
                        val sendData = UDP_MAGIC_PING.toByteArray()
                        val packet = DatagramPacket(
                            sendData,
                            sendData.size,
                            InetAddress.getByName("255.255.255.255"),
                            UDP_DISCOVERY_PORT
                        )
                        socket.send(packet)

                        val recvBuf = ByteArray(256)
                        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                        socket.receive(recvPacket)
                        val reply = String(recvPacket.data, 0, recvPacket.length).trim()
                        if (reply.startsWith(UDP_MAGIC_PONG_PREFIX)) {
                            val port = reply.substringAfter(UDP_MAGIC_PONG_PREFIX).toIntOrNull() ?: 8000
                            val host = recvPacket.address.hostAddress ?: return@use
                            onNodeDiscovered(host, port)
                        }
                    }
                }
                delay(2500)
            }
        }
    }

    private fun startSubnetSweepDiscovery() {
        scope.launch(Dispatchers.IO) {
            while (isActive && isDiscovering.get()) {
                val localIp = getLocalIpv4Address()
                if (localIp != null && localIp.contains(".")) {
                    val prefix = localIp.substringBeforeLast(".")
                    val myLastOctet = localIp.substringAfterLast(".").toIntOrNull() ?: -1

                    coroutineScope {
                        val semaphore = kotlinx.coroutines.sync.Semaphore(32)
                        for (i in 1..254) {
                            if (i == myLastOctet || !isDiscovering.get()) continue
                            val candidateHost = "$prefix.$i"
                            launch {
                                semaphore.acquire()
                                try {
                                    for (port in CANDIDATE_PORTS) {
                                        if (!isDiscovering.get()) break
                                        if (isTcpPortOpen(candidateHost, port, 180) && probeHttpNode(candidateHost, port)) {
                                            onNodeDiscovered(candidateHost, port)
                                            break
                                        }
                                    }
                                } finally {
                                    semaphore.release()
                                }
                            }
                        }
                    }
                }
                delay(6000)
            }
        }
    }

    private fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    private fun getLocalIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    }.getOrNull()

    private fun acquireLowLatencyWifiLocks() {
        if (wifiManager == null) return
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("Jarvis:MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "Jarvis:LowLatencyWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    fun shutdown() {
        isDiscovering.set(false)
        streamSessionStarted.set(false)
        runCatching { nsdDiscoveryListener.let { nsdManager?.stopServiceDiscovery(it) } }
        activeWebSocket?.close(1000, "Service lukker")
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }
}
