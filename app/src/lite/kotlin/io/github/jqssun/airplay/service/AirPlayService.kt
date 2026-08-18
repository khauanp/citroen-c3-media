package io.github.jqssun.airplay.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.C3MediaApplication
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.audio.DmapParser
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.connectivity.HotspotController
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.power.EnergyController
import io.github.jqssun.airplay.power.EnergyMode
import io.github.jqssun.airplay.power.EnergySnapshot
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

class AirPlayService : Service(), RaopCallbackHandler {
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<MediaStateListener>()
    private val stateLock = Any()
    private val audioRenderer = AudioRenderer()
    val videoRenderer = VideoRenderer().apply {
        enforceSdr = false
        realtimeDecoderPriority = true
        lowLatency = true
        scheduledOutputBufferRelease = true
    }

    private lateinit var audioManager: AudioManager
    private lateinit var dacp: DacpController
    private var nativeHandle = 0L
    private var nsd: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    @Volatile private var startupPending = false
    private lateinit var hotspotController: HotspotController
    private lateinit var energyController: EnergyController
    @Volatile private var state = MediaState()
    @Volatile private var progressBaseMs = 0L
    @Volatile private var progressBaseAt = 0L

    inner class LocalBinder : Binder() {
        val service: AirPlayService get() = this@AirPlayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dacp = DacpController(this)
        hotspotController = HotspotController(this)
        energyController = EnergyController(
            this,
            hotspotController,
            isStreaming = { state.connectionCount > 0 || state.mode == DisplayMode.MIRROR || state.playing },
            listener = ::applyEnergySnapshot,
        )
        createNotificationChannel()
        promoteToForeground()
        startupPending = true
        val hotspotResult = hotspotController.ensureStarted()
        updateState(state.copy(message = hotspotResult.message))
        if (hotspotResult.active && !hotspotController.isActive()) {
            waitForHotspot(0)
        } else {
            mainHandler.postDelayed({
                startupPending = false
                startServer()
            }, 300L)
        }
        energyController.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY restarts with a null intent after a native/process crash.
        // Bring the HOME dashboard back instead of leaving the ASUS launcher visible.
        if (intent == null) C3MediaApplication.scheduleDashboardRestart(this, 700L, true)
        if (!startupPending && nativeHandle == 0L && state.mode != DisplayMode.ERROR) startServer()
        return START_STICKY
    }

    fun addListener(listener: MediaStateListener) {
        listeners.add(listener)
        mainHandler.post { listener.onMediaState(snapshot()) }
    }

    fun removeListener(listener: MediaStateListener) {
        listeners.remove(listener)
    }

    fun snapshot(): MediaState {
        val base = state
        if (!base.playing || progressBaseAt == 0L || base.durationMs <= 0L) return base
        val elapsed = SystemClock.elapsedRealtime() - progressBaseAt
        return base.copy(positionMs = (progressBaseMs + elapsed).coerceAtMost(base.durationMs))
    }

    fun setVideoSurface(surface: Surface) = videoRenderer.setSurface(surface)

    fun clearVideoSurface(surface: Surface) = videoRenderer.clearSurface(surface)

    fun togglePlayPause() {
        val positionBeforeChange = snapshot().positionMs
        val playing = !state.playing
        updateState(state.copy(playing = playing, positionMs = positionBeforeChange))
        if (playing) {
            progressBaseMs = positionBeforeChange
            progressBaseAt = SystemClock.elapsedRealtime()
            dacp.play()
        } else {
            progressBaseMs = positionBeforeChange
            progressBaseAt = 0L
            dacp.pause()
        }
    }

    fun nextTrack() = dacp.next()

    fun previousTrack() = dacp.previous()

    private fun waitForHotspot(attempt: Int) {
        if (hotspotController.isActive() || attempt >= 30) {
            startupPending = false
            startServer()
            return
        }
        updateState(state.copy(message = "Ativando rede ${HotspotController.SSID}…"))
        mainHandler.postDelayed({ waitForHotspot(attempt + 1) }, 500L)
    }

    @SuppressLint("WakelockTimeout")
    private fun startServer() {
        if (nativeHandle != 0L) return
        updateState(MediaState(mode = DisplayMode.STARTING, message = "Preparando conexão com o iPhone…"))
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "c3media:receiver").apply {
                setReferenceCounted(false)
                acquire()
            }
            nsd = NsdServiceManager(this).apply { acquireMulticastLock() }
            NativeBridge.nativeSetDefaultStreamValues(
                audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
                audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
            )
            val handle = NativeBridge.nativeInit(
                this,
                hardwareAddress(),
                RECEIVER_NAME,
                filesDir.resolve("airplay.pem").absolutePath,
                true,
                false,
            )
            if (handle == 0L) throw IllegalStateException("O receptor AirPlay não iniciou")
            nativeHandle = handle
            audioRenderer.attachEngine(handle)
            NativeBridge.nativeSetH265Enabled(handle, false)
            NativeBridge.nativeSetCodecs(handle, true, true)
            NativeBridge.nativeSetHlsEnabled(handle, false)
            NativeBridge.nativeSetAudioEnabled(handle, true)
            NativeBridge.nativeSetPlist(handle, "maxFPS", 30)
            NativeBridge.nativeSetPlist(handle, "overscanned", 0)
            NativeBridge.nativeSetPlist(handle, "audio_delay_micros", 0)
            NativeBridge.nativeSetDisplaySize(handle, 1280, 800, 30)
            val port = NativeBridge.nativeStart(handle, 7000)
            if (port < 0) throw IllegalStateException("A porta de conexão não abriu")

            val raop = NativeBridge.nativeGetRaopTxtRecords(handle) ?: emptyMap()
            val airplay = NativeBridge.nativeGetAirplayTxtRecords(handle) ?: emptyMap()
            val raopName = NativeBridge.nativeGetRaopServiceName(handle) ?: RECEIVER_NAME
            val serverName = NativeBridge.nativeGetServerName(handle) ?: RECEIVER_NAME
            nsd?.registerRaop(raopName, port, raop)
            nsd?.registerAirplay(serverName, port, airplay)
            updateState(
                MediaState(
                    serverRunning = true,
                    mode = DisplayMode.IDLE,
                    message = "Pronto para conectar",
                ),
            )
            Log.i(TAG, "AirPlay ready on port $port")
        } catch (error: Throwable) {
            Log.e(TAG, "AirPlay startup failed", error)
            releaseNative()
            updateState(
                MediaState(
                    serverRunning = false,
                    mode = DisplayMode.ERROR,
                    message = error.message ?: "Falha ao iniciar o receptor",
                ),
            )
        }
    }

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        energyController.noteActivity()
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    /** Called by the native audio engine; kept public because JNI resolves it by name. */
    fun onLog(message: String) {
        Log.d("AirPlayNative", message)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        energyController.noteActivity()
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        if (usingScreen) {
            updateState(state.copy(mode = DisplayMode.MIRROR, playing = true, message = "Waze no iPhone"))
        } else {
            progressBaseAt = SystemClock.elapsedRealtime()
            updateState(state.copy(mode = DisplayMode.AUDIO, playing = true, message = "Reproduzindo do iPhone"))
        }
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        energyController.noteActivity()
        if (w > 0 && h > 0) {
            videoRenderer.setResolution(w.toInt(), h.toInt())
            updateState(state.copy(mode = DisplayMode.MIRROR, message = "Espelhamento ativo"))
        }
    }

    override fun onVolumeChange(volume: Float) {
        val fraction = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        mainHandler.post {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val wanted = (fraction * max).roundToInt()
            if (wanted != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, wanted, 0)
            }
        }
    }

    override fun onClientVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (current == 0) -144f else -30f + 30f * current / max
    }

    override fun onAudioTeardown() {
        progressBaseMs = snapshot().positionMs
        progressBaseAt = 0L
        updateState(state.copy(playing = false))
    }

    override fun onConnectionInit() {
        energyController.noteActivity()
        updateState(
            state.copy(
                connectionCount = state.connectionCount + 1,
                message = "iPhone conectado",
            ),
        )
    }

    override fun onConnectionDestroy() {
        val count = (state.connectionCount - 1).coerceAtLeast(0)
        if (count == 0) {
            audioRenderer.stop()
            videoRenderer.resetStream()
            dacp.reset()
            progressBaseMs = 0L
            progressBaseAt = 0L
            updateState(
                MediaState(
                    serverRunning = nativeHandle != 0L,
                    mode = DisplayMode.IDLE,
                    message = "Pronto para conectar",
                    energy = state.energy,
                ),
            )
        } else {
            updateState(state.copy(connectionCount = count))
        }
    }

    override fun onConnectionReset(reason: Int) {
        Log.w(TAG, "Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        updateState(state.copy(mode = DisplayMode.PIN, pin = pin, message = "Digite este código no iPhone"))
    }

    override fun onMetadata(data: ByteArray) {
        energyController.noteActivity()
        val info = TrackInfo.fromDmap(DmapParser.parse(data), state.track.coverArt)
        val duration = if (info.durationMs > 0L) info.durationMs else state.durationMs
        updateState(
            state.copy(
                // Metadata also arrives while the iPhone screen is mirrored. Do not hide
                // Waze just because YouTube Music changed tracks in the background.
                mode = audioUpdateMode(),
                track = info,
                durationMs = duration,
                message = if (state.mode == DisplayMode.MIRROR) "Espelhamento ativo" else "Reproduzindo do iPhone",
            ),
        )
    }

    override fun onCoverArt(data: ByteArray) {
        if (state.energy.thermalLimited || state.energy.availableMemoryMb in 1..LOW_MEMORY_MB) return
        val art = decodeCoverArt(data) ?: return
        mainHandler.post {
            val previous = state.track.coverArt
            updateState(state.copy(track = state.track.copy(coverArt = art)))
            if (previous != null && previous !== art && !previous.isRecycled) previous.recycle()
        }
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        energyController.noteActivity()
        val position = (((curr - start) / 44100.0) * 1000.0).toLong().coerceAtLeast(0L)
        val duration = (((end - start) / 44100.0) * 1000.0).toLong().coerceAtLeast(0L)
        if (duration <= 0L) return
        progressBaseMs = position
        progressBaseAt = SystemClock.elapsedRealtime()
        updateState(
            state.copy(
                mode = audioUpdateMode(),
                positionMs = position,
                durationMs = duration,
                playing = true,
            ),
        )
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        dacp.update(dacpId, activeRemote)
    }

    override fun onAudioOnly(audioOnly: Boolean) {
        if (audioOnly && state.connectionCount > 0) {
            videoRenderer.resetStream()
            updateState(state.copy(mode = DisplayMode.AUDIO, message = "Reproduzindo do iPhone"))
        } else if (!audioOnly && state.connectionCount > 0) {
            updateState(state.copy(mode = DisplayMode.MIRROR, message = "Espelhamento ativo"))
        }
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        energyController.noteActivity()
        if (nativeHandle != 0L) {
            NativeBridge.nativeUpdatePlaybackInfo(nativeHandle, 0f, 0f, 0f, true)
        }
    }

    override fun onVideoScrub(positionSeconds: Float) = Unit
    override fun onVideoRate(rate: Float) = Unit
    override fun onVideoStop() {
        // iOS closes the mirroring decoder before opening YouTube/YouTube Music.
        // Keeping that API-21 codec alive caused memory pressure and vendor codec
        // crashes which could take down the HOME activity as well.
        videoRenderer.resetStream()
        val nextMode = if (state.playing || state.track.title.isNotBlank()) {
            DisplayMode.AUDIO
        } else {
            DisplayMode.IDLE
        }
        updateState(state.copy(mode = nextMode, message = if (nextMode == DisplayMode.AUDIO) {
            "Música do iPhone"
        } else {
            "Pronto para conectar"
        }))
    }
    override fun onVideoSessionPoll() = Unit

    private fun audioUpdateMode(): DisplayMode =
        if (state.mode == DisplayMode.MIRROR) DisplayMode.MIRROR else DisplayMode.AUDIO

    private fun applyEnergySnapshot(snapshot: EnergySnapshot) {
        val previous = state.energy.mode
        when (snapshot.mode) {
            EnergyMode.STANDBY -> {
                if (previous != EnergyMode.STANDBY) {
                    videoRenderer.resetStream()
                    audioRenderer.stop()
                    progressBaseAt = 0L
                    val art = state.track.coverArt
                    updateState(
                        state.copy(
                            mode = DisplayMode.STANDBY,
                            playing = false,
                            track = state.track.copy(coverArt = null),
                            message = "Aguardando o iPhone",
                            energy = snapshot,
                        ),
                    )
                    if (art != null && !art.isRecycled) art.recycle()
                } else {
                    updateState(state.copy(energy = snapshot))
                }
            }

            EnergyMode.ACTIVE, EnergyMode.THERMAL_PROTECTION -> {
                if (previous == EnergyMode.STANDBY) {
                    updateState(
                        state.copy(
                            mode = DisplayMode.IDLE,
                            message = if (snapshot.thermalLimited) "Proteção térmica ativa" else "iPhone detectado",
                            energy = snapshot,
                        ),
                    )
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        putExtra(C3MediaApplication.EXTRA_WAKE_ANIMATION, true)
                    }
                    try { startActivity(intent) } catch (_: Exception) {}
                } else {
                    updateState(state.copy(energy = snapshot))
                }
            }
        }
    }

    private fun decodeCoverArt(data: ByteArray): android.graphics.Bitmap? {
        if (data.isEmpty() || data.size > MAX_COVER_BYTES) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > COVER_DECODE_LIMIT ||
                bounds.outHeight / sample > COVER_DECODE_LIMIT
            ) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(
                data,
                0,
                data.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    inDither = true
                },
            ) ?: return null
            val largest = maxOf(bitmap.width, bitmap.height)
            if (largest <= COVER_DISPLAY_LIMIT) return bitmap
            val ratio = COVER_DISPLAY_LIMIT.toFloat() / largest
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            scaled
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Cover art ignored to preserve memory during mirroring", error)
            null
        } catch (error: RuntimeException) {
            Log.w(TAG, "Invalid cover art ignored", error)
            null
        }
    }

    private fun updateState(next: MediaState) {
        synchronized(stateLock) { state = next }
        mainHandler.post {
            val current = snapshot()
            listeners.forEach { listener ->
                try {
                    listener.onMediaState(current)
                } catch (_: Exception) {
                }
            }
            if (foregroundStarted) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun promoteToForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
        foregroundStarted = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getActivity(this, 0, intent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val current = state
        val title = current.track.title.ifBlank { getString(R.string.notification_title) }
        val text = current.track.artist.ifBlank { current.message.ifBlank { getString(R.string.notification_text) } }
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun hardwareAddress(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (network.name.startsWith("wlan") || network.name.startsWith("eth")) {
                    val address = network.hardwareAddress
                    if (address != null && address.size == 6 && address.any { it != 0.toByte() }) return address
                }
            }
        } catch (_: Exception) {
        }
        val preferences = getSharedPreferences("c3media", Context.MODE_PRIVATE)
        val saved = preferences.getString("receiver_mac", null)
        if (!saved.isNullOrBlank()) {
            try {
                return saved.split(":").map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: Exception) {
            }
        }
        return ByteArray(6).also { bytes ->
            SecureRandom().nextBytes(bytes)
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
            preferences.edit().putString("receiver_mac", bytes.joinToString(":") { "%02x".format(it) }).apply()
        }
    }

    private fun releaseNative() {
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            try { NativeBridge.nativeStop(nativeHandle) } catch (_: Throwable) {}
            try { NativeBridge.nativeDestroy(nativeHandle) } catch (_: Throwable) {}
            nativeHandle = 0L
        }
        nsd?.release()
        nsd = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        listeners.clear()
        energyController.stop()
        dacp.release()
        audioRenderer.stop()
        releaseNative()
        videoRenderer.release()
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(null)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        C3MediaApplication.scheduleDashboardRestart(this, 1_000L, false)
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val RECEIVER_NAME = "Citroën C3"
        private const val TAG = "C3MediaService"
        private const val CHANNEL_ID = "c3_media_receiver"
        private const val NOTIFICATION_ID = 303
        private const val MAX_COVER_BYTES = 8 * 1024 * 1024
        private const val COVER_DECODE_LIMIT = 512
        private const val COVER_DISPLAY_LIMIT = 384
        private const val LOW_MEMORY_MB = 96L
    }
}
