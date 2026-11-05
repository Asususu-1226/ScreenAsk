package com.screenask.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap opens the identification trampoline. */
class QuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "识屏问答"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, CaptureTriggerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this, 7001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            runCatching { startActivity(intent) }
        }
    }
}
