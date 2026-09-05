package com.vipjam.dsp

import com.vipjam.data.PresetImporter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetApplierTest {
    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("presets/$name")!!
            .bufferedReader().readText()

    @Test
    fun `bass gain rounds to int and still validates`() {
        val json = resource("Movie.v3.json")
        val updated = PresetApplier.withGroupScalar(json, "bass", "gain", 150.6)
        assertEquals(151, JSONObject(updated).getJSONObject("bass").getInt("gain"))
        assertTrue(PresetImporter.parseV3(updated).isSuccess)
    }

    @Test
    fun `clarity gain rounds to int and still validates`() {
        val json = resource("Game.v3.json")
        val updated = PresetApplier.withGroupScalar(json, "clarity", "gain", 149.4)
        assertEquals(149, JSONObject(updated).getJSONObject("clarity").getInt("gain"))
        assertTrue(PresetImporter.parseV3(updated).isSuccess)
    }

    @Test
    fun `reverb field rounds to int and still validates`() {
        val json = resource("Movie.v3.json")
        val updated = PresetApplier.withGroupScalar(json, "reverb", "roomSize", 4.5)
        val reverb = JSONObject(updated).getJSONObject("reverb")
        assertEquals(5, reverb.getInt("roomSize"))
        assertEquals(2, reverb.getInt("width"))
        assertTrue(PresetImporter.parseV3(updated).isSuccess)
    }

    @Test
    fun `eq band updates one index and still validates`() {
        val json = resource("Movie.v3.json")
        val before = JSONObject(json).getJSONObject("equalizer").getJSONArray("bands")
        val updated = PresetApplier.withGroupScalar(json, "equalizer", "0", 3.5)
        val eq = JSONObject(updated).getJSONObject("equalizer")
        assertEquals(3.5, eq.getJSONArray("bands").getDouble(0), 0.0)
        assertEquals(before.getDouble(1), eq.getJSONArray("bands").getDouble(1), 0.0)
        assertEquals(before.length(), eq.getJSONArray("bands").length())
        assertEquals(before.length(), eq.getInt("bandCount"))
        assertTrue(PresetImporter.parseV3(updated).isSuccess)
    }

    @Test
    fun `unknown group rejected`() {
        val json = resource("Movie.v3.json")
        try {
            PresetApplier.withGroupScalar(json, "nope", "gain", 1.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("unknown group"))
        }
    }

    @Test
    fun `unknown field rejected`() {
        val json = resource("Movie.v3.json")
        try {
            PresetApplier.withGroupScalar(json, "bass", "roomSize", 1.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("unknown field"))
        }
        try {
            PresetApplier.withGroupScalar(json, "reverb", "gain", 1.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("unknown field"))
        }
        try {
            PresetApplier.withGroupScalar(json, "equalizer", "abc", 1.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("band index"))
        }
        try {
            PresetApplier.withGroupScalar(json, "equalizer", "99", 1.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("band index"))
        }
    }
}
