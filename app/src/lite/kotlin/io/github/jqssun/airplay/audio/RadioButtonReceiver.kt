package io.github.jqssun.airplay.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/** Legacy Android-5/AVRCP entry point used by older factory head units. */
class RadioButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            current?.dispatchMediaKey(event.keyCode)
        }
    }

    companion object {
        @Volatile private var current: RadioMediaSession? = null

        fun attach(session: RadioMediaSession) {
            current = session
        }

        fun detach(session: RadioMediaSession) {
            if (current === session) current = null
        }
    }
}
