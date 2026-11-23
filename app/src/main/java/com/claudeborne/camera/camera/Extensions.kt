package com.claudeborne.camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager

/** Questions about Camera Extensions that the settings screen can ask without an open camera. */
object Extensions {
    /** True when any rear or front camera offers the Night extension. */
    fun nightAvailable(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.any { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) != null &&
                manager.getCameraExtensionCharacteristics(id).supportedExtensions.contains(CameraExtensionCharacteristics.EXTENSION_NIGHT)
        }
    } catch (_: Exception) {
        false
    }
}
