package com.claudeborne.camera.look

import com.claudeborne.camera.capture.PhotoRules
import com.claudeborne.camera.gl.ExifTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class ExifTurnTest {
    @Test fun everyTagTheCameraWritesGivesItsTurn() {
        // PhotoRules writes tag 1/6/3/8 for a turn of 0/90/180/270 and 2/5/4/7 for the mirrored selfie of the same turn
        for (turn in listOf(0, 90, 180, 270)) {
            assertEquals(turn to false, ExifTurn.of(PhotoRules.exifOrientation(turn, mirrored = false)))
            assertEquals(turn to true, ExifTurn.of(PhotoRules.exifOrientation(turn, mirrored = true)))
        }
        assertEquals(0 to false, ExifTurn.of(0))
    }
}
