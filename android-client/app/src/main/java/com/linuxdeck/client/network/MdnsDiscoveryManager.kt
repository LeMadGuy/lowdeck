package com.linuxdeck.client.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.linuxdeck.client.security.PairingQrPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Represents a Linux Deck host discovered via mDNS (`_linuxdeck._tcp.`) or configured manually / via USB.
 */
data class DiscoveredHost(
    val serviceName: String,
    val hostname: String,
    val hostAddress: String,
    val port: Int = PairingQrPayload.DEFAULT_CONTROL_PORT,
    val pairingPort: Int = PairingQrPayload.DEFAULT_PAIRING_PORT,
    val shortFingerprint: String = "",
    val protocolVersion: String = "1",
    val isUsbLoopback: Boolean = false,
) {
    val endpointKey: String get() = "$hostAddress:$port"

    companion object {
        const val USB_LOOPBACK_HOST = "127.0.0.1"

        /**
         * Creates a ready-to-use target for USB cable connections (`adb reverse tcp:8443 tcp:8443`).
         */
        fun usbAdbReverse(
            port: Int = PairingQrPayload.DEFAULT_CONTROL_PORT,
            pairingPort: Int = PairingQrPayload.DEFAULT_PAIRING_PORT,
        ): DiscoveredHost = DiscoveredHost(
            serviceName = "USB (adb reverse)",
            hostname = "localhost",
            hostAddress = USB_LOOPBACK_HOST,
            port = port,
            pairingPort = pairingPort,
            isUsbLoopback = true,
        )

        /**
         * Creates a manual LAN / Tailscale target.
         */
        fun manual(
            host: String,
            port: Int = PairingQrPayload.DEFAULT_CONTROL_PORT,
            pairingPort: Int = PairingQrPayload.DEFAULT_PAIRING_PORT,
        ): DiscoveredHost {
            val cleanHost = host.trim().ifEmpty { USB_LOOPBACK_HOST }
            return DiscoveredHost(
                serviceName = cleanHost,
                hostname = cleanHost,
                hostAddress = cleanHost,
                port = port,
                pairingPort = pairingPort,
                isUsbLoopback = cleanHost == USB_LOOPBACK_HOST || cleanHost.equals("localhost", ignoreCase = true),
            )
        }

        /**
         * Parses TXT record byte map emitted by `linux-daemon/internal/discovery/mdns.go`.
         */
        fun parseTxtAttributes(rawAttributes: Map<String, ByteArray?>): TxtMetadata {
            val decoded = rawAttributes.mapValues { (_, bytes) ->
                bytes?.toString(Charsets.UTF_8)?.trim().orEmpty()
            }
            return TxtMetadata(
                version = decoded["version"]?.ifEmpty { "1" } ?: "1",
                hostname = decoded["hostname"].orEmpty(),
                shortFingerprint = decoded["fp"].orEmpty().lowercase(),
            )
        }
    }

    data class TxtMetadata(
        val version: String,
        val hostname: String,
        val shortFingerprint: String,
    )
}

/**
 * Discovers `_linuxdeck._tcp.` services on the local network using Android's [NsdManager],
 * serializing service resolutions on `Dispatchers.IO` to avoid `FAILURE_ALREADY_ACTIVE`.
 */
class MdnsDiscoveryManager(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveWorkerJob: Job? = null
    private var resolveQueue = Channel<NsdServiceInfo>(Channel.BUFFERED)

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Starts mDNS discovery for `_linuxdeck._tcp.` on [ioDispatcher].
     */
    suspend fun startDiscovery() = withContext(ioDispatcher) {
        if (_isDiscovering.value) return@withContext
        val manager = nsdManager ?: run {
            _lastError.value = "Serviço NsdManager indisponível neste dispositivo."
            return@withContext
        }

        _lastError.value = null
        acquireMulticastLock()

        resolveQueue = Channel(Channel.BUFFERED)
        resolveWorkerJob = scope.launch {
            for (serviceInfo in resolveQueue) {
                resolveSingleService(manager, serviceInfo)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                _isDiscovering.value = false
                _lastError.value = "Falha ao iniciar descoberta mDNS (código $errorCode)."
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                _isDiscovering.value = false
                releaseMulticastLock()
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                _isDiscovering.value = true
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                _isDiscovering.value = false
                releaseMulticastLock()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                val type = serviceInfo.serviceType.orEmpty()
                if (!type.contains(SERVICE_TYPE_CORE, ignoreCase = true)) return
                resolveQueue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                val lostName = serviceInfo?.serviceName ?: return
                _discoveredHosts.update { current ->
                    current.filterNot { it.serviceName == lostName }
                }
            }
        }

        discoveryListener = listener
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { err ->
            _isDiscovering.value = false
            _lastError.value = "Erro ao iniciar mDNS: ${err.message}"
            releaseMulticastLock()
        }
    }

    /**
     * Stops active mDNS discovery and releases the Wi-Fi multicast lock.
     */
    suspend fun stopDiscovery() = withContext(ioDispatcher) {
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null && nsdManager != null) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        resolveQueue.close()
        resolveWorkerJob?.cancel()
        resolveWorkerJob = null
        _isDiscovering.value = false
        releaseMulticastLock()
    }

    /**
     * Clears cached hosts and restarts mDNS scan.
     */
    suspend fun refresh() = withContext(ioDispatcher) {
        stopDiscovery()
        _discoveredHosts.value = emptyList()
        startDiscovery()
    }

    private suspend fun resolveSingleService(manager: NsdManager, info: NsdServiceInfo) {
        val done = CompletableDeferred<DiscoveredHost?>()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                done.complete(null)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                done.complete(resolved?.let(::mapResolvedService))
            }
        }

        @Suppress("DEPRECATION")
        val started = runCatching {
            manager.resolveService(info, resolveListener)
            true
        }.getOrDefault(false)

        if (!started) return

        val host = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { done.await() } ?: return
        _discoveredHosts.update { existing ->
            val filtered = existing.filterNot {
                it.serviceName == host.serviceName || it.endpointKey == host.endpointKey
            }
            (filtered + host).sortedBy { it.hostname.lowercase() }
        }
    }

    private fun mapResolvedService(resolved: NsdServiceInfo): DiscoveredHost? {
        val inetAddress = extractPreferredAddress(resolved) ?: return null
        val hostIp = inetAddress.hostAddress?.substringBefore('%') ?: return null
        val port = resolved.port.takeIf { it in 1..65535 } ?: PairingQrPayload.DEFAULT_CONTROL_PORT
        val txt = DiscoveredHost.parseTxtAttributes(resolved.attributes)
        val displayHostname = txt.hostname.ifEmpty { resolved.serviceName ?: hostIp }

        return DiscoveredHost(
            serviceName = resolved.serviceName ?: displayHostname,
            hostname = displayHostname,
            hostAddress = hostIp,
            port = port,
            pairingPort = PairingQrPayload.DEFAULT_PAIRING_PORT,
            shortFingerprint = txt.shortFingerprint,
            protocolVersion = txt.version,
        )
    }

    private fun extractPreferredAddress(info: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addresses = info.hostAddresses
            if (addresses.isNotEmpty()) {
                return addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
            }
        }
        @Suppress("DEPRECATION")
        return info.host
    }

    private fun acquireMulticastLock() {
        runCatching {
            val lock = multicastLock ?: wifiManager?.createMulticastLock("linuxdeck-mdns-lock")?.apply {
                setReferenceCounted(false)
                multicastLock = this
            }
            if (lock?.isHeld == false) {
                lock.acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            val lock = multicastLock
            if (lock?.isHeld == true) {
                lock.release()
            }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_linuxdeck._tcp."
        private const val SERVICE_TYPE_CORE = "_linuxdeck._tcp"
        private const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}

