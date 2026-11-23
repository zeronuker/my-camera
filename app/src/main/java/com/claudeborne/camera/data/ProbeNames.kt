package com.claudeborne.camera.data

/** Android reports camera facts as numbers; these turn them into the names the probe report shows. Pure, so it is unit tested. */
internal object ProbeNames {
    fun capability(c: Int) = when (c) {
        0 -> "BACKWARD_COMPATIBLE"; 1 -> "MANUAL_SENSOR"; 2 -> "MANUAL_POST_PROCESSING"; 3 -> "RAW"
        4 -> "PRIVATE_REPROCESSING"; 5 -> "READ_SENSOR_SETTINGS"; 6 -> "BURST_CAPTURE"; 7 -> "YUV_REPROCESSING"
        8 -> "DEPTH_OUTPUT"; 9 -> "CONSTRAINED_HIGH_SPEED_VIDEO"; 10 -> "MOTION_TRACKING"; 11 -> "LOGICAL_MULTI_CAMERA"
        12 -> "MONOCHROME"; 13 -> "SECURE_IMAGE_DATA"; 14 -> "SYSTEM_CAMERA"; 15 -> "OFFLINE_PROCESSING"
        16 -> "ULTRA_HIGH_RESOLUTION_SENSOR"; 17 -> "REMOSAIC_REPROCESSING"; 18 -> "DYNAMIC_RANGE_TEN_BIT"
        19 -> "STREAM_USE_CASE"; 20 -> "COLOR_SPACE_PROFILES"
        else -> "UNKNOWN_$c"
    }

    fun hardwareLevel(l: Int?) = when (l) { 0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL_3"; 4 -> "EXTERNAL"; null -> "NONE"; else -> "UNKNOWN_$l" }

    fun facing(f: Int?) = when (f) { 0 -> "FRONT"; 1 -> "BACK"; 2 -> "EXTERNAL"; null -> "NONE"; else -> "UNKNOWN_$f" }

    fun extension(e: Int) = when (e) { 0 -> "AUTOMATIC"; 1 -> "FACE_RETOUCH"; 2 -> "BOKEH"; 3 -> "HDR"; 4 -> "NIGHT"; else -> "UNKNOWN_$e" }

    fun dynamicRange(p: Long) = when (p) {
        1L -> "STANDARD"; 2L -> "HLG10"; 4L -> "HDR10"; 8L -> "HDR10_PLUS"
        16L -> "DOLBY_VISION_10B_HDR_REF"; 32L -> "DOLBY_VISION_10B_HDR_REF_PO"; 64L -> "DOLBY_VISION_10B_HDR_OEM"; 128L -> "DOLBY_VISION_10B_HDR_OEM_PO"
        256L -> "DOLBY_VISION_8B_HDR_REF"; 512L -> "DOLBY_VISION_8B_HDR_REF_PO"; 1024L -> "DOLBY_VISION_8B_HDR_OEM"; 2048L -> "DOLBY_VISION_8B_HDR_OEM_PO"
        else -> "UNKNOWN_$p"
    }

    fun deviceError(e: Int) = when (e) {
        1 -> "ERROR_CAMERA_IN_USE"; 2 -> "ERROR_MAX_CAMERAS_IN_USE"; 3 -> "ERROR_CAMERA_DISABLED"; 4 -> "ERROR_CAMERA_DEVICE"; 5 -> "ERROR_CAMERA_SERVICE"
        else -> "ERROR_$e"
    }
}
