package com.vipjam.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

enum class RootManager {
    MAGISK,
    KERNELSU,
    APATCH,
    NONE
}

object RootShell {
    suspend fun stream(command: String, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine(onLine)
        }
        process.waitFor()
    }

    suspend fun capture(command: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    suspend fun hasSu(): Boolean = withTimeoutOrNull(10_000) {
        capture("id", 10_000)
    }?.contains("uid=0") == true

    suspend fun detectManager(): RootManager {
        if (!hasSu()) return RootManager.NONE
        if (!capture("command -v magisk", 10_000).isNullOrBlank()) return RootManager.MAGISK
        if (!capture("command -v ksud", 10_000).isNullOrBlank()) return RootManager.KERNELSU
        if (!capture("command -v apd", 10_000).isNullOrBlank()) return RootManager.APATCH
        return RootManager.NONE
    }
}
