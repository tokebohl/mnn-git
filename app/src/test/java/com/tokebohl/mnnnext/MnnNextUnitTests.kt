package com.tokebohl.mnnnext

import com.tokebohl.mnnnext.homeassistant.HomeAssistantBridge
import com.tokebohl.mnnnext.memory.JarvisMemoryStore
import com.tokebohl.mnnnext.network.HayaiTtsBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MnnNextUnitTests {

    @Test
    fun `vector serialization and cosine similarity are accurate`() {
        val vec = JarvisMemoryStore.computeLocalSemanticVector("Jarvis kører lokalt på OnePlus 12R")
        val bytes = JarvisMemoryStore.floatArrayToBytes(vec)
        val restored = JarvisMemoryStore.bytesToFloatArray(bytes)

        assertEquals(384, restored.size)
        assertEquals(1.0f, JarvisMemoryStore.cosineSimilarity(vec, restored), 1e-4f)
    }

    @Test
    fun `automatic fact extractor captures Danish and English facts`() {
        val text = "Husk at koden til garagen er 4812. Jeg foretrækker kortfattede svar."
        val facts = JarvisMemoryStore.extractFactsFromText(text)
        assertEquals(2, facts.size)
        assertEquals("explicit_note", facts[0].second)
        assertEquals("preference", facts[1].second)
    }

    @Test
    fun `HomeAssistant regex parses domain, service and entity_id`() {
        val sample = "Jeg tænder lyset i stuen nu. [HA_ACTION: light.turn_on | light.stue_loft]"
        val match = HomeAssistantBridge.HA_TAG_REGEX.find(sample)
        assertNotNull(match)
        assertEquals("light", match!!.groupValues[1])
        assertEquals("turn_on", match.groupValues[2])
        assertEquals("light.stue_loft", match.groupValues[3])
    }

    @Test
    fun `HayaiTtsBridge streams start, text and end over authenticated v1 tts stream`() {
        val frames = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()
        val authHeaders = CopyOnWriteArrayList<String>()
        val doneLatch = CountDownLatch(1)

        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    paths.add(response.request.url.encodedPath)
                    authHeaders.add(response.request.header("Authorization").orEmpty())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(text)
                    if (text.contains("\"type\":\"end\"")) {
                        doneLatch.countDown()
                    }
                }
            })
        )
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bridge = HayaiTtsBridge(context = null, scope = scope)
        bridge.connectManual(server.hostName, server.port, customApiKey = "test-10t-key")

        Thread.sleep(250)
        bridge.startStreamSession()
        bridge.feedToken("Hej Toke, ")
        bridge.feedToken("forbindelsen til OnePlus 10T virker.")
        bridge.flushRemainingTokens()

        assertTrue(doneLatch.await(3, TimeUnit.SECONDS))
        assertEquals("/v1/tts/stream", paths.firstOrNull())
        assertEquals("Bearer test-10t-key", authHeaders.firstOrNull())
        assertTrue(frames.any { it.contains("\"type\":\"start\"") })
        assertTrue(frames.any { it.contains("\"type\":\"text\"") && it.contains("OnePlus 10T") })
        assertTrue(frames.any { it.contains("\"type\":\"end\"") })

        bridge.shutdown()
        server.shutdown()
    }
}
