package com.vipjam.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VipJamCommandEdgeTest {
    @Test
    fun `whitespace text rejected`() {
        assertNull(VipJamCommandParser.parse("   "))
        assertNull(VipJamCommandParser.parse("\n\t "))
    }

    @Test
    fun `json array rejected`() {
        assertNull(VipJamCommandParser.parse("[]"))
        assertNull(VipJamCommandParser.parse("[1,2]"))
    }

    @Test
    fun `json scalar rejected`() {
        assertNull(VipJamCommandParser.parse("42"))
        assertNull(VipJamCommandParser.parse("\"toggle\""))
    }

    @Test
    fun `missing cmd rejected`() {
        assertNull(VipJamCommandParser.parse("{}"))
        assertNull(VipJamCommandParser.parse("""{"route":"headset"}"""))
    }

    @Test
    fun `cmd is case sensitive`() {
        assertNull(VipJamCommandParser.parse("""{"cmd":"Toggle"}"""))
        assertNull(VipJamCommandParser.parse("""{"cmd":"TOGGLE"}"""))
        assertNull(VipJamCommandParser.parse("""{"cmd":"Profile","route":"headset"}"""))
    }

    @Test
    fun `toggle ignores extra fields`() {
        assertEquals(
            VipJamCommand.ToggleMaster,
            VipJamCommandParser.parse("""{"cmd":"toggle","route":"headset"}"""),
        )
    }

    @Test
    fun `profile blank route variants rejected`() {
        assertNull(VipJamCommandParser.parse("""{"cmd":"profile","route":""}"""))
        assertNull(VipJamCommandParser.parse("""{"cmd":"profile","route":"   "}"""))
    }

    @Test
    fun `profile keeps route verbatim`() {
        assertEquals(
            VipJamCommand.SetProfile("Headset"),
            VipJamCommandParser.parse("""{"cmd":"profile","route":"Headset"}"""),
        )
    }

    @Test
    fun `param explicit zero id parses`() {
        assertEquals(
            VipJamCommand.SetParam(0, 0, 0, 0),
            VipJamCommandParser.parse("""{"cmd":"param","id":0}"""),
        )
    }

    @Test
    fun `param keeps all lanes`() {
        assertEquals(
            VipJamCommand.SetParam(1, 2, 3, 4),
            VipJamCommandParser.parse("""{"cmd":"param","id":1,"v0":2,"v1":3,"v2":4}"""),
        )
    }

    @Test
    fun `param negative values pass through`() {
        assertEquals(
            VipJamCommand.SetParam(65577, -150, 0, 0),
            VipJamCommandParser.parse("""{"cmd":"param","id":65577,"v0":-150}"""),
        )
    }

    @Test
    fun `preset blank json variants rejected`() {
        assertNull(VipJamCommandParser.parse("""{"cmd":"preset","json":""}"""))
        assertNull(VipJamCommandParser.parse("""{"cmd":"preset","json":"   "}"""))
    }

    @Test
    fun `preset keeps nested object string`() {
        val inner = """{"schemaVersion":3,"origin":"viper","name":"x"}"""
        val cmd = VipJamCommandParser.parse("""{"cmd":"preset","json":$inner}""")
        assertTrue(cmd is VipJamCommand.ApplyPreset)
    }
}
