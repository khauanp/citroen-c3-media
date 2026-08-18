package io.github.jqssun.airplay.connectivity

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.io.File

/**
 * Android 5 still exposes hotspot control through the vendor WifiManager implementation.
 * Reflection is intentionally isolated here so failure simply falls back to a shared Wi-Fi.
 */
class HotspotController(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    data class Result(val active: Boolean, val message: String)

    fun ensureStarted(): Result {
        if (isActive()) return Result(true, "Rede $SSID ativa")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            return Result(false, "Use a mesma rede Wi-Fi no tablet e no iPhone")
        }
        val wifiWasEnabled = wifi.isWifiEnabled
        return try {
            val config = WifiConfiguration().apply {
                SSID = SSID
                preSharedKey = PASSWORD
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
            }
            val method = wifi.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType,
            )
            if (wifiWasEnabled) wifi.isWifiEnabled = false
            val accepted = method.invoke(wifi, config, true) as? Boolean ?: false
            if (!accepted && wifiWasEnabled) wifi.isWifiEnabled = true
            Result(accepted, if (accepted) "Ativando rede $SSID…" else "Ative o ponto de acesso do tablet")
        } catch (error: Exception) {
            if (wifiWasEnabled) {
                try { wifi.isWifiEnabled = true } catch (_: Exception) {}
            }
            Log.w(TAG, "Hotspot control unavailable", error)
            Result(false, "Ative o ponto de acesso ou use a mesma rede Wi-Fi")
        }
    }

    fun isActive(): Boolean {
        return try {
            val method = wifi.javaClass.getMethod("getWifiApState")
            val state = method.invoke(wifi) as Int
            state == WIFI_AP_STATE_ENABLED || state == WIFI_AP_STATE_ENABLING
        } catch (_: Exception) {
            false
        }
    }

    fun accessPointAddress(): String {
        if (!isActive()) return DEFAULT_ADDRESS
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var privateFallback: String? = null
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address || address.isLoopbackAddress || !address.isSiteLocalAddress) continue
                    val host = address.hostAddress ?: continue
                    if (network.name.startsWith("wlan") || network.name.startsWith("ap")) return host
                    if (privateFallback == null) privateFallback = host
                }
            }
            privateFallback ?: DEFAULT_ADDRESS
        } catch (_: Exception) {
            DEFAULT_ADDRESS
        }
    }

    fun recommendedIphoneAddress(): String {
        val parts = accessPointAddress().split(".")
        if (parts.size != 4) return DEFAULT_IPHONE_ADDRESS
        val last = if (parts[3] == "2") "3" else "2"
        return parts.take(3).plus(last).joinToString(".")
    }

    /**
     * Android 5 has no public hotspot-client API. The kernel ARP table is the most
     * portable low-cost signal available on the ASUS firmware. AirPlay activity is
     * used as a second signal by EnergyController when vendors hide this table.
     */
    fun hasConnectedClient(): Boolean {
        if (!isActive()) return false
        return try {
            File("/proc/net/arp").useLines { lines ->
                lines.drop(1).any { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    columns.size >= 6 && columns[2] == "0x2" &&
                        columns[3] != "00:00:00:00:00:00"
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val SSID = "Citroen-C3"
        const val PASSWORD = "C3Media26"
        const val SUBNET_MASK = "255.255.255.0"
        private const val DEFAULT_ADDRESS = "192.168.43.1"
        private const val DEFAULT_IPHONE_ADDRESS = "192.168.43.2"
        private const val WIFI_AP_STATE_ENABLING = 12
        private const val WIFI_AP_STATE_ENABLED = 13
        private const val TAG = "C3MediaHotspot"
    }
}
