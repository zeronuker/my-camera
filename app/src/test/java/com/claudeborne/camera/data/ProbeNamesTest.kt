package com.claudeborne.camera.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeNamesTest {
    @Test fun capabilitiesAreNamed() {
        assertEquals("MANUAL_SENSOR", ProbeNames.capability(1))
        assertEquals("LOGICAL_MULTI_CAMERA", ProbeNames.capability(11))
        assertEquals("DYNAMIC_RANGE_TEN_BIT", ProbeNames.capability(18))
        assertEquals("UNKNOWN_99", ProbeNames.capability(99))
    }

    @Test fun otherNames() {
        assertEquals("BACK", ProbeNames.facing(1))
        assertEquals("FULL", ProbeNames.hardwareLevel(1))
        assertEquals("NIGHT", ProbeNames.extension(4))
        assertEquals("HLG10", ProbeNames.dynamicRange(2))
        assertEquals("ERROR_MAX_CAMERAS_IN_USE", ProbeNames.deviceError(2))
    }

    @Test fun crashLogKeepsNewest() {
        val names = (1..12).map { "crash-%02d.txt".format(it) }
        assertEquals(listOf("crash-02.txt", "crash-01.txt"), CrashLog.prune(names, keep = 10))
        assertEquals(emptyList<String>(), CrashLog.prune(names.take(3), keep = 10))
    }
}
