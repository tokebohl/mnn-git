package com.tokebohl.hayaitts.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Valgfri zero-config responder til HayaiTTS-Next på din OnePlus 10T.
 * Bemærk: MNN_Next har allerede indbygget parallel subnet-scanner, så den finder
 * din OnePlus 10T automatisk, men med denne klasse sker koblingen på <100ms via UDP/mDNS.
 */
class HayaiDiscoveryResponder(
    context: Context,
    private val ttsServerPort: Int = 8000
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startAdvertising() {
        registerMdnsService()
        startUdpResponder()
    }

    private fun registerMdnsService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "HayaiTTS-Next-10T"
            serviceType = "_hayaitts._tcp."
            port = ttsServerPort
        }
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            })
        }
    }

    private fun startUdpResponder() {
        scope.launch {
            runCatching {
                DatagramSocket(42110).use { socket ->
                    val buf = ByteArray(256)
                    while (isActive) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length).trim()
                        if (msg == "JARVIS_DISCOVER_HAYAI_TTS") {
                            val reply = "HAYAI_TTS_HERE:$ttsServerPort".toByteArray()
                            socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }
}
