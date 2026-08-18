package io.github.jqssun.airplay

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class C3MediaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordCrash(thread, error)
            scheduleDashboardRestart(this, 1_500L, true)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun recordCrash(thread: Thread, error: Throwable) {
        try {
            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            filesDir.resolve(CRASH_FILE).writeText("$date | ${thread.name}\n$stack")
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val CRASH_FILE = "last-crash.txt"
        const val EXTRA_WAKE_ANIMATION = "wake_animation"

        fun scheduleDashboardRestart(context: Context, delayMs: Long, animate: Boolean) {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_WAKE_ANIMATION, animate)
            }
            val pending = PendingIntent.getActivity(
                context,
                3031,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pending,
            )
        }
    }
}
