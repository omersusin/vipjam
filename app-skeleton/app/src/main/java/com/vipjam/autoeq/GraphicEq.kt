package com.vipjam.autoeq

import kotlin.math.ln

object GraphicEq {
    const val MAX_DB = 12.0

    fun parse(text: String): List<Pair<Double, Double>> {
        require(text.isNotBlank()) { "empty GraphicEQ text" }
        val body = text.substringAfter("GraphicEQ:", missingDelimiterValue = "")
        require(body.isNotEmpty()) { "missing GraphicEQ: prefix" }
        val flat = body.replace('\n', ';').replace('\r', ';')
        val points = ArrayList<Pair<Double, Double>>()
        for (raw in flat.split(';')) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            val parts = token.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            require(parts.size == 2) { "malformed pair: $token" }
            val freq = parts[0].toDoubleOrNull()
                ?: throw IllegalArgumentException("bad freq: $token")
            val gain = parts[1].toDoubleOrNull()
                ?: throw IllegalArgumentException("bad gain: $token")
            require(freq.isFinite() && freq > 0.0) { "bad freq: $token" }
            require(gain.isFinite()) { "bad gain: $token" }
            points.add(Pair(freq, gain))
        }
        require(points.isNotEmpty()) { "no freq/gain pairs" }
        points.sortBy { it.first }
        return points
    }

    fun sample(points: List<Pair<Double, Double>>, freqHz: Double): Double {
        require(points.isNotEmpty()) { "empty points" }
        require(freqHz.isFinite() && freqHz > 0.0) { "bad freqHz: $freqHz" }
        if (freqHz <= points.first().first) return points.first().second.coerceIn(-MAX_DB, MAX_DB)
        if (freqHz >= points.last().first) return points.last().second.coerceIn(-MAX_DB, MAX_DB)
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].first < freqHz) lo = mid else hi = mid
        }
        val f0 = points[lo].first
        val g0 = points[lo].second
        val f1 = points[hi].first
        val g1 = points[hi].second
        val t = (ln(freqHz) - ln(f0)) / (ln(f1) - ln(f0))
        return (g0 + t * (g1 - g0)).coerceIn(-MAX_DB, MAX_DB)
    }

    fun sampleBands(points: List<Pair<Double, Double>>, bandFreqsHz: FloatArray): List<Double> =
        bandFreqsHz.map { sample(points, it.toDouble()) }
}
