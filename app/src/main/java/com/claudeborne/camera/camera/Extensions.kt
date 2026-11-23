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

    /** True when the phone lists a rear and the front camera as a concurrent pair (Dual view). */
    fun dualViewAvailable(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.concurrentCameraIds.any { pair ->
            pair.any { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } &&
                pair.any { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        }
    } catch (_: Exception) {
        false
    }

    /** True when a rear camera zooms below 1x, i.e. has an ultra wide view whose edges stretch faces. */
    fun ultraWideAvailable(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.any { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                (c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f) < 0.95f
        }
    } catch (_: Exception) {
        false
    }
}
