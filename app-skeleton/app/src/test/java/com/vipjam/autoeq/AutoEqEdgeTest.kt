package com.vipjam.autoeq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoEqEdgeTest {
    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("autoeq/$name")!!
            .bufferedReader().readText()

    @Test
    fun `off filters are skipped`() {
        val text = "Preamp: -6.0 dB\n" +
            "Filter 1: OFF PK Fc 100 Hz Gain 5.0 dB Q 1.0\n" +
            "Filter 2: ON PK Fc 200 Hz Gain 3.0 dB Q 1.0\n"
        val eq = AutoEq.parseParametric(text)
        assertEquals(1, eq.filters.size)
        assertEquals(200.0, eq.filters[0].fcHz, 1e-9)
    }

    @Test
    fun `all off rejected as no filters`() {
        try {
            AutoEq.parseParametric("Preamp: -6.0 dB\nFilter 1: OFF PK Fc 100 Hz Gain 5.0 dB Q 1.0\n")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("no ON"))
        }
    }

    @Test
    fun `duplicate preamp rejected`() {
        try {
            AutoEq.parseParametric("Preamp: -6.0 dB\nPreamp: -7.0 dB\nFilter 1: ON PK Fc 100 Hz Gain 0 dB Q 1.0\n")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("duplicate"))
        }
    }

    @Test
    fun `zero q rejected`() {
        try {
            AutoEq.parseParametric("Preamp: -6.0 dB\nFilter 1: ON PK Fc 100 Hz Gain 0 dB Q 0.0\n")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("bad Q"))
        }
    }

    @Test
    fun `zero fc rejected`() {
        try {
            AutoEq.parseParametric("Preamp: -6.0 dB\nFilter 1: ON PK Fc 0 Hz Gain 0 dB Q 1.0\n")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("bad Fc"))
        }
    }

    @Test
    fun `non-finite gain rejected`() {
        try {
            AutoEq.parseParametric("Preamp: -6.0 dB\nFilter 1: ON PK Fc 100 Hz Gain Infinity dB Q 1.0\n")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `missing preamp rejected`() {
        try {
            AutoEq.parseParametric("Filter 1: ON PK Fc 100 Hz Gain 0 dB Q 1.0\n")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("Preamp"))
        }
    }

    @Test
    fun `toVdc rejects empty and bad rates`() {
        val eq = AutoEq.parseParametric(resource("Sennheiser HD 600 ParametricEQ.txt"))
        try {
            AutoEq.toVdc(eq, emptyList())
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
        }
        try {
            AutoEq.toVdc(eq, listOf(0))
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `toVdc single rate validates structurally`() {
        val eq = AutoEq.parseParametric(resource("Sennheiser HD 600 ParametricEQ.txt"))
        val vdc = AutoEq.toVdc(eq, listOf(48000))
        assertTrue(vdc.startsWith("SR_48000:"))
        assertFalse(AutoEq.validateVdc(vdc))
    }

    @Test
    fun `validateVdc rejects count mismatch and empty`() {
        assertFalse(AutoEq.validateVdc("SR_44100:1,2,3,4,5\nSR_48000:1,2,3,4\n"))
        assertFalse(AutoEq.validateVdc("SR_44100:\nSR_48000:\n"))
    }

    @Test
    fun `profileUrl joins base and path`() {
        assertEquals(AutoEq.BASE_URL + "/a/b.txt", AutoEqDownloader().profileUrl("a/b.txt"))
    }

    @Test
    fun `shelf biquads are finite`() {
        for (t in listOf(FilterType.LSC, FilterType.HSC)) {
            val c = AutoEq.rbjBiquad(t, 100.0, -6.0, 0.7, 44100)
            assertEquals(5, c.size)
            for (v in c) assertTrue("non-finite $v", v.isFinite())
        }
    }

    @Test
    fun `zero gain shelf has unity dc gain`() {
        val c = AutoEq.rbjBiquad(FilterType.LSC, 100.0, 0.0, 0.7, 48000)
        val dc = (c[0] + c[1] + c[2]) / (1.0 - c[3] - c[4])
        assertEquals(1.0, dc, 1e-9)
    }
}
