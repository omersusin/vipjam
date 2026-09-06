package com.vipjam.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FailingSink : ParamSink {
    override fun setParam(id: Int, v0: Int): Boolean = false
    override fun setParam(id: Int, v0: Int, v1: Int): Boolean = false
    override fun setParam(id: Int, v0: Int, v1: Int, v2: Int): Boolean = false
}

private class OkSink : ParamSink {
    val calls = mutableListOf<Int>()
    override fun setParam(id: Int, v0: Int): Boolean {
        calls += id
        return true
    }
    override fun setParam(id: Int, v0: Int, v1: Int): Boolean {
        calls += id
        return true
    }
    override fun setParam(id: Int, v0: Int, v1: Int, v2: Int): Boolean {
        calls += id
        return true
    }
}

class PresetApplierEdgeTest {
    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("presets/$name")!!
            .bufferedReader().readText()

    @Test
    fun `apply tolerates missing groups`() {
        val sink = OkSink()
        assertTrue(PresetApplier.apply(sink, """{"schemaVersion":3}""", true))
        assertEquals(listOf(VipJamDispatcher.P_MASTER), sink.calls)
    }

    @Test
    fun `apply with master off sends zero`() {
        val seen = mutableMapOf<Int, Int>()
        val sink = object : ParamSink {
            override fun setParam(id: Int, v0: Int): Boolean {
                seen[id] = v0
                return true
            }
            override fun setParam(id: Int, v0: Int, v1: Int): Boolean = true
            override fun setParam(id: Int, v0: Int, v1: Int, v2: Int): Boolean = true
        }
        assertTrue(PresetApplier.apply(sink, """{"schemaVersion":3}""", false))
        assertEquals(0, seen[VipJamDispatcher.P_MASTER])
    }

    @Test
    fun `apply propagates sink failure`() {
        assertFalse(PresetApplier.apply(FailingSink(), resource("Movie.v3.json"), true))
    }

    @Test
    fun `clarity disabled still dispatches mode`() {
        val groups = mutableListOf<Triple<Int, Int, Int?>>()
        val sink = object : ParamSink {
            override fun setParam(id: Int, v0: Int): Boolean = true
            override fun setParam(id: Int, v0: Int, v1: Int): Boolean {
                groups += Triple(id, v0, v1)
                return true
            }
            override fun setParam(id: Int, v0: Int, v1: Int, v2: Int): Boolean = true
        }
        val json = """{"clarity":{"enable":false,"gain":10,"mode":3}}"""
        assertTrue(PresetApplier.apply(sink, json, true))
        assertEquals(listOf(Triple(VipJamDispatcher.F_CLARITY, 10, 3)), groups)
    }

    @Test
    fun `eq band half rounds up`() {
        val json = resource("Movie.v3.json")
        val updated = PresetApplier.withGroupScalar(json, "equalizer", "1", 1.5)
        assertEquals(1.5, JSONObject(updated).getJSONObject("equalizer").getJSONArray("bands").getDouble(1), 0.0)
    }

    @Test
    fun `reverb damp accepted`() {
        val json = resource("Movie.v3.json")
        val updated = PresetApplier.withGroupScalar(json, "reverb", "damp", 7.0)
        assertEquals(7, JSONObject(updated).getJSONObject("reverb").getInt("damp"))
    }

    @Test
    fun `scalar on absent group rejected`() {
        try {
            PresetApplier.withGroupScalar("""{"schemaVersion":3}""", "bass", "gain", 1.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("group absent"))
        }
    }

    @Test
    fun `eq absent bands rejected`() {
        try {
            PresetApplier.withGroupScalar("""{"equalizer":{}}""", "equalizer", "0", 1.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("no bands"))
        }
    }

    @Test
    fun `eq negative band index rejected`() {
        val json = resource("Movie.v3.json")
        try {
            PresetApplier.withGroupScalar(json, "equalizer", "-1", 1.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("band index"))
        }
    }

    @Test
    fun `intBytes is little endian`() {
        assertEquals(listOf<Byte>(1, 0, 0, 0), VipJamDispatcher.intBytes(1).toList())
        assertEquals(listOf<Byte>(0, 0, 0, 0), VipJamDispatcher.intBytes(0).toList())
        assertEquals(listOf<Byte>(-1, -1, -1, -1), VipJamDispatcher.intBytes(-1).toList())
        val back = ByteBuffer.wrap(VipJamDispatcher.intBytes(0x20050)).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0x20050, back)
    }
}
