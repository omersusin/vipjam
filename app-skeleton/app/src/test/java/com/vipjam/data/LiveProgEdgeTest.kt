package com.vipjam.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProgEdgeTest {
    @Test
    fun `empty text misses both sections`() {
        val errors = LiveProgScripts.validate("")
        assertTrue(errors.any { it.contains("@init") })
        assertTrue(errors.any { it.contains("@sample") })
    }

    @Test
    fun `only init misses sample`() {
        val errors = LiveProgScripts.validate("@init\nx=1;")
        assertEquals(listOf("missing @sample section"), errors)
    }

    @Test
    fun `only sample misses init`() {
        val errors = LiveProgScripts.validate("@sample\ny=x;")
        assertEquals(listOf("missing @init section"), errors)
    }

    @Test
    fun `unbalanced close bracket caught`() {
        assertTrue(LiveProgScripts.validate("@init\n@sample\nfoo);").any { it.contains("unbalanced ()") })
        assertTrue(LiveProgScripts.validate("@init\n@sample\nfoo];").any { it.contains("unbalanced []") })
        assertTrue(LiveProgScripts.validate("@init\n@sample\nfoo};").any { it.contains("unbalanced {}") })
    }

    @Test
    fun `unbalanced open bracket caught`() {
        assertTrue(LiveProgScripts.validate("@init\n@sample\nfoo(bar;").any { it.contains("unbalanced ()") })
        assertTrue(LiveProgScripts.validate("@init\n@sample\nfoo[bar;").any { it.contains("unbalanced []") })
        assertTrue(LiveProgScripts.validate("@init\n@sample\nfoo{bar;").any { it.contains("unbalanced {}") })
    }

    @Test
    fun `balanced nested brackets pass`() {
        assertTrue(LiveProgScripts.validate("@init\nx=(1+[2*{3}]);\n@sample\ny=x;").isEmpty())
    }

    @Test
    fun `markers inside words count`() {
        assertTrue(LiveProgScripts.validate("x@init y\nz@sample w").isEmpty())
    }
}
