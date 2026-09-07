package com.vipjam.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface FlashEvent {
    data class Log(val line: String) : FlashEvent
    data class Finished(val ok: Boolean, val needsReboot: Boolean) : FlashEvent
}

fun qRoot(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun flashCommand(manager: RootManager, zipPath: String): String = when (manager) {
    RootManager.MAGISK -> "magisk --install-module ${qRoot(zipPath)}"
    RootManager.KERNELSU -> "ksud module install ${qRoot(zipPath)}"
    RootManager.APATCH -> "apd module install ${qRoot(zipPath)}"
    RootManager.NONE -> "magisk --install-module ${qRoot(zipPath)}"
}

fun parseModuleProp(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
    }
    return out
}

class ModuleFlasher(
    private val runShell: suspend (String, (String) -> Unit) -> Int = { cmd, onLine ->
        RootShell.stream(cmd, onLine)
    },
    private val runCapture: suspend (String) -> String? = { cmd ->
        RootShell.capture(cmd, 10_000)
    },
    private val resolveManager: suspend () -> RootManager = {
        RootShell.detectManager()
    }
) {
    fun flash(zip: File): Flow<FlashEvent> = channelFlow {
        try {
            val manager = runCatching { resolveManager() }.getOrNull() ?: RootManager.NONE
            if (manager == RootManager.NONE) {
                send(FlashEvent.Finished(false, false))
                return@channelFlow
            }
            val command = flashCommand(manager, zip.absolutePath)
            send(FlashEvent.Log(command))
            val exit = runCatching {
                runShell(command) { line -> trySend(FlashEvent.Log(line)) }
            }.getOrElse {
                trySend(FlashEvent.Log(it.message ?: it.javaClass.simpleName))
                send(FlashEvent.Finished(false, false))
                return@channelFlow
            }
            send(FlashEvent.Finished(exit == 0, exit == 0))
        } catch (e: Exception) {
            trySend(FlashEvent.Log(e.message ?: e.javaClass.simpleName))
            send(FlashEvent.Finished(false, false))
        }
    }

    suspend fun reboot() {
        runCatching {
            runShell("svc power reboot || reboot") {}
        }
    }

    suspend fun readInstalledProp(): Map<String, String>? = withContext(Dispatchers.IO) {
        val text = runCatching { runCapture("cat /data/adb/modules/vipjam/module.prop") }.getOrNull()
        if (text.isNullOrBlank()) null else parseModuleProp(text)
    }
}
