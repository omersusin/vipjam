package com.vipjam.autoeq

import android.content.Context
import android.util.LruCache
import org.json.JSONObject
import java.io.File

class AutoEqCache(context: Context) {
    private val baseDir = File(context.filesDir, "autoeq").apply { mkdirs() }
    private val profilesDir = File(baseDir, "profiles").apply { mkdirs() }
    private val memory = LruCache<String, String>(50)

    fun putVdc(key: String, vdc: String) {
        memory.put(key, vdc)
    }

    fun getVdc(key: String): String? = memory.get(key)

    fun saveProfileText(key: String, text: String) {
        memory.put(key, text)
        profileFile(key).writeText(text)
    }

    fun loadProfileText(key: String): String? {
        memory.get(key)?.let { return it }
        val f = profileFile(key)
        if (!f.exists()) return null
        val text = f.readText()
        memory.put(key, text)
        return text
    }

    fun saveMetadata(version: Int, profileCount: Int) {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("profileCount", profileCount)
        File(baseDir, "metadata.json").writeText(obj.toString())
    }

    fun loadMetadata(): JSONObject? {
        val f = File(baseDir, "metadata.json")
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun profileFile(key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(profilesDir, "$safe.txt")
    }
}
