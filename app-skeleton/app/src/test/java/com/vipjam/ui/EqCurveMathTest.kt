package com.vipjam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqCurveMathTest {

    @Test
    fun `31Hz maps near left edge`() {
        val x = EqCurveMath.freqToXNorm(31f)
        assertTrue("31Hz xNorm=$x should be > 0", x > 0f)
        assertTrue("31Hz xNorm=$x should be < 0.1", x < 0.1f)
    }

    @Test
    fun `16kHz maps near right edge`() {
        val x = EqCurveMath.freqToXNorm(16000f)
        assertTrue("16kHz xNorm=$x should be > 0.9", x > 0.9f)
        assertTrue("16kHz xNorm=$x should be < 1", x < 1f)
    }

    @Test
    fun `freq range edges map exactly`() {
        assertEquals(0f, EqCurveMath.freqToXNorm(EqCurveMath.MIN_FREQ_HZ), 1e-6f)
        assertEquals(1f, EqCurveMath.freqToXNorm(EqCurveMath.MAX_FREQ_HZ), 1e-6f)
    }

    @Test
    fun `plus12dB maps to top`() {
        assertEquals(0f, EqCurveMath.dbToYNorm(12f), 1e-6f)
    }

    @Test
    fun `minus12dB maps to bottom and zero to middle`() {
        assertEquals(1f, EqCurveMath.dbToYNorm(-12f), 1e-6f)
        assertEquals(0.5f, EqCurveMath.dbToYNorm(0f), 1e-6f)
    }

    @Test
    fun `freq roundtrip through norm`() {
        for (f in EqCurveMath.BAND_FREQS_HZ) {
            val back = EqCurveMath.xNormToFreqHz(EqCurveMath.freqToXNorm(f))
            assertEquals("freq $f", f, back, f * 1e-4f)
        }
    }

    @Test
    fun `db roundtrip through norm`() {
        for (db in floatArrayOf(-12f, -6f, 0f, 3.5f, 6f, 12f)) {
            assertEquals("db $db", db, EqCurveMath.yNormToDb(EqCurveMath.dbToYNorm(db)), 1e-5f)
        }
    }

    @Test
    fun `px mapping respects padding`() {
        val w = 1000f
        val padL = 32f
        val padR = 12f
        val x31 = EqCurveMath.freqToX(31f, w, padL, padR)
        assertTrue("31Hz x=$x31 should hug left pad $padL", x31 - padL < (w - padL - padR) * 0.1f)
        val x16k = EqCurveMath.freqToX(16000f, w, padL, padR)
        assertTrue("16k x=$x16k should hug right edge", (w - padR) - x16k < (w - padL - padR) * 0.1f)
        assertEquals(padL, EqCurveMath.freqToX(20f, w, padL, padR), 1e-3f)
        assertEquals(w - padR, EqCurveMath.freqToX(20000f, w, padL, padR), 1e-3f)
        assertEquals(10f, EqCurveMath.dbToY(12f, 500f, 10f, 24f), 1e-3f)
        assertEquals(500f - 24f, EqCurveMath.dbToY(-12f, 500f, 10f, 24f), 1e-3f)
    }

    @Test
    fun `clampDb limits to plus-minus 12`() {
        assertEquals(12f, EqCurveMath.clampDb(20f), 0f)
        assertEquals(-12f, EqCurveMath.clampDb(-20f), 0f)
        assertEquals(3f, EqCurveMath.clampDb(3f), 0f)
    }

    @Test
    fun `nearestBand finds tapped dot`() {
        val w = 1000f
        val padL = 32f
        val padR = 12f
        val n = 10
        for (i in 0 until n) {
            val x = EqCurveMath.freqToX(EqCurveMath.bandFreqHz(i), w, padL, padR)
            assertEquals("band $i", i, EqCurveMath.nearestBand(x, w, padL, padR, n))
        }
        assertEquals(0, EqCurveMath.nearestBand(-100f, w, padL, padR, n))
        assertEquals(n - 1, EqCurveMath.nearestBand(5000f, w, padL, padR, n))
        assertEquals(0, EqCurveMath.nearestBand(500f, w, padL, padR, 0))
    }

    @Test
    fun `shortFreqLabel formats bands`() {
        assertEquals("31", EqCurveMath.shortFreqLabel(31f))
        assertEquals("1k", EqCurveMath.shortFreqLabel(1000f))
        assertEquals("2k", EqCurveMath.shortFreqLabel(2000f))
        assertEquals("16k", EqCurveMath.shortFreqLabel(16000f))
    }
}
