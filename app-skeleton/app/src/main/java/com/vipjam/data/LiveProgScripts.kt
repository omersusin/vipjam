package com.vipjam.data

object LiveProgScripts {
    fun validate(text: String): List<String> {
        val errors = mutableListOf<String>()
        if ("@init" !in text) errors += "missing @init section"
        if ("@sample" !in text) errors += "missing @sample section"
        val pairs = listOf('(' to ')', '[' to ']', '{' to '}')
        for ((open, close) in pairs) {
            var depth = 0
            for (ch in text) {
                if (ch == open) depth++
                if (ch == close) depth--
                if (depth < 0) break
            }
            if (depth != 0) errors += "unbalanced $open$close"
        }
        return errors
    }
}
