package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Sends the play/pause/previous/next commands back to the iPhone. */
class DacpController(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var activeRemote = ""
    @Volatile private var host = ""
    @Volatile private var port = 0

    fun update(dacpId: String, remote: String) {
        activeRemote = remote
        host = ""
        port = 0
        if (dacpId.isBlank()) return
        val info = NsdServiceInfo().apply {
            serviceType = "_dacp._tcp"
            serviceName = "iTunes_Ctrl_$dacpId"
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "DACP resolve failed: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    host = serviceInfo.host?.hostAddress ?: ""
                    port = serviceInfo.port
                }
            })
        } catch (error: Exception) {
            Log.w(TAG, "DACP resolve error", error)
        }
    }

    fun play() = send("/ctrl-int/1/play")
    fun pause() = send("/ctrl-int/1/pause")
    fun next() = send("/ctrl-int/1/nextitem")
    fun previous() = send("/ctrl-int/1/previtem")

    fun reset() {
        activeRemote = ""
        host = ""
        port = 0
    }

    fun release() {
        reset()
        executor.shutdownNow()
    }

    private fun send(path: String) {
        val endpointHost = host
        val endpointPort = port
        val remote = activeRemote
        if (endpointHost.isBlank() || endpointPort <= 0 || remote.isBlank()) return
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
                    connection.responseCode
                } catch (error: Exception) {
                    Log.w(TAG, "DACP command failed: $path", error)
                } finally {
                    connection?.disconnect()
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "C3MediaDacp"
    }
}
