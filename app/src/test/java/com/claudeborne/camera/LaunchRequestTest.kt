package com.claudeborne.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRequestTest {
    @Test fun cameraIntentsMapToTheirKind() {
        assertEquals(LaunchKind.STILL, LaunchRequest.of("android.media.action.STILL_IMAGE_CAMERA", null).kind)
        assertEquals(LaunchKind.VIDEO, LaunchRequest.of("android.media.action.VIDEO_CAMERA", null).kind)
        assertEquals(LaunchKind.IMAGE_CAPTURE, LaunchRequest.of("android.media.action.IMAGE_CAPTURE", null).kind)
        assertEquals(LaunchKind.VIDEO_CAPTURE, LaunchRequest.of("android.media.action.VIDEO_CAPTURE", null).kind)
    }

    @Test fun secureLaunchIsOnlyTheLockScreenIntent() {
        assertTrue(LaunchRequest.of("android.media.action.STILL_IMAGE_CAMERA_SECURE", null).secure)
        assertFalse(LaunchRequest.of("android.media.action.STILL_IMAGE_CAMERA", null).secure)
        assertFalse(LaunchRequest.of(null, "video").secure)
    }

    @Test fun shortcutsAndPlainLaunch() {
        val selfie = LaunchRequest.of("android.intent.action.MAIN", "selfie")
        assertTrue(selfie.wantsFront && selfie.wantsPhoto)
        assertTrue(LaunchRequest.of(null, "video").wantsVideo)
        val plain = LaunchRequest.of("android.intent.action.MAIN", null)
        assertEquals(LaunchKind.NORMAL, plain.kind)
        assertFalse(plain.wantsVideo || plain.wantsPhoto || plain.secure || plain.returnsResult)
    }

    @Test fun captureRequestsWaitForTheirResult() {
        assertTrue(LaunchRequest.of("android.media.action.IMAGE_CAPTURE", null).returnsResult)
        assertTrue(LaunchRequest.of("android.media.action.VIDEO_CAPTURE", null).returnsResult)
        assertFalse(LaunchRequest.of("android.media.action.VIDEO_CAMERA", null).returnsResult)
    }
}
