package com.claudeborne.camera.video

import com.claudeborne.camera.gl.PreviewMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class VideoLogicTest {
    private val sizes = setOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
    private val aeRanges = listOf(10 to 30, 30 to 30, 10 to 60, 60 to 60)
    private val hs = mapOf(
        (3840 to 2160) to setOf(120), (1920 to 1080) to setOf(120, 240), (1280 to 720) to setOf(120, 240),
    )
    private val caps = VideoCaps(sizes, aeRanges, hs, setOf(0, 1, 2), setOf(2L, 4L))

    @Test fun mainLensOffersEveryResolutionAt30And60PlusHighSpeed() {
        val labels = VideoQuality.options(caps, front = false, mainLens = true).map { it.label }
        assertEquals(
            listOf("720p · 30", "720p · 60", "1080p · 30", "1080p · 60", "2K · 30", "2K · 60", "4K · 30", "4K · 60", "720p · 120", "1080p · 120", "4K · 120"),
            labels,
        )
    }

    @Test fun otherLensesHaveNo4K120() {
        val labels = VideoQuality.options(caps, front = false, mainLens = false).map { it.label }
        assertFalse("4K · 120" in labels)
        assertTrue("1080p · 120" in labels)
    }

    @Test fun frontCameraHasNoHighSpeedAndOnlyItsSizes() {
        val front = VideoCaps(setOf(1280 to 720, 1920 to 1080, 2560 to 1440), aeRanges, emptyMap(), setOf(0, 1), emptySet())
        val labels = VideoQuality.options(front, front = true, mainLens = true).map { it.label }
        assertEquals(listOf("720p · 30", "720p · 60", "1080p · 30", "1080p · 60", "2K · 30", "2K · 60"), labels)
    }

    @Test fun encoderLimitsRemoveChoices() {
        val labels = VideoQuality.options(caps, front = false, mainLens = true) { res, fps -> !(res == Res.R4K && fps > 30) }.map { it.label }
        assertFalse("4K · 60" in labels)
        assertFalse("4K · 120" in labels)
        assertTrue("4K · 30" in labels)
    }

    @Test fun cameraWithoutA60RangeHasNo60() {
        val slow = VideoCaps(sizes, listOf(30 to 30), emptyMap(), emptySet(), emptySet())
        assertTrue(VideoQuality.options(slow, front = false, mainLens = true).all { it.fps == 30 })
    }

    @Test fun sloMoHas4KOnlyAt120OnTheMainLens() {
        assertEquals(
            listOf("4K · 120", "1080p · 120", "1080p · 240", "720p · 120", "720p · 240"),
            VideoQuality.sloMoOptions(caps, mainLens = true).map { it.label },
        )
        assertEquals(listOf("1080p · 120", "1080p · 240", "720p · 120", "720p · 240"), VideoQuality.sloMoOptions(caps, mainLens = false).map { it.label })
    }

    @Test fun timeLapseUsesThe30fpsChoices() {
        val all = VideoQuality.options(caps, front = false, mainLens = true)
        assertTrue(VideoQuality.timeLapseOptions(all).all { it.fps == 30 })
    }

    @Test fun resolveKeepsOrReplacesAnUnavailableChoice() {
        val all = VideoQuality.options(caps, front = false, mainLens = false)
        assertEquals(VideoOption(Res.R4K, 60), VideoQuality.resolve(VideoOption(Res.R4K, 60), all))
        assertEquals(VideoOption(Res.R4K, 60), VideoQuality.resolve(VideoOption(Res.R4K, 120), all)) // same size, nearest rate
        assertEquals(VideoOption(Res.R1080, 30), VideoQuality.resolve(VideoOption(Res.R1080, 30), all))
        assertNull(VideoQuality.resolve(VideoOption(Res.R4K, 30), emptyList()))
    }

    @Test fun adaptiveFrameRateRange() {
        assertEquals(10 to 30, VideoQuality.aeRange(caps, 30, adaptive = true))
        assertEquals(30 to 30, VideoQuality.aeRange(caps, 30, adaptive = false))
        assertEquals(10 to 60, VideoQuality.aeRange(caps, 60, adaptive = true))
        assertTrue(VideoQuality.adaptive(1, 30))
        assertFalse(VideoQuality.adaptive(1, 60))
        assertTrue(VideoQuality.adaptive(2, 60))
        assertFalse(VideoQuality.adaptive(0, 30))
    }

    @Test fun lensZones() {
        assertEquals(0, LensZone.of(0.67f))
        assertEquals(1, LensZone.of(1f))
        assertEquals(1, LensZone.of(3.4f))
        assertEquals(2, LensZone.of(3.5f))
        assertTrue(LensZone.isMain(2f))
        val r = LensZone.range(2f, 0.67f, 20f)
        assertTrue(1f in r && 3.4f in r && 3.5f !in r && 0.9f !in r)
    }

    @Test fun fileNamesFollowThePhotoStyle() {
        // 2026-11-23 15:42:10.123 UTC
        val t = 1795448530123L
        assertEquals("VID_20261123_154210_123.mp4", VideoRules.fileName(t, TimeZone.getTimeZone("UTC")))
    }

    @Test fun bitrateGrowsWithQualityAndNeverPassesTheCap() {
        val std = VideoRules.bitrate(3840, 2160, 30, hevc = true, high = false, tenBit = false)
        val high = VideoRules.bitrate(3840, 2160, 30, hevc = true, high = true, tenBit = false)
        assertTrue(high > std)
        assertTrue(VideoRules.bitrate(3840, 2160, 30, hevc = false, high = false, tenBit = false) > std) // AVC needs more
        assertTrue(VideoRules.bitrate(3840, 2160, 30, hevc = true, high = false, tenBit = true) > std)
        assertTrue(VideoRules.bitrate(3840, 2160, 60, hevc = true, high = true, tenBit = false) > high)
        assertEquals(50_000_000, VideoRules.bitrate(3840, 2160, 120, hevc = false, high = true, tenBit = true, cap = 50_000_000))
        assertTrue(VideoRules.bitrate(1280, 720, 30, hevc = true, high = false, tenBit = false) >= 2_000_000)
    }

    @Test fun frameSizeAndTurn() {
        assertEquals(1080 to 1920, VideoRules.frameSize(Res.R1080, portrait = true))
        assertEquals(1920 to 1080, VideoRules.frameSize(Res.R1080, portrait = false))
        assertTrue(VideoRules.portrait(0) && VideoRules.portrait(180) && !VideoRules.portrait(90))
        assertEquals(0, VideoRules.turn(0, front = false))
        assertEquals(270, VideoRules.turn(90, front = false))
        assertEquals(90, VideoRules.turn(90, front = true))
    }

    @Test fun timerText() {
        assertEquals("0:00", VideoRules.timer(0))
        assertEquals("0:07", VideoRules.timer(7_400))
        assertEquals("1:02", VideoRules.timer(62_000))
        assertEquals("1:02:09", VideoRules.timer(3_729_000))
    }

    @Test fun sloMoStretchesTime() {
        assertEquals(4, VideoRules.slowFactor(120))
        assertEquals(8, VideoRules.slowFactor(240))
        assertEquals(4_000_000_000L, VideoRules.slowMoPts(1_000_000_000L, 120))
    }

    @Test fun timeLapseKeepsOneFrameEveryIntervalAndPlaysAt30() {
        assertEquals(1.0, TimeLapse.intervalSec(30), 1e-9)
        assertEquals(2.0, TimeLapse.intervalSec(60), 1e-9)
        // 30x: frame 0 at once, frame 1 after one second
        assertTrue(TimeLapse.keep(0, 0, 30))
        assertFalse(TimeLapse.keep(900_000_000L, 1, 30))
        assertTrue(TimeLapse.keep(1_000_000_000L, 1, 30))
        assertEquals(0L, TimeLapse.pts(0))
        assertEquals(1_000_000_000L, TimeLapse.pts(30))
        assertEquals(10.0, TimeLapse.clipSeconds(300, 30), 1e-9)
    }

    @Test fun turnedPictureReadsFromTheRightCorner() {
        // landscape output of a portrait scene held on its side: output top-right shows the upright picture's top-left
        val (u, v) = PreviewMath.map(1f, 1f, 90, false, 1920, 1440, 1920, 1440, turn = 90)
        assertEquals(0f, u, 0.001f)
        assertEquals(1f, v, 0.001f)
        val (u2, v2) = PreviewMath.map(0f, 0f, 90, false, 1920, 1440, 1920, 1440, turn = 90) // bottom-left output = bottom-right of the upright picture
        assertEquals(1f, u2, 0.001f)
        assertEquals(0f, v2, 0.001f)
        // 180 is a point reflection
        val (u3, v3) = PreviewMath.map(0.25f, 0.25f, 90, false, 1920, 1440, 1440, 1920, turn = 180)
        assertEquals(0.75f, u3, 0.001f)
        assertEquals(0.75f, v3, 0.001f)
        // no turn is the old behaviour
        assertEquals(PreviewMath.map(0.3f, 0.6f, 90, true, 1920, 1440, 1080, 1920), PreviewMath.map(0.3f, 0.6f, 90, true, 1920, 1440, 1080, 1920, turn = 0))
    }
}
