package com.screenask.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.RemoteInput

class ReplyReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.screenask.app.NOTIFICATION_REPLY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationHelper.REPLY_KEY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return
        AskDispatcher.followUp(context, text)
    }
}

class ControlReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COPY = "com.screenask.app.action.COPY"
        const val ACTION_STOP_SESSION = "com.screenask.app.action.STOP_SESSION"
        const val ACTION_STOP_OVERLAY = "com.screenask.app.action.STOP_OVERLAY"
        const val EXTRA_TEXT = "extra_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COPY -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotEmpty()) AskEngine.copyToClipboard(context, text)
            }
            ACTION_STOP_SESSION -> {
                ScreenCaptureService.instance?.stopSession()
            }
            ACTION_STOP_OVERLAY -> {
                OverlayService.setEnabled(context, false)
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = Prefs.load(context)
        if (!config.autoStartOnBoot) return
        NotificationHelper.createChannels(context)
        NotificationHelper.showLauncher(context)
    }
}
