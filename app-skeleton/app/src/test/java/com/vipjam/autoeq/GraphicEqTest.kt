package com.vipjam.autoeq

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GraphicEqTest {

    @Test
    fun `parse valid single-line graphic eq`() {
        val points = GraphicEq.parse("GraphicEQ: 20 -2.0; 100 1.5; 1000 0.0; 20000 3.25")
        assertEquals(4, points.size)
        assertEquals(20.0, points[0].first, 1e-9)
        assertEquals(-2.0, points[0].second, 1e-9)
        assertEquals(20000.0, points[3].first, 1e-9)
        assertEquals(3.25, points[3].second, 1e-9)
    }

    @Test
    fun `parse multiline graphic eq with extra text`() {
        val text = "Preamp: -6 dB\nGraphicEQ: 20 1.0; 40 2.0\n100 3.0; 1000 -1.0\n"
        val points = GraphicEq.parse(text)
        assertEquals(4, points.size)
        assertEquals(100.0, points[2].first, 1e-9)
    }

    @Test
    fun `parse invalid inputs rejected`() {
        assertRejects("")
        assertRejects("   ")
        assertRejects("no prefix here")
        assertRejects("GraphicEQ:")
        assertRejects("GraphicEQ: 100; 200 1.0")
        assertRejects("GraphicEQ: abc 1.0")
        assertRejects("GraphicEQ: 100 xyz")
        assertRejects("GraphicEQ: -20 1.0")
        assertRejects("GraphicEQ: 0 1.0")
        assertRejects("GraphicEQ: NaN 1.0")
        assertRejects("GraphicEQ: 100 Infinity")
        assertRejects("GraphicEQ: 100 1.0 2.0")
    }

    @Test
    fun `sample exact points`() {
        val points = GraphicEq.parse("GraphicEQ: 100 -3.0; 1000 2.0; 10000 -1.0")
        assertEquals(-3.0, GraphicEq.sample(points, 100.0), 1e-9)
        assertEquals(2.0, GraphicEq.sample(points, 1000.0), 1e-9)
        assertEquals(-1.0, GraphicEq.sample(points, 10000.0), 1e-9)
    }

    @Test
    fun `sample log-interpolates between points`() {
        val points = GraphicEq.parse("GraphicEQ: 100 0.0; 1000 10.0")
        val mid = GraphicEq.sample(points, 316.22776601683796)
        assertEquals(5.0, mid, 1e-6)
        val quarter = GraphicEq.sample(points, 100.0 * Math.pow(10.0, 0.25))
        assertEquals(2.5, quarter, 1e-6)
    }

    @Test
    fun `sample outside range holds edge and clamps`() {
        val points = GraphicEq.parse("GraphicEQ: 100 5.0; 1000 -4.0")
        assertEquals(5.0, GraphicEq.sample(points, 20.0), 1e-9)
        assertEquals(-4.0, GraphicEq.sample(points, 20000.0), 1e-9)
        val hot = GraphicEq.parse("GraphicEQ: 100 20.0; 1000 -20.0")
        assertEquals(12.0, GraphicEq.sample(hot, 100.0), 1e-9)
        assertEquals(-12.0, GraphicEq.sample(hot, 1000.0), 1e-9)
        assertEquals(12.0, GraphicEq.sample(hot, 20.0), 1e-9)
        assertEquals(-12.0, GraphicEq.sample(hot, 20000.0), 1e-9)
    }

    @Test
    fun `sample interpolated values clamp to plus-minus 12`() {
        val points = GraphicEq.parse("GraphicEQ: 100 0.0; 1000 30.0")
        assertEquals(12.0, GraphicEq.sample(points, 1000.0), 1e-9)
        val v = GraphicEq.sample(points, 500.0)
        assertEquals(true, v <= 12.0 && v >= -12.0)
    }

    @Test
    fun `unsorted input is sorted`() {
        val points = GraphicEq.parse("GraphicEQ: 1000 2.0; 100 -3.0; 10000 -1.0")
        assertEquals(100.0, points[0].first, 1e-9)
        assertEquals(1000.0, points[1].first, 1e-9)
        assertEquals(10000.0, points[2].first, 1e-9)
        assertEquals(2.0, GraphicEq.sample(points, 1000.0), 1e-9)
    }

    @Test
    fun `sampleBands maps each band freq`() {
        val points = GraphicEq.parse("GraphicEQ: 20 1.0; 20000 1.0")
        val bands = GraphicEq.sampleBands(points, floatArrayOf(31f, 1000f, 16000f))
        assertEquals(3, bands.size)
        for (b in bands) assertEquals(1.0, b, 1e-9)
    }

    private fun assertRejects(text: String) {
        try {
            GraphicEq.parse(text)
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected rejection for: " + text.take(80))
    }
}
