package com.claudeborne.camera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreserveTest {
    @Test fun elevenSwitches() = assertEquals(11, Keep.entries.size)

    @Test fun allOnResetsNothing() = assertEquals(emptySet<String>(), Preserve.keysToReset { true })

    @Test fun offSwitchResetsItsKeysOnly() {
        val keys = Preserve.keysToReset { it != Keep.MACRO }
        assertEquals(setOf("autoMacro"), keys)
    }

    @Test fun stockDefaultsResetModeStylesAndTheRest() {
        val keys = Preserve.keysToReset { it.default }
        assertTrue("lastMode" in keys && "filter" in keys && "faceMetering" in keys && "masterFormat" in keys)
        assertTrue("retouch" !in keys && "aiZoom" !in keys && "proOn" !in keys)
    }

    @Test fun everyKeyIsARealSetting() {
        val real = Settings.allKeys()
        Keep.entries.flatMap { it.resets }.forEach { assertTrue("$it is not a setting", it in real) }
    }
}
