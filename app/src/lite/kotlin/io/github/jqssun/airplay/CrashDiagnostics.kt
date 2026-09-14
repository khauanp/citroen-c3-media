package io.github.jqssun.airplay

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded persistent journal for failures that terminate the whole process.
 *
 * Native aborts and system kills cannot run Kotlin cleanup code, so meaningful
 * events are committed while the process is healthy. The next process start
 * turns an unfinished journal into a report before opening a new session.
 */
object CrashDiagnostics {
    private const val PREFS = "c3media_diagnostics"
    private const val CURRENT_LOG = "diagnostics-current.log"
    private const val PREVIOUS_LOG = "diagnostics-previous.log"
    private const val HEARTBEAT = "diagnostics-heartbeat.txt"
    private const val MAX_LOG_BYTES = 384L * 1024L
    private const val HEARTBEAT_INTERVAL_MS = 15_000L

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    private var sessionId = ""
    private var lastHeartbeatAt = 0L

    fun initialize(context: Context) {
        synchronized(lock) {
            val app = context.applicationContext
            appContext = app
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = app.filesDir.resolve(CURRENT_LOG)
            val nowUptime = SystemClock.elapsedRealtime()
            val version = versionName(app)
            val priorActive = prefs.getBoolean("session_active", false)
            val reportAlreadyWritten = prefs.getBoolean("report_ready", false)

            if (priorActive && current.exists() && !reportAlreadyWritten) {
                val previousUptime = prefs.getLong("last_uptime_ms", 0L)
                val previousVersion = prefs.getString("version", "unknown") ?: "unknown"
                val reason = when {
                    previousUptime > 0L && nowUptime + 60_000L < previousUptime -> "DEVICE_REBOOT"
                    previousVersion != version -> "APP_UPDATED_OR_REINSTALLED"
                    else -> "UNEXPECTED_PROCESS_TERMINATION"
                }
                if (reason == "UNEXPECTED_PROCESS_TERMINATION") {
                    writeReportLocked(app, reason, null)
                }
            }

            sessionId = "${System.currentTimeMillis()}-${android.os.Process.myPid()}"
            val previous = app.filesDir.resolve(PREVIOUS_LOG)
            previous.delete()
            if (current.exists()) current.renameTo(previous)
            current.writeText(
                "C3 Media diagnostic session\n" +
                    "session=$sessionId\n" +
                    "version=$version\n" +
                    "device=${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}\n",
            )
            prefs.edit()
                .putBoolean("session_active", true)
                .putBoolean("report_ready", false)
                .putLong("last_uptime_ms", nowUptime)
                .putString("version", version)
                .commit()
            eventLocked(app, "APPLICATION", "process_created pid=${android.os.Process.myPid()}")
        }
    }

    fun event(area: String, detail: String) {
        synchronized(lock) {
            appContext?.let { eventLocked(it, area, detail) }
        }
    }

    fun heartbeat(detail: String) {
        synchronized(lock) {
            val app = appContext ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - lastHeartbeatAt < HEARTBEAT_INTERVAL_MS) return
            lastHeartbeatAt = now
            try {
                val runtime = Runtime.getRuntime()
                val memoryMb = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / 1_048_576L
                val snapshot =
                    "session=$sessionId\n" +
                        "wall_time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n" +
                        "uptime_ms=$now\n" +
                        "available_heap_mb=$memoryMb\n" +
                        "state=$detail\n"
                val temporary = app.filesDir.resolve("$HEARTBEAT.tmp")
                val target = app.filesDir.resolve(HEARTBEAT)
                temporary.writeText(snapshot)
                if (!temporary.renameTo(target)) {
                    target.writeText(snapshot)
                    temporary.delete()
                }
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("last_uptime_ms", now)
                    .commit()
            } catch (_: Throwable) {
            }
        }
    }

    fun recordCrash(thread: Thread, error: Throwable) {
        synchronized(lock) {
            val app = appContext ?: return
            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            eventLocked(app, "UNCAUGHT_EXCEPTION", "thread=${thread.name}\n$stack")
            writeReportLocked(app, "UNCAUGHT_EXCEPTION", stack)
        }
    }

    fun hasPendingReport(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("prompt_pending", false) && reportFile(context).exists()

    fun reportText(context: Context): String = try {
        reportFile(context).readText()
    } catch (_: Throwable) {
        "Não foi possível ler o relatório automático."
    }

    fun suggestedFileName(): String =
        "C3-Media-falha-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())}.txt"

    fun markReportSaved(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("prompt_pending", false)
            .commit()
    }

    private fun eventLocked(app: Context, area: String, detail: String) {
        try {
            rotateIfNeeded(app)
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val safeArea = area.replace('\n', ' ').take(48)
            val safeDetail = detail.replace("\u0000", "").take(12_000)
            app.filesDir.resolve(CURRENT_LOG).appendText(
                "$time | uptime=${SystemClock.elapsedRealtime()} | ${Thread.currentThread().name} | $safeArea | $safeDetail\n",
            )
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_uptime_ms", SystemClock.elapsedRealtime())
                .commit()
        } catch (_: Throwable) {
            // Diagnostics must never become a reason for the receiver to stop.
        }
    }

    private fun rotateIfNeeded(app: Context) {
        val current = app.filesDir.resolve(CURRENT_LOG)
        if (!current.exists() || current.length() < MAX_LOG_BYTES) return
        val previous = app.filesDir.resolve(PREVIOUS_LOG)
        previous.delete()
        current.renameTo(previous)
        current.writeText("C3 Media diagnostic session continued\nsession=$sessionId\n")
    }

    private fun writeReportLocked(app: Context, reason: String, stack: String?) {
        try {
            val report = buildString {
                append("C3 Media — relatório automático de encerramento\n")
                append("reason=$reason\n")
                append("detected_at=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                append("version=${versionName(app)}\n")
                append("device=${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}\n\n")
                app.filesDir.resolve(HEARTBEAT).takeIf(File::exists)?.let {
                    append("--- last heartbeat ---\n")
                    append(it.readText().takeLast(8_000))
                    append('\n')
                }
                app.filesDir.resolve(PREVIOUS_LOG).takeIf(File::exists)?.let {
                    append("--- earlier events ---\n")
                    append(it.readText().takeLast(160_000))
                    append('\n')
                }
                app.filesDir.resolve(CURRENT_LOG).takeIf(File::exists)?.let {
                    append("--- final events before exit ---\n")
                    append(it.readText().takeLast(260_000))
                    append('\n')
                }
                if (!stack.isNullOrBlank()) {
                    append("--- exception ---\n")
                    append(stack.take(80_000))
                }
            }
            val target = reportFile(app)
            val temporary = app.filesDir.resolve("${C3MediaApplication.CRASH_FILE}.tmp")
            temporary.writeText(report)
            if (!temporary.renameTo(target)) {
                target.writeText(report)
                temporary.delete()
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("report_ready", true)
                .putBoolean("prompt_pending", true)
                .commit()
        } catch (_: Throwable) {
        }
    }

    private fun reportFile(context: Context): File =
        context.filesDir.resolve(C3MediaApplication.CRASH_FILE)

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }
}
