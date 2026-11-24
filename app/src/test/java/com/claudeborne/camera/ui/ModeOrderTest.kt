package com.claudeborne.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeOrderTest {
    @Test fun emptyTextGivesDefaultOrder() = assertEquals(Mode.entries.toList(), ModeOrder.parse(""))

    @Test fun roundTrip() {
        val order = ModeOrder.parse("TEXT,PHOTO")
        assertEquals(listOf(Mode.TEXT, Mode.PHOTO), order.take(2))
        assertEquals(order, ModeOrder.parse(ModeOrder.encode(order)))
    }

    @Test fun unknownNamesDroppedAndMissingAppended() {
        val order = ModeOrder.parse("GONE,PHOTO,PHOTO")
        assertEquals(Mode.PHOTO, order.first())
        assertEquals(Mode.entries.size, order.size)
    }

    @Test fun moveSwapsWithNeighbourThePhoneCanDo() {
        val order = Mode.entries.toList()
        val shown = order.filter { it != Mode.PORTRAIT } // the phone cannot do Portrait: TEXT is the neighbour after PHOTO
        val moved = ModeOrder.move(order, shown, Mode.TEXT, -1)
        assertEquals(order.indexOf(Mode.TEXT), moved.indexOf(Mode.PHOTO))
        assertEquals(order.indexOf(Mode.PHOTO), moved.indexOf(Mode.TEXT))
    }

    @Test fun moveAtTheEndDoesNothing() {
        val order = Mode.entries.toList()
        assertEquals(order, ModeOrder.move(order, order, order.first(), -1))
        assertEquals(order, ModeOrder.move(order, order, order.last(), 1))
    }

    @Test fun stripAndMoreSplitAfterFive() {
        val shown = Mode.entries.toList()
        assertEquals(5, ModeOrder.strip(shown).size)
        assertEquals(shown.size - 5, ModeOrder.more(shown).size)
        assertEquals(emptyList<Mode>(), ModeOrder.more(shown.take(4)))
    }
}
