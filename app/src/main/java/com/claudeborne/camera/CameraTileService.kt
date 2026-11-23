package com.claudeborne.camera

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Quick Settings tile: opens the camera (the lock-screen camera when the phone is locked) and closes the shade. */
class CameraTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        val locked = isLocked
        val action = if (locked) "android.media.action.STILL_IMAGE_CAMERA_SECURE" else "android.media.action.STILL_IMAGE_CAMERA"
        val intent = Intent(this, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(pending)
    }
}
