package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Sends the play/pause/previous/next commands back to the iPhone. */
class DacpController(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var activeRemote = ""
    @Volatile private var serviceName = ""
    @Volatile private var host = ""
    @Volatile private var port = 0
    private val pendingPaths = ArrayDeque<String>()
    private val pendingLock = Any()
    private val resolving = AtomicBoolean(false)
    private val discovering = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun update(dacpId: String, remote: String) {
        val nextName = if (dacpId.isBlank()) "" else "iTunes_Ctrl_$dacpId"
        if (nextName == serviceName && remote == activeRemote) {
            if (nextName.isNotBlank() && host.isBlank()) discover()
            return
        }
        activeRemote = remote.trim()
        serviceName = nextName
        host = ""
        port = 0
        if (serviceName.isBlank() || remote.isBlank()) {
            stopDiscovery()
            return
        }
        discover()
    }

    private fun discover() {
        if (released.get() || serviceName.isBlank() || !discovering.compareAndSet(false, true)) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.equals(serviceName, ignoreCase = true)) {
                    resolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.equals(serviceName, ignoreCase = true)) {
                    host = ""
                    port = 0
                }
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
        try {
            nsd.discoverServices(DACP_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            discovering.set(false)
            Log.w(TAG, "DACP discovery error", error)
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        if (!resolving.compareAndSet(false, true)) return
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    Log.w(TAG, "DACP resolve failed: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    host = serviceInfo.host?.hostAddress ?: ""
                    port = serviceInfo.port
                    resolving.set(false)
                    stopDiscovery()
                    val queued = synchronized(pendingLock) {
                        ArrayList<String>(pendingPaths).also { pendingPaths.clear() }
                    }
                    queued.forEach(::send)
                }
            })
        } catch (error: Exception) {
            resolving.set(false)
            Log.w(TAG, "DACP resolve error", error)
        }
    }

    fun play() = send("/ctrl-int/1/play")
    fun pause() = send("/ctrl-int/1/pause")
    fun next() = send("/ctrl-int/1/nextitem")
    fun previous() = send("/ctrl-int/1/previtem")

    fun reset() {
        activeRemote = ""
        serviceName = ""
        host = ""
        port = 0
        synchronized(pendingLock) { pendingPaths.clear() }
        stopDiscovery()
    }

    fun release() {
        released.set(true)
        reset()
        executor.shutdownNow()
    }

    private fun send(path: String) {
        val endpointHost = host
        val endpointPort = port
        val remote = activeRemote
        if (remote.isBlank()) return
        if (endpointHost.isBlank() || endpointPort <= 0) {
            synchronized(pendingLock) {
                if (pendingPaths.lastOrNull() != path) pendingPaths.addLast(path)
                while (pendingPaths.size > MAX_PENDING_COMMANDS) pendingPaths.removeFirst()
            }
            discover()
            return
        }
        try {
            executor.execute {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL("http://$endpointHost:$endpointPort$path").openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Active-Remote", remote)
                    connection.setRequestProperty("Host", "$endpointHost:$endpointPort")
                    connection.connectTimeout = 1800
                    connection.readTimeout = 1800
                    val response = connection.responseCode
                    if (response !in 200..299) throw IllegalStateException("DACP HTTP $response")
                } catch (error: Exception) {
                    host = ""
                    port = 0
                    synchronized(pendingLock) {
                        if (pendingPaths.lastOrNull() != path) pendingPaths.addLast(path)
                        while (pendingPaths.size > MAX_PENDING_COMMANDS) pendingPaths.removeFirst()
                    }
                    discover()
                    Log.w(TAG, "DACP command failed: $path", error)
                } finally {
                    connection?.disconnect()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        if (!discovering.getAndSet(false)) return
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "C3MediaDacp"
        private const val DACP_TYPE = "_dacp._tcp."
        private const val MAX_PENDING_COMMANDS = 4
    }
}
