package io.github.jqssun.airplay.power

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.jqssun.airplay.connectivity.HotspotController

class EnergyController(
    context: Context,
    private val hotspot: HotspotController,
    private val isStreaming: () -> Boolean,
    private val listener: (EnergySnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    @Volatile private var lastActivityAt = SystemClock.elapsedRealtime()
    @Volatile private var lastSnapshot = EnergySnapshot()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            checkNow()
            val delay = if (lastSnapshot.mode == EnergyMode.STANDBY) STANDBY_POLL_MS else ACTIVE_POLL_MS
            handler.postDelayed(this, delay)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    fun noteActivity() {
        lastActivityAt = SystemClock.elapsedRealtime()
        // Video arrives up to 30 times per second. Poll battery/ARP only when an
        // event must wake standby; the regular ticker handles active operation.
        if (running && lastSnapshot.mode == EnergyMode.STANDBY) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
    }

    fun checkNow(): EnergySnapshot {
        val streaming = isStreaming()
        val phonePresent = streaming || hotspot.hasConnectedClient()
        val now = SystemClock.elapsedRealtime()
        if (phonePresent) lastActivityAt = now

        val battery = readBattery()
        val mode = EnergyPolicy.selectMode(phonePresent, now - lastActivityAt, battery.temperatureC)
        val snapshot = EnergySnapshot(
            mode = mode,
            phonePresent = phonePresent,
            batteryPercent = battery.percent,
            batteryTemperatureC = battery.temperatureC,
            charging = battery.charging,
            availableMemoryMb = readAvailableMemoryMb(),
        )
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            listener(snapshot)
        }
        return snapshot
    }

    private fun readBattery(): BatteryReading {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatteryReading()
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return BatteryReading(
            percent = if (level >= 0) level * 100 / scale else -1,
            temperatureC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun readAvailableMemoryMb(): Long {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private data class BatteryReading(
        val percent: Int = -1,
        val temperatureC: Float = 0f,
        val charging: Boolean = false,
    )

    companion object {
        private const val ACTIVE_POLL_MS = 3_000L
        private const val STANDBY_POLL_MS = 15_000L
    }
}
