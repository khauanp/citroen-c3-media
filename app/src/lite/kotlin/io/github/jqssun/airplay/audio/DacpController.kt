package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Sends play/pause/previous/next commands back to the active iPhone. */
class DacpController(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var activeRemote = ""
    @Volatile private var serviceName = ""
    @Volatile private var host = ""
    @Volatile private var port = 0
    private val pendingPaths = ArrayDeque<String>()
    private val pendingLock = Any()
    private val resolving = AtomicBoolean(false)
    private val resolveGeneration = AtomicInteger(0)
    private val discovering = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun update(dacpId: String, remote: String) {
        val cleanId = dacpId.trim()
        val nextName = when {
            cleanId.isBlank() -> ""
            cleanId.startsWith(DACP_PREFIX, ignoreCase = true) -> cleanId
            else -> "$DACP_PREFIX$cleanId"
        }
        val nextRemote = remote.trim()
        if (nextName.equals(serviceName, ignoreCase = true)) {
            if (nextRemote.isNotBlank()) activeRemote = nextRemote
            if (host.isNotBlank() && port > 0 && activeRemote.isNotBlank()) flushPending()
            else ensureResolved()
            return
        }
        activeRemote = nextRemote
        serviceName = nextName
        invalidateEndpoint()
        if (serviceName.isBlank()) {
            stopDiscovery()
            return
        }
        ensureResolved()
    }

    fun play() = send("/ctrl-int/1/play")
    fun pause() = send("/ctrl-int/1/pause")
    fun toggle() = send("/ctrl-int/1/playpause")
    fun next() = send("/ctrl-int/1/nextitem")
    fun previous() = send("/ctrl-int/1/previtem")

    fun reset() {
        activeRemote = ""
        serviceName = ""
        invalidateEndpoint()
        synchronized(pendingLock) { pendingPaths.clear() }
        stopDiscovery()
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        reset()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private fun ensureResolved() {
        if (released.get() || serviceName.isBlank() || host.isNotBlank()) return
        if (!resolving.compareAndSet(false, true)) return
        val generation = resolveGeneration.incrementAndGet()
        mainHandler.postDelayed({
            if (resolveGeneration.get() == generation && resolving.compareAndSet(true, false)) {
                discover()
            }
        }, DIRECT_RESOLVE_TIMEOUT_MS)

        // This direct lookup is the path used by the original app and works on
        // Android 5 hotspot builds where browse callbacks are never delivered.
        val info = NsdServiceInfo().apply {
            serviceName = this@DacpController.serviceName
            serviceType = DACP_TYPE
        }
        resolve(info, generation, true)
    }

    private fun discover() {
        if (released.get() || serviceName.isBlank() || host.isNotBlank()) return
        if (!discovering.compareAndSet(false, true)) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!sameService(serviceInfo.serviceName, serviceName)) return
                if (!resolving.compareAndSet(false, true)) return
                val generation = resolveGeneration.incrementAndGet()
                resolve(serviceInfo, generation, false)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (sameService(serviceInfo.serviceName, serviceName)) invalidateEndpoint()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discovering.set(false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DACP discovery start failed: $errorCode")
                discovering.set(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DACP discovery stop failed: $errorCode")
                discovering.set(false)
            }
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices(DACP_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            discovering.set(false)
            Log.w(TAG, "DACP discovery error", it)
        }
    }

    private fun resolve(info: NsdServiceInfo, generation: Int, fallbackToDiscovery: Boolean) {
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (resolveGeneration.get() != generation) return
                    resolving.set(false)
                    Log.w(TAG, "DACP resolve failed: $errorCode")
                    if (fallbackToDiscovery) discover()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (resolveGeneration.get() != generation || released.get()) return
                    val resolvedHost = serviceInfo.host?.hostAddress.orEmpty()
                    val resolvedPort = serviceInfo.port
                    resolving.set(false)
                    if (resolvedHost.isBlank() || resolvedPort <= 0) {
                        if (fallbackToDiscovery) discover()
                        return
                    }
                    host = resolvedHost
                    port = resolvedPort
                    stopDiscovery()
                    flushPending()
                }
            })
        }.onFailure {
            if (resolveGeneration.get() == generation) resolving.set(false)
            Log.w(TAG, "DACP resolve error", it)
            if (fallbackToDiscovery) discover()
        }
    }

    private fun send(path: String) {
        if (released.get()) return
        val endpointHost = host
        val endpointPort = port
        val remote = activeRemote
        if (remote.isBlank() || endpointHost.isBlank() || endpointPort <= 0) {
            queue(path)
            if (serviceName.isNotBlank()) ensureResolved()
            return
        }
        runCatching {
            executor.execute {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL("http://$endpointHost:$endpointPort$path").openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Active-Remote", remote)
                    connection.setRequestProperty("Connection", "close")
                    connection.connectTimeout = HTTP_TIMEOUT_MS
                    connection.readTimeout = HTTP_TIMEOUT_MS
                    val response = connection.responseCode
                    runCatching { connection?.inputStream?.close() }
                    if (response !in 200..299) error("DACP HTTP $response")
                } catch (error: Throwable) {
                    if (host == endpointHost && port == endpointPort) invalidateEndpoint()
                    queue(path)
                    ensureResolved()
                    Log.w(TAG, "DACP command failed: $path", error)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    private fun flushPending() {
        if (activeRemote.isBlank() || host.isBlank() || port <= 0) return
        val queued = synchronized(pendingLock) {
            ArrayList<String>(pendingPaths).also { pendingPaths.clear() }
        }
        queued.forEach(::send)
    }

    private fun queue(path: String) {
        synchronized(pendingLock) {
            if (pendingPaths.lastOrNull() != path) pendingPaths.addLast(path)
            while (pendingPaths.size > MAX_PENDING_COMMANDS) pendingPaths.removeFirst()
        }
    }

    private fun invalidateEndpoint() {
        host = ""
        port = 0
        resolving.set(false)
        resolveGeneration.incrementAndGet()
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        if (!discovering.getAndSet(false)) return
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    private fun sameService(first: String?, second: String): Boolean =
        first?.trim()?.trimEnd('.')?.equals(second.trim().trimEnd('.'), ignoreCase = true) == true

    companion object {
        private const val TAG = "C3MediaDacp"
        private const val DACP_PREFIX = "iTunes_Ctrl_"
        private const val DACP_TYPE = "_dacp._tcp."
        private const val MAX_PENDING_COMMANDS = 8
        private const val DIRECT_RESOLVE_TIMEOUT_MS = 2_200L
        private const val HTTP_TIMEOUT_MS = 1_500
    }
}
