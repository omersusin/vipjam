package com.vipjam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqCurveMathEdgeTest {
    @Test
    fun `freq clamps outside range`() {
        assertEquals(0f, EqCurveMath.freqToXNorm(1f), 0f)
        assertEquals(1f, EqCurveMath.freqToXNorm(100000f), 0f)
        assertEquals(0f, EqCurveMath.freqToXNorm(0f), 0f)
    }

    @Test
    fun `norm clamps outside range`() {
        assertEquals(EqCurveMath.MIN_FREQ_HZ, EqCurveMath.xNormToFreqHz(-1f), 0f)
        assertEquals(EqCurveMath.MAX_FREQ_HZ, EqCurveMath.xNormToFreqHz(2f), 0f)
        assertEquals(12f, EqCurveMath.yNormToDb(-1f), 0f)
        assertEquals(-12f, EqCurveMath.yNormToDb(2f), 0f)
    }

    @Test
    fun `db norm clamps outside range`() {
        assertEquals(0f, EqCurveMath.dbToYNorm(100f), 0f)
        assertEquals(1f, EqCurveMath.dbToYNorm(-100f), 0f)
    }

    @Test
    fun `px roundtrip through freq`() {
        val w = 800f
        val h = 400f
        for (f in EqCurveMath.BAND_FREQS_HZ) {
            val back = EqCurveMath.xToFreqHz(EqCurveMath.freqToX(f, w, 32f, 12f), w, 32f, 12f)
            assertEquals("freq $f", f, back, f * 1e-3f)
        }
        for (db in floatArrayOf(-12f, -3f, 0f, 7.5f, 12f)) {
            val back = EqCurveMath.yToDb(EqCurveMath.dbToY(db, h, 10f, 24f), h, 10f, 24f)
            assertEquals("db $db", db, back, 1e-3f)
        }
    }

    @Test
    fun `px clamps outside pads`() {
        assertEquals(EqCurveMath.MIN_FREQ_HZ, EqCurveMath.xToFreqHz(-50f, 800f, 32f, 12f), 1e-3f)
        assertEquals(EqCurveMath.MAX_FREQ_HZ, EqCurveMath.xToFreqHz(900f, 800f, 32f, 12f), 1e-3f)
        assertEquals(12f, EqCurveMath.yToDb(-50f, 400f, 10f, 24f), 1e-3f)
        assertEquals(-12f, EqCurveMath.yToDb(900f, 400f, 10f, 24f), 1e-3f)
    }

    @Test
    fun `freqToX endpoints hug pads`() {
        assertEquals(32f, EqCurveMath.freqToX(1f, 800f, 32f, 12f), 1e-3f)
        assertEquals(800f - 12f, EqCurveMath.freqToX(50000f, 800f, 32f, 12f), 1e-3f)
    }

    @Test
    fun `bandFreqHz out of range returns max`() {
        assertEquals(EqCurveMath.MAX_FREQ_HZ, EqCurveMath.bandFreqHz(-1), 0f)
        assertEquals(EqCurveMath.MAX_FREQ_HZ, EqCurveMath.bandFreqHz(10), 0f)
        assertEquals(EqCurveMath.MAX_FREQ_HZ, EqCurveMath.bandFreqHz(99), 0f)
    }

    @Test
    fun `nearestBand picks closest dot`() {
        val w = 1000f
        val x0 = EqCurveMath.freqToX(31f, w, 32f, 12f)
        val x1 = EqCurveMath.freqToX(62f, w, 32f, 12f)
        assertEquals(0, EqCurveMath.nearestBand((x0 + x1) / 2 - 1f, w, 32f, 12f, 10))
        assertEquals(1, EqCurveMath.nearestBand((x0 + x1) / 2 + 1f, w, 32f, 12f, 10))
    }

    @Test
    fun `shortFreqLabel fractional kilos`() {
        assertEquals("1.5k", EqCurveMath.shortFreqLabel(1500f))
        assertEquals("999", EqCurveMath.shortFreqLabel(999f))
        assertEquals("31", EqCurveMath.shortFreqLabel(31.4f))
    }

    @Test
    fun `band freqs are ascending`() {
        val bands = EqCurveMath.BAND_FREQS_HZ
        assertEquals(10, bands.size)
        for (i in 1 until bands.size) assertTrue(bands[i] > bands[i - 1])
    }
}
