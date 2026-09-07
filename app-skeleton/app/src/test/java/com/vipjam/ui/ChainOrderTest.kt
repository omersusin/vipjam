package com.vipjam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainOrderTest {
    @Test
    fun `default pins limiter last`() {
        assertEquals(ChainOrder.LIMITER_GROUP, ChainOrder.DEFAULT_DISPLAY_ORDER.last())
        assertEquals(HYBRID_GROUPS.size, ChainOrder.DEFAULT_DISPLAY_ORDER.size)
    }

    @Test
    fun `sanitize drops unknown and forces limiter last`() {
        val order = ChainOrder.sanitize("bass;nope;masterLimiter;equalizer")
        assertTrue("nope" !in order)
        assertEquals(ChainOrder.LIMITER_GROUP, order.last())
        assertEquals(HYBRID_GROUPS.size, order.size)
    }

    @Test
    fun `sanitize null restores default`() {
        assertEquals(ChainOrder.DEFAULT_DISPLAY_ORDER, ChainOrder.sanitize(null))
    }

    @Test
    fun `limiter cannot move`() {
        val order = ChainOrder.DEFAULT_DISPLAY_ORDER
        assertEquals(order, ChainOrder.move(order, ChainOrder.LIMITER_GROUP, -1))
        assertEquals(order, ChainOrder.move(order, ChainOrder.LIMITER_GROUP, 1))
    }

    @Test
    fun `nothing moves past pinned limiter`() {
        val order = ChainOrder.DEFAULT_DISPLAY_ORDER
        val second = order[order.size - 2]
        val moved = ChainOrder.move(order, second, 5)
        assertEquals(ChainOrder.LIMITER_GROUP, moved.last())
        assertEquals(order.size, moved.size)
    }

    @Test
    fun `display sort keeps limiter last within section`() {
        val groups = listOf("fetCompressor", "masterLimiter", "dynamicSystem")
        val sorted = ChainOrder.sortForDisplay(groups, ChainOrder.DEFAULT_DISPLAY_ORDER)
        assertEquals(ChainOrder.LIMITER_GROUP, sorted.last())
    }
}
