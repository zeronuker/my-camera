package com.claudeborne.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class PhotoRulesTest {
    private val all = StillFormat.entries.toSet()

    @Test fun flashCycles() {
        assertEquals(Flash.AUTO, Flash.OFF.next(rear = true))
        assertEquals(Flash.OFF, Flash.TORCH.next(rear = true))
        assertEquals(Flash.ON, Flash.OFF.next(rear = false))
        assertEquals(Flash.OFF, Flash.ON.next(rear = false))
        assertEquals("Torch", Flash.TORCH.label)
    }

    @Test fun formatChoice() {
        assertEquals(StillFormat.JPEG_R, PhotoRules.chooseFormat(false, true, false, true, all))
        assertEquals(StillFormat.HEIC, PhotoRules.chooseFormat(false, true, true, true, all))
        assertEquals(StillFormat.JPEG, PhotoRules.chooseFormat(true, true, true, true, all)) // mirrored selfie
        assertEquals(StillFormat.JPEG_R, PhotoRules.chooseFormat(true, false, false, true, all))
        assertEquals(StillFormat.JPEG, PhotoRules.chooseFormat(false, false, false, true, setOf(StillFormat.JPEG)))
    }

    @Test fun orientationFollowsPhoneTurn() {
        assertEquals(90, PhotoRules.jpegOrientation(90, 0, front = false)) // rear, held upright
        assertEquals(0, PhotoRules.jpegOrientation(90, 90, front = false)) // rear, turned to landscape
        assertEquals(270, PhotoRules.jpegOrientation(270, 0, front = true))
        assertEquals(0, PhotoRules.jpegOrientation(270, 90, front = true))
        assertEquals(0, PhotoRules.deviceDegrees(20))
        assertEquals(270, PhotoRules.deviceDegrees(50)) // the listener counts clockwise, the camera formula counterclockwise
        assertEquals(0, PhotoRules.deviceDegrees(340))
        assertEquals(90, PhotoRules.deviceDegrees(265))
        assertEquals(180, PhotoRules.jpegOrientation(90, PhotoRules.deviceDegrees(90), front = false))
    }

    @Test fun exifTags() {
        assertEquals(6, PhotoRules.exifOrientation(90, mirrored = false))
        assertEquals(1, PhotoRules.exifOrientation(0, mirrored = false))
        assertEquals(5, PhotoRules.exifOrientation(90, mirrored = true))
        assertEquals(7, PhotoRules.exifOrientation(270, mirrored = true))
    }

    @Test fun fileNameLikeStock() {
        val utc = TimeZone.getTimeZone("UTC")
        val time = java.util.GregorianCalendar(utc).apply { clear(); set(2026, java.util.Calendar.NOVEMBER, 23, 7, 42, 10); set(java.util.Calendar.MILLISECOND, 123) }.timeInMillis
        assertEquals("IMG_20261123_074210_123.jpg", PhotoRules.fileName(time, "jpg", utc))
        assertEquals("IMG_20261123_074210_123_REAR.jpg", PhotoRules.fileName(time, "jpg", utc, "_REAR")) // Dual Photo: one file per camera
    }

    @Test fun stillSizeMatchesScreenShape() {
        val sizes = listOf(4096 to 3072, 3840 to 2160, 3136 to 1440, 2772 to 1272, 2580 to 1080, 1920 to 1080)
        assertEquals(3136 to 1440, PhotoRules.chooseStillSize(sizes, 2400f / 1080f))
        assertEquals(4096 to 3072, PhotoRules.chooseStillSize(sizes, 4f / 3f))
        assertEquals(3840 to 2160, PhotoRules.chooseStillSize(sizes, 16f / 9f))
    }
}
