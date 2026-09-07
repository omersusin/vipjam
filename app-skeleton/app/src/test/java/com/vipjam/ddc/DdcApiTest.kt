package com.vipjam.ddc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DdcApiTest {

    @Test
    fun `default source points at community pack`() {
        val src = DdcApi.defaultSource()
        assertEquals(DdcApi.DEFAULT_REPO, src.repo)
        assertEquals(DdcApi.DEFAULT_BRANCH, src.branch)
        assertTrue(src.label.isNotBlank())
    }

    @Test
    fun `raw url encodes path segments`() {
        val url = DdcApi.rawUrl("owner/repo", "main", "DDC/AKG K240.vdc")
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/DDC/AKG%20K240.vdc", url)
    }

    @Test
    fun `tree url format`() {
        val url = DdcApi.treeUrl("owner/repo", "main")
        assertEquals("https://api.github.com/repos/owner/repo/git/trees/main?recursive=1", url)
    }

    @Test
    fun `search is case-insensitive substring`() {
        val entries = listOf(
            DdcEntry("AKG K240.vdc", "DDC/AKG K240.vdc", "https://x/AKG K240.vdc"),
            DdcEntry("JBL J22.vdc", "DDC/JBL J22.vdc", "https://x/JBL J22.vdc"),
        )
        assertEquals(listOf(entries[0]), DdcApi.search(entries, "akg"))
        assertEquals(entries, DdcApi.search(entries, "  "))
        assertTrue(DdcApi.search(entries, "sony").isEmpty())
    }

    @Test
    fun `filterUnowned skips staged case-insensitively`() {
        val entries = listOf(
            DdcEntry("AKG K240.vdc", "p1", "u1"),
            DdcEntry("JBL J22.vdc", "p2", "u2"),
        )
        val out = DdcApi.filterUnowned(entries, setOf("akg k240.vdc"))
        assertEquals(listOf(entries[1]), out)
    }

    @Test
    fun `parse index json round trip`() {
        val entries = listOf(
            DdcEntry("AKG K240.vdc", "DDC/AKG K240.vdc", "https://x/akg.vdc"),
            DdcEntry("JBL J22.vdc", "DDC/JBL J22.vdc", "https://x/jbl.vdc"),
        )
        val parsed = DdcApi.parseIndexJson(DdcApi.indexToJson(entries))
        assertEquals(entries, parsed)
    }

    @Test
    fun `parse index json skips blank rows`() {
        val parsed = DdcApi.parseIndexJson(
            """[{"name":"  ","url":"https://x/a.vdc"},{"name":"JBL J22.vdc","url":"https://x/jbl.vdc"}]""",
        )
        assertEquals(1, parsed.size)
        assertEquals("JBL J22.vdc", parsed[0].name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse index json rejects non-array`() {
        DdcApi.parseIndexJson("""{"name":"x"}""")
    }
}
