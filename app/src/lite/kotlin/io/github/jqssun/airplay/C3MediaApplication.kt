package io.github.jqssun.airplay

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
class C3MediaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashDiagnostics.recordCrash(thread, error)
            scheduleDashboardRestart(this, 1_500L, true)
            previous?.uncaughtException(thread, error)
        }
        CrashDiagnostics.initialize(this)
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
