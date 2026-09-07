package com.vipjam.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelBusTest {
    @Test
    fun `silence maps to floor and zero fraction`() {
        assertEquals(-60f, LevelBus.rmsToDb(0f), 0.001f)
        assertEquals(0f, LevelBus.levelFraction(0f), 0.001f)
    }

    @Test
    fun `full scale maps to zero db and full fraction`() {
        assertEquals(0f, LevelBus.rmsToDb(1f), 0.001f)
        assertEquals(1f, LevelBus.levelFraction(1f), 0.001f)
    }

    @Test
    fun `stale levels are not live`() {
        val level = MeasuredLevel(0.1f, 0.2f, 1000L, "test tone")
        assertTrue(LevelBus.isLive(level, 1000L + LevelBus.STALE_TIMEOUT_MS))
        assertFalse(LevelBus.isLive(level, 1000L + LevelBus.STALE_TIMEOUT_MS + 1))
        assertFalse(LevelBus.isLive(null, 1000L))
    }

    @Test
    fun `non-finite levels are never live`() {
        assertFalse(LevelBus.isLive(MeasuredLevel(Float.NaN, 0.1f, 0L, "t"), 0L))
    }
}
