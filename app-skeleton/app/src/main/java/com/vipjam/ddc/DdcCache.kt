package com.vipjam.ddc

import android.content.Context
import android.util.LruCache
import java.io.File

class DdcCache(context: Context) {
    private val baseDir = File(context.filesDir, "ddc").apply { mkdirs() }
    private val libraryDir = File(baseDir, "library").apply { mkdirs() }
    private val memory = LruCache<String, String>(50)

    fun saveVdcText(name: String, text: String) {
        memory.put(keyOf(name), text)
        entryFile(name).writeText(text)
    }

    fun loadVdcText(name: String): String? {
        memory.get(keyOf(name))?.let { return it }
        val f = entryFile(name)
        if (!f.exists()) return null
        val text = f.readText()
        memory.put(keyOf(name), text)
        return text
    }

    fun cachedNames(): Set<String> {
        val names = LinkedHashSet<String>()
        libraryDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.lowercase().endsWith(".vdc")) names.add(f.name)
        }
        return names
    }

    fun saveIndex(entries: List<DdcEntry>) {
        File(baseDir, "index.json").writeText(DdcApi.indexToJson(entries))
    }

    fun loadIndex(): List<DdcEntry>? {
        val f = File(baseDir, "index.json")
        if (!f.exists()) return null
        return try {
            DdcApi.parseIndexJson(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun keyOf(name: String): String = name.trim().lowercase()

    private fun entryFile(name: String): File {
        val safe = name.substringAfterLast('/').trim().ifBlank { "correction.vdc" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val withExt = if (safe.lowercase().endsWith(".vdc")) safe else "$safe.vdc"
        return File(libraryDir, withExt)
    }
}
