package com.vipjam.ui

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

object EqCurveMath {
    val BAND_FREQS_HZ = floatArrayOf(
        31f, 62f, 125f, 250f, 500f,
        1000f, 2000f, 4000f, 8000f, 16000f,
    )

    const val MIN_FREQ_HZ = 20f
    const val MAX_FREQ_HZ = 20000f
    const val MAX_DB = 12f

    private val logMin = ln(MIN_FREQ_HZ)
    private val logSpan = ln(MAX_FREQ_HZ) - ln(MIN_FREQ_HZ)

    fun freqToXNorm(freqHz: Float): Float {
        val f = freqHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
        return ((ln(f) - logMin) / logSpan).coerceIn(0f, 1f)
    }

    fun xNormToFreqHz(xNorm: Float): Float {
        val x = xNorm.coerceIn(0f, 1f)
        return exp(logMin + x * logSpan)
    }

    fun dbToYNorm(db: Float): Float =
        ((MAX_DB - db) / (2f * MAX_DB)).coerceIn(0f, 1f)

    fun yNormToDb(yNorm: Float): Float {
        val y = yNorm.coerceIn(0f, 1f)
        return MAX_DB - y * 2f * MAX_DB
    }

    fun freqToX(freqHz: Float, widthPx: Float, padLeftPx: Float, padRightPx: Float): Float =
        padLeftPx + freqToXNorm(freqHz) * (widthPx - padLeftPx - padRightPx)

    fun dbToY(db: Float, heightPx: Float, padTopPx: Float, padBottomPx: Float): Float =
        padTopPx + dbToYNorm(db) * (heightPx - padTopPx - padBottomPx)

    fun xToFreqHz(xPx: Float, widthPx: Float, padLeftPx: Float, padRightPx: Float): Float {
        val span = (widthPx - padLeftPx - padRightPx).coerceAtLeast(1f)
        return xNormToFreqHz(((xPx - padLeftPx) / span).coerceIn(0f, 1f))
    }

    fun yToDb(yPx: Float, heightPx: Float, padTopPx: Float, padBottomPx: Float): Float {
        val span = (heightPx - padTopPx - padBottomPx).coerceAtLeast(1f)
        return yNormToDb(((yPx - padTopPx) / span).coerceIn(0f, 1f))
    }

    fun clampDb(db: Float): Float = db.coerceIn(-MAX_DB, MAX_DB)

    fun bandFreqHz(index: Int): Float =
        BAND_FREQS_HZ.getOrElse(index) { MAX_FREQ_HZ }

    fun nearestBand(
        touchX: Float,
        widthPx: Float,
        padLeftPx: Float,
        padRightPx: Float,
        bandCount: Int,
    ): Int {
        if (bandCount <= 0) return 0
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in 0 until bandCount) {
            val x = freqToX(bandFreqHz(i), widthPx, padLeftPx, padRightPx)
            val d = kotlin.math.abs(x - touchX)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    fun shortFreqLabel(hz: Float): String {
        if (hz < 1000f) return hz.roundToInt().toString()
        val k = hz / 1000f
        return if (k == k.roundToInt().toFloat()) "${k.roundToInt()}k"
        else "${"%.1f".format(k)}k"
    }
}
