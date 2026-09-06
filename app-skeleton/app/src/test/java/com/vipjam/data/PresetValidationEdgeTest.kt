package com.vipjam.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetValidationEdgeTest {
    @Test
    fun `missing schema version rejected`() {
        assertTrue(PresetImporter.parseV3("""{"origin":"viper","name":"x"}""").isFailure)
    }

    @Test
    fun `missing origin rejected`() {
        assertTrue(PresetImporter.parseV3("""{"schemaVersion":3,"name":"x"}""").isFailure)
    }

    @Test
    fun `unknown origin rejected`() {
        val bad = """{"schemaVersion":3,"origin":"dolby","name":"x"}"""
        val err = PresetImporter.parseV3(bad).exceptionOrNull()?.message.orEmpty()
        assertTrue(err.contains("unknown origin"))
    }

    @Test
    fun `blank name rejected`() {
        assertTrue(PresetImporter.parseV3("""{"schemaVersion":3,"origin":"viper","name":""}""").isFailure)
        assertTrue(PresetImporter.parseV3("""{"schemaVersion":3,"origin":"viper","name":"   "}""").isFailure)
        assertTrue(PresetImporter.parseV3("""{"schemaVersion":3,"origin":"viper"}""").isFailure)
    }

    @Test
    fun `unknown james stage rejected`() {
        val bad = """{"schemaVersion":3,"origin":"james","name":"x","james":{"nope":{}}}"""
        val err = PresetImporter.parseV3(bad).exceptionOrNull()?.message.orEmpty()
        assertTrue(err.contains("unknown james stage"))
    }

    @Test
    fun `valid james stage accepted`() {
        val ok = """{"schemaVersion":3,"origin":"james","name":"x","james":{"tone":{"enable":true}}}"""
        assertTrue(PresetImporter.parseV3(ok).isSuccess)
    }

    @Test
    fun `route key allowed`() {
        val ok = """{"schemaVersion":3,"origin":"viper","name":"x","route":"headset"}"""
        assertTrue(PresetImporter.parseV3(ok).isSuccess)
    }

    @Test
    fun `masterEnable absent defaults true`() {
        val preset = PresetImporter.parseV3("""{"schemaVersion":3,"origin":"viper","name":"x"}""").getOrThrow()
        assertTrue(preset.masterEnable)
    }

    @Test
    fun `masterEnable false survives`() {
        val preset = PresetImporter.parseV3(
            """{"schemaVersion":3,"origin":"viper","name":"x","masterEnable":false}""",
        ).getOrThrow()
        assertEquals(false, preset.masterEnable)
    }

    @Test
    fun `equalizer without bands skips count check`() {
        val ok = """{"schemaVersion":3,"origin":"viper","name":"x","equalizer":{"enable":true}}"""
        assertTrue(PresetImporter.parseV3(ok).isSuccess)
    }

    @Test
    fun `equalizer bandCount absent with bands fails`() {
        val bad = """{"schemaVersion":3,"origin":"viper","name":"x","equalizer":{"bands":[0.0]}}"""
        assertTrue(PresetImporter.parseV3(bad).isFailure)
    }

    @Test
    fun `groupEnables sorted and skips enable-less groups`() {
        val json = """{"schemaVersion":3,"origin":"viper","name":"x",
            "bass":{"enable":true},"reverb":{"roomSize":4},"clarity":{"enable":false}}"""
        assertEquals(
            listOf("bass" to true, "clarity" to false),
            PresetImporter.groupEnables(json),
        )
    }

    @Test
    fun `withGroupEnabled unknown group rejected`() {
        try {
            PresetImporter.withGroupEnabled("{}", "nope", true)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("unknown group"))
        }
    }

    @Test
    fun `withGroupEnabled absent group throws`() {
        try {
            PresetImporter.withGroupEnabled("""{"schemaVersion":3}""", "bass", true)
            throw AssertionError("expected exception")
        } catch (e: Exception) {
            assertTrue(e is org.json.JSONException)
        }
    }

    @Test
    fun `unpack rejects non-object json`() {
        val raw = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("[1,2]".toByteArray(Charsets.UTF_8))
        assertTrue(PresetImporter.unpackLink(PresetImporter.LINK_SCHEME + raw).isFailure)
    }

    @Test
    fun `unpack rejects non-json payload`() {
        val raw = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("hello".toByteArray(Charsets.UTF_8))
        assertTrue(PresetImporter.unpackLink(PresetImporter.LINK_SCHEME + raw).isFailure)
    }

    @Test
    fun `unpack rejects invalid v3 payload`() {
        val raw = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"schemaVersion":2}""".toByteArray(Charsets.UTF_8))
        assertTrue(PresetImporter.unpackLink(PresetImporter.LINK_SCHEME + raw).isFailure)
    }

    @Test
    fun `pack link is unpadded url safe`() {
        val json = """{"schemaVersion":3,"origin":"viper","name":"ü"}"""
        val link = PresetImporter.packLink(json)
        assertTrue(link.startsWith(PresetImporter.LINK_SCHEME))
        assertTrue(!link.removePrefix(PresetImporter.LINK_SCHEME).contains("="))
        assertEquals(json, PresetImporter.unpackLink(link).getOrThrow())
    }

    @Test
    fun `source keys accepted`() {
        val ok = """{"schemaVersion":3,"origin":"viper","name":"x","source":"wavelet","sourceName":"Wavelet"}"""
        assertTrue(PresetImporter.parseV3(ok).isSuccess)
    }

    @Test
    fun `createdAt accepted`() {
        val ok = """{"schemaVersion":3,"origin":"viper","name":"x","createdAt":"2026-01-01"}"""
        assertTrue(PresetImporter.parseV3(ok).isSuccess)
    }

    @Test
    fun `source keys wrong type rejected`() {
        val bad = """{"schemaVersion":3,"origin":"viper","name":"x","source":42}"""
        assertTrue(PresetImporter.parseV3(bad).isFailure)
    }

    private fun bankResource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("presets/bank/$name")!!
            .bufferedReader().readText()

    @Test
    fun `bank viper default parses`() {
        val preset = PresetImporter.parseV3(bankResource("viper-v2--default.json")).getOrThrow()
        assertTrue(preset.name.isNotBlank())
    }

    @Test
    fun `bank viper game parses`() {
        val preset = PresetImporter.parseV3(bankResource("viper-v2--game-v2.json")).getOrThrow()
        assertTrue(preset.name.isNotBlank())
    }

    @Test
    fun `bank james demo parses`() {
        val preset = PresetImporter.parseV3(bankResource("jamesdsp--james-headset-demo.json")).getOrThrow()
        assertTrue(preset.name.isNotBlank())
    }
}
