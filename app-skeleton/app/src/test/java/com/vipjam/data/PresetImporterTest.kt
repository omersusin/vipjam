package com.vipjam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresetImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("presets/$name")!!
            .bufferedReader().readText()

    @Test
    fun `movie preset parses`() {
        val preset = PresetImporter.parseV3(resource("Movie.v3.json")).getOrThrow()
        assertEquals("Movie", preset.name)
        assertEquals("viper", preset.origin)
        assertTrue(preset.masterEnable)
    }

    @Test
    fun `game preset parses`() {
        val preset = PresetImporter.parseV3(resource("Game.v3.json")).getOrThrow()
        assertEquals("Game", preset.name)
    }

    @Test
    fun `rejects bad schema version`() {
        val bad = """{"schemaVersion":2,"origin":"viper","name":"x"}"""
        assertTrue(PresetImporter.parseV3(bad).isFailure)
    }

    @Test
    fun `rejects unknown group`() {
        val bad = """{"schemaVersion":3,"origin":"viper","name":"x","nope":{}}"""
        val err = PresetImporter.parseV3(bad).exceptionOrNull()?.message.orEmpty()
        assertTrue(err.contains("unknown group"))
    }

    @Test
    fun `rejects band count mismatch`() {
        val bad = """{"schemaVersion":3,"origin":"viper","name":"x",
            "equalizer":{"enable":true,"bandCount":5,"bands":[0.0,0.0]}}"""
        assertTrue(PresetImporter.parseV3(bad).isFailure)
    }

    @Test
    fun `link round trip`() {
        val json = resource("Game.v3.json")
        val preset = PresetImporter.parseV3(json).getOrThrow()
        val link = PresetImporter.packLink(preset.settingsJson)
        assertTrue(link.startsWith(PresetImporter.LINK_SCHEME))
        val back = PresetImporter.unpackLink(link).getOrThrow()
        assertEquals(preset.settingsJson, PresetImporter.parseV3(back).getOrThrow().settingsJson)
    }

    @Test
    fun `unpack rejects bad scheme and payload`() {
        assertTrue(PresetImporter.unpackLink("https://example.com/").isFailure)
        val bad = PresetImporter.LINK_SCHEME + "!!!"
        assertTrue(PresetImporter.unpackLink(bad).isFailure)
    }

    @Test
    fun `group toggles round trip`() {
        val json = resource("Movie.v3.json")
        val groups = PresetImporter.groupEnables(json)
        assertTrue(groups.isNotEmpty())
        val bass = groups.first { it.first == "bass" }
        val flipped = PresetImporter.withGroupEnabled(json, "bass", !bass.second)
        val back = PresetImporter.groupEnables(flipped)
        assertEquals(!bass.second, back.first { it.first == "bass" }.second)
        assertTrue(PresetImporter.parseV3(flipped).isSuccess)
    }

    @Test
    fun `liveprog validation catches bad scripts`() {
        assertTrue(LiveProgScripts.validate("@init\nx=1;\n@sample\ny=x;").isEmpty())
        val errors = LiveProgScripts.validate("y=x;")
        assertTrue(errors.any { it.contains("@init") })
        assertTrue(errors.any { it.contains("@sample") })
        assertTrue(LiveProgScripts.validate("@init\n@sample\nfoo(bar;").any {
            it.contains("unbalanced")
        })
    }

    @Test
    fun `store saves lists and deletes`() = runTest {
        val file = tmp.newFile("prefs.preferences_pb")
        val store = PresetStore(PreferenceDataStoreFactory.create { file })
        val json = resource("Movie.v3.json")
        assertEquals("Movie", store.importText(json).getOrThrow())
        assertEquals(listOf("Movie"), store.entries.first().map { it.name })
        val link = PresetImporter.packLink(resource("Game.v3.json"))
        assertEquals("Game", store.importLink(link).getOrThrow())
        assertEquals(
            listOf("Game", "Movie"),
            store.entries.first().map { it.name },
        )
        store.delete("Movie")
        assertEquals(listOf("Game"), store.entries.first().map { it.name })
    }
}
