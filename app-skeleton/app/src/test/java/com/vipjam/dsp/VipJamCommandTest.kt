package com.vipjam.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VipJamCommandTest {
    @Test
    fun `toggle parses`() {
        assertEquals(
            VipJamCommand.ToggleMaster,
            VipJamCommandParser.parse("""{"cmd":"toggle"}"""),
        )
    }

    @Test
    fun `profile parses`() {
        assertEquals(
            VipJamCommand.SetProfile("headset"),
            VipJamCommandParser.parse("""{"cmd":"profile","route":"headset"}"""),
        )
    }

    @Test
    fun `param parses with defaults`() {
        assertEquals(
            VipJamCommand.SetParam(65577, 300, 0, 0),
            VipJamCommandParser.parse("""{"cmd":"param","id":65577,"v0":300}"""),
        )
    }

    @Test
    fun `preset parses`() {
        val inner = """{"schemaVersion":3}"""
        val cmd = VipJamCommandParser.parse("""{"cmd":"preset","json":$inner}""")
        assertTrue(cmd is VipJamCommand.ApplyPreset)
        assertEquals(inner, (cmd as VipJamCommand.ApplyPreset).settingsJson)
    }

    @Test
    fun `garbage rejected`() {
        assertNull(VipJamCommandParser.parse(null))
        assertNull(VipJamCommandParser.parse(""))
        assertNull(VipJamCommandParser.parse("not json"))
        assertNull(VipJamCommandParser.parse("""{"cmd":"nope"}"""))
        assertNull(VipJamCommandParser.parse("""{"cmd":"profile"}"""))
        assertNull(VipJamCommandParser.parse("""{"cmd":"param","v0":1}"""))
        assertNull(VipJamCommandParser.parse("""{"cmd":"preset"}"""))
    }
}
