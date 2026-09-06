package com.vipjam.root

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ModuleFlasherTest {
    @Test
    fun picksFirstMatchingModuleAsset() {
        val json = "{\"assets\":[" +
            "{\"name\":\"vipjam-app.apk\",\"browser_download_url\":\"https://example.com/app.apk\"}," +
            "{\"name\":\"vipjam-magisk-v1.2.3.zip\",\"browser_download_url\":\"https://example.com/mod.zip\"}," +
            "{\"name\":\"vipjam-magisk-v1.2.2.zip\",\"browser_download_url\":\"https://example.com/old.zip\"}" +
            "]}"
        val asset = ReleaseApi.pickModuleAsset(json)
        assertEquals("vipjam-magisk-v1.2.3.zip", asset?.name)
        assertEquals("https://example.com/mod.zip", asset?.url)
    }

    @Test
    fun returnsNullWhenNoAssetMatches() {
        val json = "{\"assets\":[" +
            "{\"name\":\"vipjam-app.apk\",\"browser_download_url\":\"https://example.com/app.apk\"}" +
            "]}"
        assertNull(ReleaseApi.pickModuleAsset(json))
    }

    @Test
    fun rejectsMalformedReleaseJson() {
        try {
            ReleaseApi.pickModuleAsset("not json{{{")
            fail("expected JSONException")
        } catch (e: JSONException) {
        }
        try {
            ReleaseApi.pickModuleAsset("{\"tag_name\":\"v1\"}")
            fail("expected JSONException")
        } catch (e: JSONException) {
        }
    }

    @Test
    fun buildsPerManagerCommands() {
        assertEquals("magisk --install-module /tmp/a.zip", flashCommand(RootManager.MAGISK, "/tmp/a.zip"))
        assertEquals("ksud module install /tmp/a.zip", flashCommand(RootManager.KERNELSU, "/tmp/a.zip"))
        assertEquals("apd module install /tmp/a.zip", flashCommand(RootManager.APATCH, "/tmp/a.zip"))
    }

    @Test
    fun parsesModuleProp() {
        val prop = parseModuleProp("id=vipjam\nname=VipJam\nversion=v1.2.3\nversionCode=5\n")
        assertEquals("vipjam", prop["id"])
        assertEquals("v1.2.3", prop["version"])
        assertEquals("5", prop["versionCode"])
    }

    @Test
    fun modulePropSkipsGarbage() {
        val prop = parseModuleProp("# comment\n\nnoequals\n=value\nid=vipjam\n  version = v2 \n")
        assertEquals("vipjam", prop["id"])
        assertEquals("v2", prop["version"])
    }

    @Test
    fun flashSurvivesGarbageOutput() = runTest {
        val flasher = ModuleFlasher(
            runShell = { _, onLine ->
                onLine("")
                onLine("\u0000garbage{{{")
                0
            },
            resolveManager = { RootManager.MAGISK },
        )
        val events = flasher.flash(File("vipjam.zip")).toList()
        val done = events.filterIsInstance<FlashEvent.Finished>().single()
        assertTrue(done.ok)
        assertTrue(done.needsReboot)
    }

    @Test
    fun flashFailsWhenNoManager() = runTest {
        val flasher = ModuleFlasher(
            resolveManager = { RootManager.NONE },
        )
        val events = flasher.flash(File("vipjam.zip")).toList()
        val done = events.filterIsInstance<FlashEvent.Finished>().single()
        assertTrue(!done.ok)
    }
}
