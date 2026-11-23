package com.claudeborne.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProRulesTest {
    @Test fun isoListKeepsTheCameraLimitsAsEnds() {
        val iso = ProRules.isoStops(50, 6400)
        assertEquals(50, iso.first())
        assertEquals(6400, iso.last())
        assertTrue(100 in iso && 800 in iso && 12800 !in iso)
        assertEquals(iso.sorted(), iso)
        assertTrue(ProRules.isoStops(0, 0).isEmpty())
    }

    @Test fun isoListHandlesOddLimits() {
        val iso = ProRules.isoStops(55, 3000)
        assertEquals(55, iso.first())
        assertEquals(3000, iso.last())
        assertEquals(iso.distinct(), iso)
    }

    @Test fun shutterListIsInsideTheRangeAndSorted() {
        val s = ProRules.shutterStops(100_000L, 1_000_000_000L)
        assertTrue(s.all { it in 100_000L..1_000_000_000L })
        assertEquals(s.sorted(), s)
        assertTrue(1_000_000_000L / 250 in s)
    }

    @Test fun shutterLabels() {
        assertEquals("1/250", ProRules.shutterLabel(4_000_000))
        assertEquals("1/2", ProRules.shutterLabel(500_000_000))
        assertEquals("1\"", ProRules.shutterLabel(1_000_000_000))
        assertEquals("2.5\"", ProRules.shutterLabel(2_500_000_000))
        assertEquals("30\"", ProRules.shutterLabel(30_000_000_000))
    }

    @Test fun shutterAngle() {
        assertEquals(180, ProRules.shutterAngle(1_000_000_000L / 60, 30))
        assertEquals(360, ProRules.shutterAngle(1_000_000_000L / 30, 30))
        assertEquals(90, ProRules.shutterAngle(1_000_000_000L / 120, 30))
    }

    @Test fun nearestWorksInStops() {
        val list = listOf(100, 200, 400, 800)
        assertEquals(1, ProRules.nearest(list, 250))
        assertEquals(2, ProRules.nearest(list, 330))
        assertEquals(0, ProRules.nearest(list, 1))
    }

    @Test fun seedingTakesTheLiveValuesOnce() {
        val caps = ProCaps(isoMin = 50, isoMax = 6400, exposureMinNs = 100_000, exposureMaxNs = 1_000_000_000)
        val seeded = ProRules.seedExposure(Pro(), caps, 410, 9_000_000)
        assertTrue(seeded.manualExposure)
        assertEquals(400, seeded.iso)
        assertEquals(1_000_000_000L / 100, seeded.exposureNs)
        // already manual: nothing changes
        assertEquals(seeded, ProRules.seedExposure(seeded, caps, 50, 100_000))
        // a camera without manual exposure is left alone
        assertEquals(Pro(), ProRules.seedExposure(Pro(), ProCaps(), 400, 1_000_000))
    }

    @Test fun usableExposure() {
        assertEquals(1_000_000_000L / 30, ProRules.usableExposure(500_000_000, 30, false))
        assertEquals(ProRules.PREVIEW_MAX_NS, ProRules.usableExposure(5_000_000_000, null, false))
        assertEquals(5_000_000_000, ProRules.usableExposure(5_000_000_000, null, true))
        assertEquals(33_333_333L, ProRules.frameDuration(1_000_000L))
        assertEquals(2_000_000_000L, ProRules.frameDuration(2_000_000_000L))
    }

    @Test fun kelvinGainsGoTheRightWay() {
        // a sensor that reads XYZ straight as RGB, calibrated for a warm and a daylight lamp
        val identity = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val cal = ProRules.ColorCal(identity, identity, 17, 21)
        val warm = ProRules.kelvinGains(3000, cal) // orange light: take red away, add blue
        val cool = ProRules.kelvinGains(8000, cal)
        assertTrue(warm.second > cool.second)
        assertTrue(warm.first < cool.first)
        val day = ProRules.kelvinGains(6500, cal)
        assertEquals(1f, day.first, 0.25f)
        assertEquals(1f, day.second, 0.25f)
    }

    @Test fun illuminantCodes() {
        assertEquals(6504, ProRules.illuminantKelvin(21))
        assertEquals(2856, ProRules.illuminantKelvin(17))
    }

    @Test fun whiteBalanceChoicesOnlyOfferWhatTheCameraHas() {
        val all = ProRules.wbChoices(setOf(1, 2, 3, 5, 6), kelvin = true)
        assertEquals(Wb.Auto, all.first())
        assertTrue(all.any { it is Wb.Kelvin })
        val few = ProRules.wbChoices(setOf(1, 5), kelvin = false)
        assertEquals(listOf<Wb>(Wb.Auto, Wb.Preset("Daylight", 5)), few)
    }

    @Test fun focusDial() {
        val steps = ProRules.focusSteps(10f)
        assertEquals(0f, steps.first(), 0f)
        assertEquals(10f, steps.last(), 0.001f)
        assertEquals("INF", ProRules.focusLabel(0f))
        assertEquals("10 cm", ProRules.focusLabel(10f))
        assertEquals("1.0 m", ProRules.focusLabel(1f))
        assertTrue(ProRules.focusSteps(0f).isEmpty())
    }

    @Test fun largestFace() {
        val small = intArrayOf(0, 0, 10, 10)
        val big = intArrayOf(5, 5, 50, 60)
        assertEquals(big, ProRules.largestFace(listOf(small, big)))
        assertNull(ProRules.largestFace(emptyList()))
    }

    @Test fun masterFormats() {
        assertEquals(MasterFormat.JPG, MasterFormat.of(9))
        assertTrue(MasterFormat.RAW_JPG.wantsRaw && MasterFormat.RAW_JPG.wantsJpeg)
        assertTrue(!MasterFormat.RAW.wantsJpeg && !MasterFormat.JPG.wantsRaw)
    }
}
