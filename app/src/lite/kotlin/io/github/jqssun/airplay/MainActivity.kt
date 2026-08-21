package io.github.jqssun.airplay

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.connectivity.ConnectionStatusReader
import io.github.jqssun.airplay.connectivity.HotspotController
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.DisplayMode
import io.github.jqssun.airplay.service.MediaState
import io.github.jqssun.airplay.service.MediaStateListener
import io.github.jqssun.airplay.power.EnergyMode
import io.github.jqssun.airplay.ui.DashboardView

class MainActivity : Activity(), SurfaceHolder.Callback, DashboardView.Actions {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var dashboard: DashboardView
    private lateinit var hotspot: HotspotController
    private val handler = Handler()
    private var service: AirPlayService? = null
    private var bound = false
    private var technicalWindowOpen = false
    private var debugDemoMode: String? = null
    private var lastEnergyMode = EnergyMode.ACTIVE

    private val stateListener = MediaStateListener { state ->
        if (debugDemoMode == null) runOnUiThread { showState(state) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as AirPlayService.LocalBinder).service
            bound = true
            dashboard.setMapTileStore(service?.mapTileStore)
            service?.addListener(stateListener)
            if (surfaceView.holder.surface?.isValid == true) {
                service?.setVideoSurface(surfaceView.holder.surface)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeListener(stateListener)
            dashboard.setMapTileStore(null)
            service = null
            bound = false
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            dashboard.updateConnection(ConnectionStatusReader.read(this@MainActivity, hotspot))
            if (debugDemoMode == null) service?.let { dashboard.updateMedia(it.snapshot()) }
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugDemoMode = if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent.getStringExtra("debug_demo")
        } else null
        hotspot = HotspotController(this)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
        window.attributes = window.attributes.apply { screenBrightness = 0.78f }
        buildUi()
        if (intent.getBooleanExtra(C3MediaApplication.EXTRA_WAKE_ANIMATION, false)) {
            dashboard.triggerStartupAnimation()
            wakeDisplay()
        }
        showDebugDemo()
        hideSystemUi()
        startReceiverService()
        handler.post(statusTicker)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        surfaceView = SurfaceView(this).apply {
            visibility = View.INVISIBLE
            holder.addCallback(this@MainActivity)
        }
        dashboard = DashboardView(this).apply { actions = this@MainActivity }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            dashboard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
    }

    private fun showDebugDemo() {
        val demo = when (debugDemoMode) {
            "idle" -> MediaState(serverRunning = true, mode = DisplayMode.IDLE, message = "Pronto para conectar")
            "mirror" -> MediaState(
                serverRunning = true,
                connectionCount = 1,
                mode = DisplayMode.MIRROR,
                track = TrackInfo(title = "Midnight Drive", artist = "Citroën Sessions"),
                playing = true,
                message = "Espelhamento ativo",
            )
            "audio" -> MediaState(
                serverRunning = true,
                connectionCount = 1,
                mode = DisplayMode.AUDIO,
                track = TrackInfo(
                    title = "Midnight Drive",
                    artist = "Citroën Sessions",
                    album = "Roads After Dark",
                    durationMs = 238_000L,
                ),
                positionMs = 96_000L,
                durationMs = 238_000L,
                playing = true,
                message = "Reproduzindo do iPhone",
            )
            else -> return
        }
        dashboard.updateMedia(demo)
        showState(demo)
    }

    private fun startReceiverService() {
        val intent = Intent(this, AirPlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun showState(state: MediaState) {
        dashboard.updateMedia(state)
        applyEnergyUi(state)
        val mirror = state.mode == DisplayMode.MIRROR && state.energy.mode != EnergyMode.STANDBY
        if (mirror && surfaceView.visibility != View.VISIBLE) {
            updateSurfaceLayout(true)
            surfaceView.visibility = View.VISIBLE
            surfaceView.holder.surface?.takeIf { it.isValid }?.let { service?.setVideoSurface(it) }
        } else if (!mirror && surfaceView.visibility == View.VISIBLE) {
            surfaceView.holder.surface?.takeIf { it.isValid }?.let { service?.clearVideoSurface(it) }
            surfaceView.visibility = View.INVISIBLE
            updateSurfaceLayout(false)
        } else if (mirror) {
            updateSurfaceLayout(true)
        }
    }

    private fun updateSurfaceLayout(modular: Boolean) {
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) {
            root.post { updateSurfaceLayout(modular) }
            return
        }
        surfaceView.layoutParams = if (modular) {
            val sx = width / 1280f
            val sy = height / 800f
            FrameLayout.LayoutParams((778f * sx).toInt(), (672f * sy).toInt()).apply {
                leftMargin = (124f * sx).toInt()
                topMargin = (108f * sy).toInt()
            }
        } else {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
    }

    private fun applyEnergyUi(state: MediaState) {
        val mode = state.energy.mode
        if (mode == EnergyMode.STANDBY) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = if (mode == EnergyMode.THERMAL_PROTECTION) 0.42f else 0.78f
            }
            if (lastEnergyMode == EnergyMode.STANDBY) {
                dashboard.triggerStartupAnimation()
                wakeDisplay()
            }
        }
        lastEnergyMode = mode
    }

    @Suppress("DEPRECATION")
    private fun wakeDisplay() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "c3media:wake-display",
        )
        lock.acquire(2_500L)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        service?.setVideoSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        service?.setVideoSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        service?.clearVideoSurface(holder.surface)
    }

    override fun onPrevious() {
        service?.previousTrack()
    }

    override fun onPlayPause() {
        service?.togglePlayPause()
    }

    override fun onNext() {
        service?.nextTrack()
    }

    override fun onConnectionHelp() {
        showMobileDataGuide()
    }

    override fun onMapHelp() {
        AlertDialog.Builder(this)
            .setTitle("Waze no tablet")
            .setMessage(
                "Defina a rota no iPhone e selecione Citroën C3 em Espelhar a Tela. " +
                    "A imagem será girada e ajustada automaticamente sem esticar.\n\n" +
                    "Importante: o AirPlay envia imagem e áudio, mas não envia os toques do tablet " +
                    "de volta ao iPhone. O mapa continua sendo controlado no iPhone; os botões de " +
                    "música da C3 Media funcionam pelo toque.",
            )
            .setPositiveButton("Entendi", null)
            .show()
    }

    override fun onMusicHelp() {
        AlertDialog.Builder(this)
            .setTitle("YouTube Music e Spotify")
            .setMessage(
                "Abra a música no iPhone, toque no seletor AirPlay e escolha Citroën C3. " +
                    "A C3 Media continuará aberta e mostrará os controles mesmo durante o Waze.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onTechnicalSettings() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN técnico"
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Acesso técnico")
            .setMessage("Digite o PIN para configurar rede ou Bluetooth.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                if (input.text.toString() == TECH_PIN) showTechnicalMenu()
                else Toast.makeText(this, "PIN incorreto", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showTechnicalMenu() {
        val items = arrayOf(
            "Bluetooth do rádio",
            "Ponto de acesso do tablet",
            "Rede Wi-Fi alternativa",
            "Internet móvel no iPhone",
            "Dados da conexão",
            "Energia e temperatura",
            "Última falha registrada",
        )
        AlertDialog.Builder(this)
            .setTitle("Ajustes técnicos")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                    1 -> openSystemSettings("android.settings.TETHER_SETTINGS")
                    2 -> openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
                    3 -> showMobileDataGuide()
                    4 -> showConnectionInfo()
                    5 -> showEnergyInfo()
                    6 -> showLastCrash()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showConnectionInfo() {
        val status = ConnectionStatusReader.read(this, hotspot)
        val text = buildString {
            append("Nome no iPhone: Citroën C3\n\n")
            append("Rede principal: ${HotspotController.SSID}\n")
            append("Senha: ${HotspotController.PASSWORD}\n\n")
            append("Endereço do tablet: ${hotspot.accessPointAddress()}\n")
            append("IP sugerido no iPhone: ${hotspot.recommendedIphoneAddress()}\n\n")
            append("Rede local: ${if (status.networkReady) "ativa" else "desconectada"}\n")
            append("Bluetooth do rádio: ${if (status.radioConnected) "conectado" else "desconectado"}")
        }
        AlertDialog.Builder(this)
            .setTitle("Conexão")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showEnergyInfo() {
        val energy = service?.snapshot()?.energy
        val text = if (energy == null) {
            "Monitor ainda inicializando."
        } else buildString {
            append("Modo: ${when (energy.mode) {
                EnergyMode.ACTIVE -> "ativo"
                EnergyMode.STANDBY -> "espera"
                EnergyMode.THERMAL_PROTECTION -> "proteção térmica"
            }}\n")
            append("Bateria: ${if (energy.batteryPercent >= 0) "${energy.batteryPercent}%" else "indisponível"}\n")
            append("Temperatura: ${"%.1f".format(energy.batteryTemperatureC)} °C\n")
            append("Alimentação: ${if (energy.charging) "carregando/conectada" else "bateria"}\n")
            append("Memória livre: ${energy.availableMemoryMb} MB\n\n")
            append("A central encerra vídeo, áudio e capas quando o iPhone sai da rede. ")
            append("O receptor mínimo permanece pronto para detectar a volta do aparelho.")
        }
        AlertDialog.Builder(this)
            .setTitle("Gerenciamento de energia")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLastCrash() {
        val crash = filesDir.resolve(C3MediaApplication.CRASH_FILE)
        val text = try {
            if (crash.exists()) crash.readText().take(6_000) else "Nenhuma falha Java registrada."
        } catch (_: Exception) {
            "Não foi possível ler o diagnóstico."
        }
        AlertDialog.Builder(this)
            .setTitle("Última falha")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showMobileDataGuide() {
        val iphoneIp = hotspot.recommendedIphoneAddress()
        AlertDialog.Builder(this)
            .setTitle("Internet móvel + AirPlay")
            .setMessage(
                "No iPhone, abra Ajustes → Wi-Fi → ⓘ ao lado de ${HotspotController.SSID}.\n\n" +
                    "Em Configurar IP, escolha Manual e preencha:\n" +
                    "IP: $iphoneIp\n" +
                    "Máscara: ${HotspotController.SUBNET_MASK}\n" +
                    "Roteador: deixe vazio\n\n" +
                    "Mantenha Configurar DNS em Automático. Se algum app não resolver endereços, " +
                    "use DNS Manual 1.1.1.1. Assim o Wi-Fi fica somente para a C3 Media e a " +
                    "internet do YouTube Music/Waze continua saindo pelos dados móveis.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openSystemSettings(action: String) {
        technicalWindowOpen = true
        try { stopLockTask() } catch (_: Exception) {}
        try {
            startActivity(Intent(action))
        } catch (_: Exception) {
            Toast.makeText(this, "Ajuste indisponível nesta versão do Android", Toast.LENGTH_LONG).show()
            technicalWindowOpen = false
        }
    }

    private fun enterKioskIfConfigured() {
        val policy = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!policy.isDeviceOwnerApp(packageName)) return
        try {
            policy.setLockTaskPackages(ComponentName(this, KioskAdminReceiver::class.java), arrayOf(packageName))
            if (policy.isLockTaskPermitted(packageName)) startLockTask()
        } catch (_: Exception) {
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (technicalWindowOpen) technicalWindowOpen = false
        enterKioskIfConfigured()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(C3MediaApplication.EXTRA_WAKE_ANIMATION, false) == true) {
            dashboard.triggerStartupAnimation()
            wakeDisplay()
        }
    }

    @Deprecated("The dashboard intentionally owns navigation")
    override fun onBackPressed() {
        // Intentionally kept inside the car dashboard.
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!technicalWindowOpen) {
            handler.postDelayed({
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, 180L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (bound) {
            service?.removeListener(stateListener)
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val TECH_PIN = "0303"
    }
}
