package com.claudeborne.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class WatermarkTest {
    @Test fun modelNames() {
        assertEquals("OnePlus 15", Watermark.modelName("OnePlus", "CPH2747"))
        assertEquals("Google Pixel 9", Watermark.modelName("google", "Pixel 9"))
        assertEquals("Samsung SM-S928B", Watermark.modelName("samsung", "SM-S928B"))
        assertEquals("OnePlus 13", Watermark.modelName("OnePlus", "OnePlus 13"))
    }

    @Test fun appliesToPhotosAndVideos() {
        assertTrue(Watermark.appliesTo(2, true) && Watermark.appliesTo(2, false))
        assertTrue(Watermark.appliesTo(0, false) && !Watermark.appliesTo(0, true))
        assertTrue(Watermark.appliesTo(1, true) && !Watermark.appliesTo(1, false))
    }

    @Test fun textJoinsOnlyWhatIsOn() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("Shot on OnePlus 15", Watermark.text(true, "OnePlus 15", null, null, "", utc))
        assertEquals("", Watermark.text(false, "x", null, null, "  ", utc))
        assertEquals(
            "Shot on OnePlus 15  ·  3.139° N, 101.687° E  ·  1970-01-01 00:00  ·  hello",
            Watermark.text(true, "OnePlus 15", Watermark.coordinates(3.139, 101.687), 0L, " hello ", utc),
        )
    }

    @Test fun coordinatesNameTheHemisphere() {
        assertEquals("33.869° S, 151.209° E", Watermark.coordinates(-33.8688, 151.2093))
        assertEquals("40.713° N, 74.006° W", Watermark.coordinates(40.7128, -74.006))
    }

    @Test fun textSizeGrowsWithTheChoice() {
        assertTrue(Watermark.textSize(0, 1000) < Watermark.textSize(1, 1000))
        assertTrue(Watermark.textSize(1, 1000) < Watermark.textSize(2, 1000))
    }

    @Test fun placesStayInsideThePicture() {
        for (position in 0..3) {
            val (x, y) = Watermark.place(position, 1000, 800, 300f, 30f)
            assertTrue("pos $position x=$x", x >= 0f && x + 300f <= 1000f)
            assertTrue("pos $position y=$y", y in 0f..800f)
        }
        assertFalse(Watermark.place(3, 1000, 800, 300f, 30f).second > Watermark.place(0, 1000, 800, 300f, 30f).second)
        assertTrue(Watermark.place(1, 1000, 800, 300f, 30f).first < Watermark.place(2, 1000, 800, 300f, 30f).first)
    }
}
