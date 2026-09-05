package com.vipjam.autoeq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoEqTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("autoeq/$name")!!
            .bufferedReader().readText()

    @Test
    fun `parse parametric hd600 fixture`() {
        val eq = AutoEq.parseParametric(resource("Sennheiser HD 600 ParametricEQ.txt"))
        assertEquals(-6.3, eq.preampDb, 1e-9)
        assertEquals(10, eq.filters.size)
        val first = eq.filters[0]
        assertEquals(FilterType.LSC, first.type)
        assertEquals(105.0, first.fcHz, 1e-9)
        assertEquals(6.5, first.gainDb, 1e-9)
        assertEquals(0.70, first.q, 1e-9)
        val shelf = eq.filters[5]
        assertEquals(FilterType.HSC, shelf.type)
        assertEquals(10000.0, shelf.fcHz, 1e-9)
    }

    @Test
    fun `parse fixed-band hd600 fixture`() {
        val eq = AutoEq.parseParametric(resource("Sennheiser HD 600 FixedBandEQ.txt"))
        assertEquals(-7.5, eq.preampDb, 1e-9)
        assertEquals(10, eq.filters.size)
        for (f in eq.filters) {
            assertEquals(FilterType.PK, f.type)
            assertEquals(1.41, f.q, 1e-9)
        }
        assertEquals(31.0, eq.filters.first().fcHz, 1e-9)
        assertEquals(16000.0, eq.filters.last().fcHz, 1e-9)
    }

    @Test
    fun `fixed-band Q is 1_41 on every band`() {
        val eq = AutoEq.parseParametric(resource("Sennheiser HD 600 FixedBandEQ.txt"))
        assertTrue(eq.filters.isNotEmpty())
        assertTrue(eq.filters.all { it.q == 1.41 })
    }

    @Test
    fun `toVdc output passes validateVdc for both fixtures`() {
        for (name in listOf(
            "Sennheiser HD 600 ParametricEQ.txt",
            "Sennheiser HD 600 FixedBandEQ.txt",
        )) {
            val eq = AutoEq.parseParametric(resource(name))
            val vdc = AutoEq.toVdc(eq)
            assertTrue("$name vdc invalid:\n$vdc", AutoEq.validateVdc(vdc))
        }
    }

    @Test
    fun `toVdc emits both SR tags with equal 5-aligned counts`() {
        val eq = AutoEq.parseParametric(resource("Sennheiser HD 600 ParametricEQ.txt"))
        val vdc = AutoEq.toVdc(eq)
        assertTrue(vdc.contains("SR_44100:"))
        assertTrue(vdc.contains("SR_48000:"))
        fun count(tag: String): Int {
            val line = vdc.substringAfter(tag).lineSequence().first()
            return line.split("SR_")[0].split(',').filter { it.isNotBlank() }.size
        }
        val a = count("SR_44100:")
        val b = count("SR_48000:")
        assertEquals(10 * 5, a)
        assertEquals(a, b)
        assertEquals(0, a % 5)
    }

    @Test
    fun `zero-dB peaking biquad is unity at b0`() {
        val c = AutoEq.rbjBiquad(FilterType.PK, 1000.0, 0.0, 1.0, 48000)
        assertEquals(5, c.size)
        assertEquals(1.0, c[0], 1e-12)
        for (v in c) assertTrue("non-finite $v", v.isFinite())
    }

    @Test
    fun `malformed input rejected`() {
        assertRejects("Filter 1: ON PK Fc 100 Hz Gain 0 dB Q 1.0\n")
        assertRejects("Preamp: -6 dB\nGarbage line\nFilter 1: ON PK Fc 100 Hz Gain 0 dB Q 1.0\n")
        assertRejects("Preamp: -6 dB\nFilter 1: ON XX Fc 100 Hz Gain 0 dB Q 1.0\n")
        assertRejects("Preamp: -6 dB\n")
        assertRejects("")
        assertRejects("Preamp: abc dB\nFilter 1: ON PK Fc 100 Hz Gain 0 dB Q 1.0\n")
        assertRejects("Preamp: -6 dB\nPreamp: -7 dB\nFilter 1: ON PK Fc 100 Hz Gain 0 dB Q 1.0\n")
    }

    @Test
    fun `validateVdc rejects garbage`() {
        assertFalse(AutoEq.validateVdc(""))
        assertFalse(AutoEq.validateVdc("SR_44100:1,2,3\n"))
        assertFalse(AutoEq.validateVdc("SR_44100:1,2,3,4,5\nSR_48000:1,2,3\n"))
        assertFalse(AutoEq.validateVdc("SR_44100:1,2,3,NaN,5\nSR_48000:1,2,3,4,5\n"))
        assertFalse(AutoEq.validateVdc("SR_44100:1,2,3,Infinity,5\nSR_48000:1,2,3,4,5\n"))
        assertFalse(AutoEq.validateVdc("SR_44100:a,b,c,d,e\nSR_48000:1,2,3,4,5\n"))
    }

    private fun assertRejects(text: String) {
        try {
            AutoEq.parseParametric(text)
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected rejection for: " + text.take(80))
    }
}
