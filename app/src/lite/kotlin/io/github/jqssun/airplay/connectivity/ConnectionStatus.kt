package io.github.jqssun.airplay.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo

data class ConnectionStatus(
    val networkReady: Boolean,
    val hotspotActive: Boolean,
    val radioConnected: Boolean,
    val networkLabel: String,
)

object ConnectionStatusReader {
    @Suppress("DEPRECATION")
    fun read(context: Context, hotspot: HotspotController): ConnectionStatus {
        val hotspotActive = hotspot.isActive()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiConnected = try {
            manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.state == NetworkInfo.State.CONNECTED
        } catch (_: Exception) {
            false
        }
        val bluetooth = try {
            BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(BluetoothProfile.A2DP) ==
                BluetoothAdapter.STATE_CONNECTED
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        val label = when {
            hotspotActive -> HotspotController.SSID
            wifiConnected -> "Wi-Fi conectado"
            else -> "Sem rede local"
        }
        return ConnectionStatus(hotspotActive || wifiConnected, hotspotActive, bluetooth, label)
    }
}
