package com.claudeborne.camera.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoTest {
    private fun jpeg(withExif: Boolean): ByteArray {
        val b = mutableListOf(0xFF, 0xD8)
        if (withExif) b += listOf(0xFF, 0xE1, 0x00, 0x08, 1, 2, 3, 4, 5, 6) // an EXIF segment: length 8 counts its own two bytes
        b += listOf(0xFF, 0xDB, 0x00, 0x04, 9, 9, 0xFF, 0xD9)
        return ByteArray(b.size) { b[it].toByte() }
    }

    private val mp4 = ByteArray(1234) { (it % 251).toByte() }

    @Test fun clipIsAppendedAfterThePicture() {
        val src = jpeg(false)
        val out = MotionPhoto.build(src, mp4, 1_500_000)
        assertArrayEquals(mp4, out.copyOfRange(out.size - mp4.size, out.size))
        assertEquals(0xFF.toByte(), out[0]); assertEquals(0xD8.toByte(), out[1])
    }

    @Test fun xmpSitsRightAfterSoiAndNamesTheClipLength() {
        val out = MotionPhoto.build(jpeg(false), mp4, 1_500_000)
        assertEquals(0xFF.toByte(), out[2]); assertEquals(0xE1.toByte(), out[3])
        val len = ((out[4].toInt() and 0xFF) shl 8) or (out[5].toInt() and 0xFF)
        val text = String(out, 6, len - 2, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("http://ns.adobe.com/xap/1.0/"))
        assertTrue(text.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(text.contains("Item:Length=\"1234\""))
        assertTrue(text.contains("GCamera:MicroVideoOffset=\"1234\""))
        assertTrue(text.contains("GCamera:MotionPhotoPresentationTimestampUs=\"1500000\""))
    }

    @Test fun leadingExifStaysFirst() {
        val out = MotionPhoto.build(jpeg(true), mp4, 0)
        assertEquals(0xE1.toByte(), out[3]); assertEquals(8, out[5].toInt()) // the EXIF segment, unchanged
        assertEquals(0xE1.toByte(), out[2 + 2 + 8 + 1]) // then our XMP segment
    }

    @Test fun nonJpegIsRefused() {
        var failed = false
        try { MotionPhoto.build(byteArrayOf(1, 2, 3, 4, 5), mp4, 0) } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
