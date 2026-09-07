package com.vipjam.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VipJamLog {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val NAME = "vipjam.log"
    private const val BACKUP = "vipjam.log.1"
    private val lock = Any()
    @Volatile private var dir: File? = null

    fun init(cacheDir: File) {
        dir = cacheDir
    }

    fun d(tag: String, msg: String) = append("D", tag, msg)
    fun i(tag: String, msg: String) = append("I", tag, msg)
    fun w(tag: String, msg: String) = append("W", tag, msg)
    fun e(tag: String, msg: String) = append("E", tag, msg)

    fun readLast(n: Int): List<String> {
        val f = dir?.let { File(it, NAME) } ?: return emptyList()
        if (n <= 0) return emptyList()
        synchronized(lock) {
            if (!f.exists()) return emptyList()
            val lines = f.readLines()
            return if (lines.size <= n) lines else lines.takeLast(n)
        }
    }

    fun clear() {
        synchronized(lock) {
            dir?.let {
                File(it, NAME).delete()
                File(it, BACKUP).delete()
            }
        }
    }

    private fun append(level: String, tag: String, msg: String) {
        val d = dir ?: return
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts $level/$tag: $msg\n"
        val bytes = line.toByteArray()
        synchronized(lock) {
            try {
                if (!d.exists()) d.mkdirs()
                val f = File(d, NAME)
                if (f.exists() && f.length() + bytes.size > MAX_BYTES) {
                    File(d, BACKUP).delete()
                    f.renameTo(File(d, BACKUP))
                }
                f.appendBytes(bytes)
            } catch (_: Exception) {
            }
        }
    }
}
