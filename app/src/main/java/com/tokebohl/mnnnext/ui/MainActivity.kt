package com.tokebohl.mnnnext.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tokebohl.mnnnext.core.JarvisBrainService
import com.tokebohl.mnnnext.memory.MemoryFact
import com.tokebohl.mnnnext.network.NodeConnectionState
import kotlinx.coroutines.launch
import java.io.File

data class ChatTurn(val sender: String, val text: String, val speedInfo: String = "")

class MainActivity : ComponentActivity() {

    private var brainService by mutableStateOf<JarvisBrainService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as JarvisBrainService.LocalBinder
            brainService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            brainService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLineagePermissions()

        Intent(this, JarvisBrainService::class.java).also { intent ->
            startForegroundService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFF00E676),
                    surface = Color(0xFF12161F),
                    background = Color(0xFF090C10)
                )
            ) {
                JarvisMainApp(service = brainService)
            }
        }
    }

    private fun requestLineagePermissions() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisMainApp(service: JarvisBrainService?) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    val connectionState by service?.ttsBridge?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(NodeConnectionState.Searching) }
    val isGenerating by service?.isGenerating?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val diagnosticLogs by service?.diagnosticLogs?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val pcmFramesCount by service?.ttsBridge?.receivedPcmFrames?.collectAsState()
        ?: remember { mutableStateOf(0) }

    var modelPath by remember {
        mutableStateOf(
            service?.credentialStore?.lastModelDir
                ?: File(Environment.getExternalStorageDirectory(), "MNNModels/Qwen2.5-7B-Instruct-MNN").absolutePath
        )
    }
    var selectedBackend by remember { mutableStateOf(service?.credentialStore?.preferredBackend ?: "opencl") }
    var selectedThreads by remember { mutableStateOf((service?.credentialStore?.preferredThreads ?: 5).toString()) }
    var maxNewTokens by remember { mutableStateOf("1024") }
    var reuseKvCache by remember { mutableStateOf(true) }
    var modelLoaded by remember { mutableStateOf(false) }
    var engineStatus by remember { mutableStateOf("Klar til indlæsning på OnePlus 12R (16GB RAM)") }
    var localModelDirs by remember { mutableStateOf(emptyList<File>()) }

    var promptInput by remember { mutableStateOf("") }
    var streamingReply by remember { mutableStateOf("") }
    var streamToTts by remember { mutableStateOf(true) }
    val chatHistory = remember { mutableStateListOf<ChatTurn>() }

    var manualHost by remember { mutableStateOf("192.168.1.100") }
    var manualPort by remember { mutableStateOf("8000") }
    var hayaiApiKey by remember { mutableStateOf("") }
    var haUrl by remember { mutableStateOf("http://192.168.1.50:8123") }
    var haToken by remember { mutableStateOf("") }
    var haStatus by remember { mutableStateOf("Ikke testet endnu") }

    var memoriesList by remember { mutableStateOf(emptyList<MemoryFact>()) }
    var newMemoryText by remember { mutableStateOf("") }
    var editingMemory by remember { mutableStateOf<MemoryFact?>(null) }
    var editingText by remember { mutableStateOf("") }
    var editingCategory by remember { mutableStateOf("manual") }

    LaunchedEffect(service) {
        if (service != null) {
            modelPath = service.credentialStore.lastModelDir
            selectedBackend = service.credentialStore.preferredBackend
            selectedThreads = service.credentialStore.preferredThreads.toString()
            manualHost = service.credentialStore.hayaiHost
            manualPort = service.credentialStore.hayaiPort.toString()
            hayaiApiKey = service.credentialStore.hayaiApiKey
            haUrl = service.credentialStore.homeAssistantUrl
            haToken = service.credentialStore.homeAssistantToken
            localModelDirs = service.llmRunner.discoverLocalModels()
            memoriesList = service.memoryStore.listAllMemories()
        }
    }

    LaunchedEffect(service, selectedTab) {
        if (selectedTab == 1 && service != null) {
            localModelDirs = service.llmRunner.discoverLocalModels()
        }
        if (selectedTab == 2 && service != null) {
            memoriesList = service.memoryStore.listAllMemories()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MNN_Next // JARVIS BRAIN",
                    color = Color(0xFF00E5FF),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "OnePlus 12R (SD 8 Gen 2 • 16GB) ⇄ 10T ⇄ RPi 4B",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            val (badgeText, badgeColor) = when (val st = connectionState) {
                is NodeConnectionState.Connected ->
                    "10T: ${st.host}:${st.port} (${st.mode})" to Color(0xFF00E676)
                is NodeConnectionState.Searching ->
                    "Auto-Scanner LAN..." to Color(0xFFFFD600)
                is NodeConnectionState.Disconnected ->
                    "10T Offline (Søger)" to Color(0xFFFF5252)
            }

            Surface(
                color = badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = Color(0xFF00E5FF)
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("SAMTALE", fontSize = 11.sp) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("10T & HW", fontSize = 11.sp) })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("HUKOMMELSE", fontSize = 11.sp) })
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("DIAGNOSTIK", fontSize = 11.sp) })
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            0 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = streamToTts,
                            onCheckedChange = {
                                streamToTts = it
                                service?.isTtsStreamingEnabled = it
                            }
                        )
                        Text("Stream til HayaiTTS (/v1/tts/stream)", color = Color.LightGray, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { service?.ttsBridge?.cancelPlayback() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Stop Tale", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                chatHistory.clear()
                                service?.resetConversationAndKvCache()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Ryd Kontekst", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (chatHistory.isEmpty() && streamingReply.isEmpty()) {
                        item {
                            Text(
                                text = "Jarvis er klar. Indlæs din lokale MNN-model under '10T & HW'. Dine svar streames i realtid med start/text/end protokollen til HayaiTTS-Next på OnePlus 10T.",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    }

                    items(chatHistory) { turn ->
                        Column {
                            Text(
                                text = if (turn.sender == "user") "DIG" else "JARVIS ${turn.speedInfo}",
                                color = if (turn.sender == "user") Color(0xFF80D8FF) else Color(0xFF00E5FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(text = turn.text, color = Color.White, fontSize = 15.sp)
                        }
                    }

                    if (streamingReply.isNotEmpty()) {
                        item {
                            Column {
                                Text(
                                    text = "JARVIS (STREAMER LIVE TIL 10T...)",
                                    color = Color(0xFF00E676),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(text = streamingReply, color = Color.White, fontSize = 15.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        placeholder = { Text("Skriv besked til Jarvis...") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isGenerating) {
                        Button(
                            onClick = { service?.cancelActiveGeneration() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                        ) {
                            Text("Stop", color = Color.White)
                        }
                    } else {
                        Button(
                            enabled = promptInput.isNotBlank() && service != null,
                            onClick = {
                                val currentPrompt = promptInput.trim()
                                promptInput = ""
                                chatHistory.add(ChatTurn("user", currentPrompt))
                                streamingReply = ""

                                service?.processUserInput(
                                    userInput = currentPrompt,
                                    onTokenGenerated = { token ->
                                        streamingReply += token
                                    },
                                    onFinished = { fullText, tps ->
                                        streamingReply = ""
                                        chatHistory.add(
                                            ChatTurn(
                                                sender = "jarvis",
                                                text = fullText,
                                                speedInfo = "(${"%.1f".format(tps)} tok/s)"
                                            )
                                        )
                                    }
                                )
                            }
                        ) {
                            Text("Send")
                        }
                    }
                }
            }

            1 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "OnePlus 12R MNN Engine (Snapdragon 8 Gen 2)",
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = modelPath,
                                onValueChange = { modelPath = it },
                                label = { Text("Sti til MNN Model-mappe") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (localModelDirs.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Fundne lokale MNN-modeller:", color = Color.Gray, fontSize = 11.sp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    localModelDirs.take(3).forEach { dir ->
                                        AssistChip(
                                            onClick = { modelPath = dir.absolutePath },
                                            label = { Text(dir.name, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = selectedBackend == "opencl",
                                    onClick = { selectedBackend = "opencl" },
                                    label = { Text("Adreno 740 OpenCL") }
                                )
                                FilterChip(
                                    selected = selectedBackend == "vulkan",
                                    onClick = { selectedBackend = "vulkan" },
                                    label = { Text("Vulkan GPU") }
                                )
                                FilterChip(
                                    selected = selectedBackend == "cpu",
                                    onClick = { selectedBackend = "cpu" },
                                    label = { Text("CPU (5 Kerner)") }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = selectedThreads,
                                    onValueChange = { selectedThreads = it },
                                    label = { Text("CPU Tråde (1-8)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = maxNewTokens,
                                    onValueChange = { maxNewTokens = it },
                                    label = { Text("Max Tokens") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = reuseKvCache, onCheckedChange = { reuseKvCache = it })
                                    Text("KV-Reuse", color = Color.White, fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = engineStatus, color = Color.LightGray, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        engineStatus = "Indlæser MNN vægte i LPDDR5X RAM..."
                                        val threadsInt = selectedThreads.toIntOrNull()?.coerceIn(1, 8) ?: 5
                                        val maxTokInt = maxNewTokens.toIntOrNull()?.coerceIn(64, 4096) ?: 1024
                                        service?.loadBrainModel(
                                            modelDirectory = File(modelPath),
                                            backend = selectedBackend,
                                            threads = threadsInt,
                                            maxNewTokens = maxTokInt,
                                            reuseKv = reuseKvCache
                                        ) { ok, msg ->
                                            modelLoaded = ok
                                            engineStatus = msg
                                        }
                                    }
                                ) {
                                    Text(if (modelLoaded) "Genindlæs Model" else "Indlæs Model")
                                }
                                OutlinedButton(onClick = { service?.resetConversationAndKvCache() }) {
                                    Text("Nulstil KV-Cache")
                                }
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "OnePlus 10T (HayaiTTS-Next /v1/tts/stream)",
                                color = Color(0xFF00E676),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "WebSocket (/v1/tts/stream): Jarvis sender tekst, og OnePlus 10T genererer og afspiller lyden på sin egen højttaler. Jarvis Brain afspiller ikke PCM lokalt. mDNS/UDP discovery:",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = manualHost,
                                    onValueChange = { manualHost = it },
                                    label = { Text("10T IP-adresse") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = manualPort,
                                    onValueChange = { manualPort = it },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    modifier = Modifier.width(95.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = hayaiApiKey,
                                onValueChange = { hayaiApiKey = it },
                                label = { Text("HayaiTTS API Key (Gemmes i Android Keystore)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val port = manualPort.toIntOrNull() ?: 8000
                                        service?.ttsBridge?.connectManual(manualHost, port, hayaiApiKey)
                                        service?.appendLog("Forbinder manuelt til HayaiTTS på $manualHost:$port/v1/tts/stream")
                                    }
                                ) {
                                    Text("Gem & Forbind")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (hayaiApiKey.isNotBlank()) {
                                            service?.credentialStore?.hayaiApiKey = hayaiApiKey
                                            service?.ttsBridge?.apiKey = hayaiApiKey
                                        }
                                        service?.ttsBridge?.startAutoConnect()
                                    }
                                ) {
                                    Text("Auto-Scan")
                                }
                                OutlinedButton(
                                    onClick = {
                                        service?.ttsBridge?.startStreamSession()
                                        service?.ttsBridge?.feedToken("Jarvis lydtest over v1 tts stream fra OnePlus 12R til OnePlus 10T.")
                                        service?.ttsBridge?.flushRemainingTokens()
                                    }
                                ) {
                                    Text("Test Tale")
                                }
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Raspberry Pi 4B • Home Assistant Integration",
                                color = Color(0xFF80D8FF),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = haUrl,
                                onValueChange = { haUrl = it },
                                label = { Text("Home Assistant URL (f.eks. http://192.168.1.50:8123)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = haToken,
                                onValueChange = { haToken = it },
                                label = { Text("Home Assistant Long-Lived Token (Keystore)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Status: $haStatus", color = Color.LightGray, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        service?.credentialStore?.homeAssistantUrl = haUrl
                                        service?.credentialStore?.homeAssistantToken = haToken
                                        uiScope.launch {
                                            val res = service?.homeAssistantBridge?.checkHealth(haUrl, haToken)
                                            haStatus = res?.getOrElse { "Fejl: ${it.message}" } ?: "Service ikke klar"
                                            service?.appendLog("Home Assistant tjek: $haStatus")
                                        }
                                    }
                                ) {
                                    Text("Gem & Test Home Assistant")
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newMemoryText,
                            onValueChange = { newMemoryText = it },
                            placeholder = { Text("Tilføj permanent viden til Jarvis...") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newMemoryText.isNotBlank() && service != null) {
                                    service.memoryStore.remember(newMemoryText.trim(), "manual", 2.0f)
                                    newMemoryText = ""
                                    memoriesList = service.memoryStore.listAllMemories()
                                }
                            }
                        ) {
                            Text("Gem")
                        }
                    }

                    if (editingMemory != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Rediger Minde #${editingMemory!!.id}", color = Color(0xFF00E5FF), fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = editingText,
                                    onValueChange = { editingText = it },
                                    label = { Text("Indhold") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val target = editingMemory
                                        if (target != null && service != null) {
                                            service.memoryStore.updateMemory(
                                                id = target.id,
                                                newContent = editingText,
                                                newCategory = editingCategory,
                                                newImportance = target.importance
                                            )
                                            memoriesList = service.memoryStore.listAllMemories()
                                            editingMemory = null
                                        }
                                    }) {
                                        Text("Opdater")
                                    }
                                    OutlinedButton(onClick = { editingMemory = null }) {
                                        Text("Annuller")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(memoriesList, key = { it.id }) { mem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1B2230), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "[${mem.category.uppercase()}] (Vægt: ${mem.importance})",
                                        color = Color(0xFF00E5FF),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(text = mem.content, color = Color.White, fontSize = 14.sp)
                                }
                                Row {
                                    TextButton(
                                        onClick = {
                                            editingMemory = mem
                                            editingText = mem.content
                                            editingCategory = mem.category
                                        }
                                    ) {
                                        Text("Rediger", color = Color(0xFF80D8FF))
                                    }
                                    TextButton(
                                        onClick = {
                                            service?.memoryStore?.deleteMemory(mem.id)
                                            memoriesList = service?.memoryStore?.listAllMemories() ?: emptyList()
                                        }
                                    ) {
                                        Text("Slet", color = Color(0xFFFF5252))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                val memStats = remember(selectedTab) {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                    val availGb = info.availMem / (1024.0 * 1024.0 * 1024.0)
                    val totalGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
                    "RAM: ${"%.2f".format(availGb)} GB fri af ${"%.2f".format(totalGb)} GB LPDDR5X"
                }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "OnePlus 12R Hardware & Stream Telemetri",
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$memStats | CPU Kerner: ${Runtime.getRuntime().availableProcessors()}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Aktiv MNN Backend: ${service?.llmRunner?.activeBackendName ?: "unloaded"} | Modtagne PCM16LE frames: $pcmFramesCount",
                                color = Color(0xFF00E676),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (diagnosticLogs.isEmpty()) {
                            item {
                                Text("Ingen log-hændelser endnu.", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                        items(diagnosticLogs) { logLine ->
                            Text(
                                text = logLine,
                                color = if (logLine.contains("FEJL", ignoreCase = true)) Color(0xFFFF5252) else Color.LightGray,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
