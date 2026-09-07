package com.vipjam.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10

data class MeasuredLevel(
    val rms: Float,
    val peak: Float,
    val atUptimeMs: Long,
    val source: String,
)

object LevelBus {
    const val STALE_TIMEOUT_MS = 1500L
    const val FLOOR_DB = -60f

    private val _levels = MutableStateFlow<MeasuredLevel?>(null)
    val levels: StateFlow<MeasuredLevel?> = _levels.asStateFlow()

    fun publish(rms: Float, peak: Float, atUptimeMs: Long, source: String) {
        if (!rms.isFinite() || !peak.isFinite()) return
        if (rms < 0f || peak < 0f) return
        _levels.value = MeasuredLevel(rms, peak, atUptimeMs, source)
    }

    fun clear() {
        _levels.value = null
    }

    fun isLive(level: MeasuredLevel?, nowMs: Long): Boolean {
        if (level == null) return false
        if (!level.rms.isFinite() || !level.peak.isFinite()) return false
        if (level.rms < 0f || level.peak < 0f) return false
        return nowMs - level.atUptimeMs in 0..STALE_TIMEOUT_MS
    }

    fun rmsToDb(rms: Float): Float {
        if (!rms.isFinite() || rms <= 0f) return FLOOR_DB
        return (20f * log10(rms)).coerceAtLeast(FLOOR_DB)
    }

    fun levelFraction(rms: Float): Float {
        if (!rms.isFinite() || rms <= 0f) return 0f
        return ((rmsToDb(rms) - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }
}
