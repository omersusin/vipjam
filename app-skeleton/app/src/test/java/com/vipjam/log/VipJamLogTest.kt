package com.vipjam.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VipJamLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `levels append with timestamp tag and level`() {
        VipJamLog.init(tmp.root)
        VipJamLog.d("T", "debug-msg")
        VipJamLog.i("T", "info-msg")
        VipJamLog.w("T", "warn-msg")
        VipJamLog.e("T", "error-msg")
        val lines = VipJamLog.readLast(10)
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("D/T: debug-msg"))
        assertTrue(lines[1].contains("I/T: info-msg"))
        assertTrue(lines[2].contains("W/T: warn-msg"))
        assertTrue(lines[3].contains("E/T: error-msg"))
    }

    @Test
    fun `readLast returns tail only`() {
        VipJamLog.init(tmp.root)
        repeat(10) { VipJamLog.i("T", "m$it") }
        val tail = VipJamLog.readLast(3)
        assertEquals(3, tail.size)
        assertTrue(tail[0].endsWith("m7"))
        assertTrue(tail[2].endsWith("m9"))
    }

    @Test
    fun `clear removes log and backup`() {
        VipJamLog.init(tmp.root)
        VipJamLog.i("T", "hello")
        VipJamLog.clear()
        assertEquals(emptyList<String>(), VipJamLog.readLast(10))
    }

    @Test
    fun `rotation keeps size bounded`() {
        VipJamLog.init(tmp.root)
        val big = "x".repeat(64 * 1024)
        repeat(40) { VipJamLog.i("T", big) }
        val log = File(tmp.root, "vipjam.log")
        val backup = File(tmp.root, "vipjam.log.1")
        assertTrue(log.length() <= VipJamLog.MAX_BYTES)
        assertTrue(backup.exists())
        assertTrue(VipJamLog.readLast(5).isNotEmpty())
    }
}
