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
import io.github.jqssun.airplay.ui.DayNightPolicy

class MainActivity : Activity(), SurfaceHolder.Callback, DashboardView.Actions {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var dashboard: DashboardView
    private lateinit var hotspot: HotspotController
    private val handler = Handler()
    private var service: AirPlayService? = null
    private var bound = false
    private var technicalWindowOpen = false
    private var reportPromptOpen = false
    @Volatile private var reportExportOpen = false
    private var debugDemoMode: String? = null
    private var lastEnergyMode = EnergyMode.ACTIVE

    private val stateListener = MediaStateListener { state ->
        if (debugDemoMode == null) runOnUiThread { showState(state) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as AirPlayService.LocalBinder).service
            bound = true
            service?.addListener(stateListener)
            if (surfaceView.holder.surface?.isValid == true) {
                service?.setVideoSurface(surfaceView.holder.surface)
            }
            if (debugDemoMode == "audio-probe") service?.runDebugAudioProbe()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeListener(stateListener)
            service = null
            bound = false
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            dashboard.updateConnection(ConnectionStatusReader.read(this@MainActivity, hotspot))
            if (debugDemoMode == null) service?.let {
                val snapshot = it.snapshot()
                dashboard.updateMedia(snapshot)
                CrashDiagnostics.heartbeat(
                    "mode=${snapshot.mode} playing=${snapshot.playing} " +
                        "connections=${snapshot.connectionCount} track=${snapshot.track.title.take(80)}",
                )
            }
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashDiagnostics.event("ACTIVITY", "onCreate")
        debugDemoMode = if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent.getStringExtra("debug_demo")
        } else null
        hotspot = HotspotController(this)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
        window.attributes = window.attributes.apply { screenBrightness = DayNightPolicy.activeBrightnessNow() }
        buildUi()
        if (intent.getBooleanExtra(C3MediaApplication.EXTRA_WAKE_ANIMATION, false)) {
            dashboard.triggerStartupAnimation()
            wakeDisplay()
        }
        showDebugDemo()
        hideSystemUi()
        startReceiverService()
        handler.post(statusTicker)
        if (debugDemoMode == null) handler.post { offerPendingCrashReport() }
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
            "audio-probe" -> MediaState(
                serverRunning = true,
                connectionCount = 1,
                mode = DisplayMode.AUDIO,
                track = TrackInfo(title = "Teste do receptor", artist = "C3 Media"),
                playing = true,
                message = "Testando saída de áudio",
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
            updateSurfaceLayout()
            surfaceView.visibility = View.VISIBLE
            surfaceView.holder.surface?.takeIf { it.isValid }?.let { service?.setVideoSurface(it) }
        } else if (!mirror && surfaceView.visibility == View.VISIBLE) {
            surfaceView.holder.surface?.takeIf { it.isValid }?.let { service?.clearVideoSurface(it) }
            surfaceView.visibility = View.INVISIBLE
            updateSurfaceLayout()
        } else if (mirror) {
            updateSurfaceLayout()
        }
    }

    private fun updateSurfaceLayout() {
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) {
            root.post { updateSurfaceLayout() }
            return
        }
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
    }

    private fun applyEnergyUi(state: MediaState) {
        val mode = state.energy.mode
        if (mode == EnergyMode.STANDBY) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = if (mode == EnergyMode.THERMAL_PROTECTION) 0.42f
                    else DayNightPolicy.activeBrightnessNow()
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

    override fun onConnectionHelp() {
        showMobileDataGuide()
    }

    override fun onMirrorHelp() {
        AlertDialog.Builder(this)
            .setTitle("Espelhamento do iPhone")
            .setMessage(
                "No iPhone, selecione Citroën C3 em Espelhar a Tela. Waze e outros " +
                    "aplicativos aparecerão com rotação automática e sem esticar.\n\n" +
                    "O AirPlay envia imagem e áudio, mas não envia os toques do tablet " +
                    "de volta ao iPhone. Todo controle permanece no celular.",
            )
            .setPositiveButton("Entendi", null)
            .show()
    }

    override fun onMusicHelp() {
        AlertDialog.Builder(this)
            .setTitle("YouTube Music e Spotify")
            .setMessage(
                "Abra a música no iPhone, toque no seletor AirPlay e escolha Citroën C3. " +
                    "A C3 Media continuará aberta e mostrará os metadados enviados pelo iPhone.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onCloseApp() {
        // The explicit close button hides only the dashboard. Keeping the
        // foreground receiver alive preserves the selected AirPlay output.
        CrashDiagnostics.event("USER_ACTION", "close_button_clicked dashboard_moved_to_background")
        moveTaskToBack(true)
    }

    private fun offerPendingCrashReport() {
        if (reportPromptOpen || !CrashDiagnostics.hasPendingReport(this) || isFinishing) return
        reportPromptOpen = true
        AlertDialog.Builder(this)
            .setTitle("O C3 Media fechou inesperadamente")
            .setMessage(
                "O processo da sessão anterior foi registrado. Escolha onde salvar o relatório " +
                    "para que a falha possa ser analisada e corrigida na origem.",
            )
            .setPositiveButton("Escolher onde salvar") { _, _ ->
                reportPromptOpen = false
                val saveIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, CrashDiagnostics.suggestedFileName())
                }
                try {
                    // Device-owner lock task and the dashboard's auto-return normally
                    // keep C3 Media above every other Activity. Suspend both while the
                    // Android document picker owns the screen, then restore them after
                    // the user saves or cancels.
                    reportExportOpen = true
                    CrashDiagnostics.event("REPORT", "file_picker_open kiosk_suspended=true")
                    try { stopLockTask() } catch (_: Exception) {}
                    startActivityForResult(saveIntent, REQUEST_SAVE_CRASH_REPORT)
                } catch (failure: Throwable) {
                    reportExportOpen = false
                    CrashDiagnostics.event("REPORT_ERROR", "file_picker ${failure.javaClass.name}: ${failure.message}")
                    Toast.makeText(this, "Não foi possível abrir o seletor de arquivos.", Toast.LENGTH_LONG).show()
                    restoreDashboardAfterReportPicker()
                }
            }
            .setNegativeButton("Depois") { _, _ ->
                reportPromptOpen = false
                CrashDiagnostics.event("REPORT", "save_postponed")
            }
            .setOnCancelListener { reportPromptOpen = false }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE_CRASH_REPORT) return
        reportExportOpen = false
        val destination = data?.data
        if (resultCode != RESULT_OK || destination == null) {
            CrashDiagnostics.event("REPORT", "file_picker_cancelled report_preserved=true")
            restoreDashboardAfterReportPicker()
            return
        }
        try {
            contentResolver.openOutputStream(destination, "w")?.bufferedWriter()?.use {
                it.write(CrashDiagnostics.reportText(this))
            } ?: throw IllegalStateException("Destino de relatório indisponível")
            CrashDiagnostics.markReportSaved(this)
            CrashDiagnostics.event("REPORT", "saved_by_user")
            Toast.makeText(this, "Relatório salvo.", Toast.LENGTH_LONG).show()
        } catch (failure: Throwable) {
            CrashDiagnostics.event("REPORT_ERROR", "save ${failure.javaClass.name}: ${failure.message}")
            Toast.makeText(this, "Não foi possível salvar. O relatório continua guardado no app.", Toast.LENGTH_LONG).show()
        } finally {
            restoreDashboardAfterReportPicker()
        }
    }

    private fun restoreDashboardAfterReportPicker() {
        handler.post {
            if (isFinishing || reportExportOpen) return@post
            hideSystemUi()
            enterKioskIfConfigured()
        }
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
            "Ponto de acesso do tablet",
            "Rede Wi-Fi alternativa",
            "Internet móvel no iPhone",
            "Dados da conexão",
            "Energia e temperatura",
            "Último relatório de falha",
        )
        AlertDialog.Builder(this)
            .setTitle("Ajustes técnicos")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> openSystemSettings("android.settings.TETHER_SETTINGS")
                    1 -> openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
                    2 -> showMobileDataGuide()
                    3 -> showConnectionInfo()
                    4 -> showEnergyInfo()
                    5 -> showLastCrash()
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
            append("Saída de áudio: cabo auxiliar do tablet")
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
            append("Após uma desconexão confirmada, a central libera áudio, vídeo e capas. ")
            append("O receptor AirPlay permanece pronto para a reconexão.")
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
            if (crash.exists()) crash.readText().take(6_000) else "Nenhum encerramento inesperado registrado."
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
        if (hasFocus && !reportExportOpen) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        CrashDiagnostics.event("ACTIVITY", "onResume")
        if (reportExportOpen) return
        hideSystemUi()
        if (technicalWindowOpen) technicalWindowOpen = false
        enterKioskIfConfigured()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        CrashDiagnostics.event(
            "ACTIVITY",
            "onNewIntent wake=${intent?.getBooleanExtra(C3MediaApplication.EXTRA_WAKE_ANIMATION, false)}",
        )
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent?.getStringExtra("debug_demo")?.let { mode ->
                debugDemoMode = mode
                showDebugDemo()
                if (mode == "audio-probe") service?.runDebugAudioProbe()
            }
        }
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
        if (!technicalWindowOpen && !reportExportOpen) {
            handler.postDelayed({
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, 180L)
        }
    }

    override fun onDestroy() {
        CrashDiagnostics.event("ACTIVITY", "onDestroy changingConfiguration=$isChangingConfigurations")
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
        private const val REQUEST_SAVE_CRASH_REPORT = 4312
    }
}
