package com.claudeborne.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeOrderTest {
    @Test fun everyModeIsInExactlyOneGroup() {
        Mode.entries.forEach { m -> assertEquals("$m", 1, Group.entries.count { m in it.members }) }
        Group.entries.forEach { g -> assertEquals(g.members.size, g.variants.size) }
    }

    @Test fun stockStripIsMasterVideoPhotoPortraitScan() {
        assertEquals(listOf(Group.MASTER, Group.VIDEO, Group.PHOTO, Group.PORTRAIT, Group.SCAN), ModeOrder.strip(ModeOrder.parse("")))
        assertEquals(listOf(Group.CREATIVE, Group.DUAL), ModeOrder.more(ModeOrder.parse("")))
    }

    @Test fun mergedModesLiveInTheirGroups() {
        assertEquals(Group.VIDEO, Mode.SLOMO.group)
        assertEquals(Group.VIDEO, Mode.PROVIDEO.group)
        assertEquals(Group.PHOTO, Mode.NIGHT.group)
        assertEquals(Group.DUAL, Mode.QUAD.group)
        assertEquals("DOCUMENT", Group.SCAN.variantOf(Mode.SCAN))
    }

    @Test fun roundTripAndUnknownNames() {
        val order = ModeOrder.parse("DUAL,GONE,PHOTO,PHOTO")
        assertEquals(listOf(Group.DUAL, Group.PHOTO), order.take(2))
        assertEquals(Group.entries.size, order.size)
        assertEquals(order, ModeOrder.parse(ModeOrder.encode(order)))
    }

    @Test fun moveSwapsWithNeighbourThePhoneCanDo() {
        val order = Group.entries.toList()
        val shown = order.filter { it != Group.PORTRAIT } // the phone cannot do Portrait: SCAN is the neighbour after PHOTO
        val moved = ModeOrder.move(order, shown, Group.SCAN, -1)
        assertEquals(order.indexOf(Group.SCAN), moved.indexOf(Group.PHOTO))
        assertEquals(order.indexOf(Group.PHOTO), moved.indexOf(Group.SCAN))
    }

    @Test fun moveAtTheEndDoesNothing() {
        val order = Group.entries.toList()
        assertEquals(order, ModeOrder.move(order, order, order.first(), -1))
        assertEquals(order, ModeOrder.move(order, order, order.last(), 1))
    }

    @Test fun stepStaysInsideTheShownGroups() {
        val shown = listOf(Group.PHOTO, Group.VIDEO)
        assertEquals(Group.VIDEO, Group.PHOTO.step(1, shown))
        assertTrue(Group.VIDEO.step(1, shown) == null)
    }
}
