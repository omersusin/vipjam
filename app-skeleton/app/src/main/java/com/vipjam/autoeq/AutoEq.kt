package com.vipjam.autoeq

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterType {
    PK, LSC, HSC
}

data class AutoEqFilter(
    val type: FilterType,
    val fcHz: Double,
    val gainDb: Double,
    val q: Double,
)

data class ParametricEq(
    val preampDb: Double,
    val filters: List<AutoEqFilter>,
)

object AutoEq {
    const val BASE_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master"
    const val USER_AGENT = "vipjam-autoeq/1.0"
    const val TIMEOUT_MS = 30000
    const val MAX_BYTES = 1024 * 1024

    private val PREAMP_RE = Regex("""^Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB\s*$""")
    private val FILTER_RE = Regex(
        """^Filter\s+(\d+):\s+(ON|OFF)\s+(LSC|HSC|PK)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun parseParametric(text: String): ParametricEq {
        require(text.isNotBlank()) { "empty AutoEq text" }
        var preamp: Double? = null
        val filters = mutableListOf<AutoEqFilter>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val pm = PREAMP_RE.matchEntire(line)
            if (pm != null) {
                require(preamp == null) { "duplicate Preamp line" }
                val v = pm.groupValues[1].toDoubleOrNull()
                    ?: throw IllegalArgumentException("bad Preamp value: $line")
                require(v.isFinite()) { "non-finite Preamp: $line" }
                preamp = v
                continue
            }
            val fm = FILTER_RE.matchEntire(line)
                ?: throw IllegalArgumentException("malformed line: $line")
            if (!fm.groupValues[2].equals("ON", ignoreCase = true)) continue
            val type = FilterType.valueOf(fm.groupValues[3].uppercase())
            val fc = fm.groupValues[4].toDoubleOrNull()
                ?: throw IllegalArgumentException("bad Fc: $line")
            val gain = fm.groupValues[5].toDoubleOrNull()
                ?: throw IllegalArgumentException("bad Gain: $line")
            val q = fm.groupValues[6].toDoubleOrNull()
                ?: throw IllegalArgumentException("bad Q: $line")
            require(fc.isFinite() && fc > 0.0) { "bad Fc: $line" }
            require(gain.isFinite()) { "bad Gain: $line" }
            require(q.isFinite() && q > 0.0) { "bad Q: $line" }
            filters += AutoEqFilter(type, fc, gain, q)
        }
        val p = requireNotNull(preamp) { "missing Preamp: line" }
        require(filters.isNotEmpty()) { "no ON LSC/PK/HSC filters" }
        return ParametricEq(p, filters)
    }

    fun rbjBiquad(type: FilterType, f0Hz: Double, gainDb: Double, q: Double, sampleRate: Int): DoubleArray {
        val qq = if (q > 0.0) q else 0.7071
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0Hz / sampleRate.toDouble()
        val cosw = cos(w0)
        val sinw = sin(w0)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        if (type == FilterType.PK) {
            val alpha = sinw / (2.0 * qq)
            b0 = 1.0 + alpha * a
            b1 = -2.0 * cosw
            b2 = 1.0 - alpha * a
            a0 = 1.0 + alpha / a
            a1 = -2.0 * cosw
            a2 = 1.0 - alpha / a
        } else {
            val s = 1.0
            val alpha = sinw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0)
            val beta = 2.0 * sqrt(a) * alpha
            if (type == FilterType.LSC) {
                b0 = a * ((a + 1.0) - (a - 1.0) * cosw + beta)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosw)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosw - beta)
                a0 = (a + 1.0) + (a - 1.0) * cosw + beta
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosw)
                a2 = (a + 1.0) + (a - 1.0) * cosw - beta
            } else {
                b0 = a * ((a + 1.0) + (a - 1.0) * cosw + beta)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosw)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosw - beta)
                a0 = (a + 1.0) - (a - 1.0) * cosw + beta
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosw)
                a2 = (a + 1.0) - (a - 1.0) * cosw - beta
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, -a1 / a0, -a2 / a0)
    }

    fun toVdc(eq: ParametricEq, sampleRates: List<Int> = listOf(44100, 48000)): String {
        require(sampleRates.isNotEmpty()) { "sampleRates must be non-empty" }
        val sb = StringBuilder()
        for (fs in sampleRates) {
            require(fs > 0) { "bad sample rate: $fs" }
            val sos = ArrayList<Double>(eq.filters.size * 5)
            for (f in eq.filters) {
                val c = rbjBiquad(f.type, f.fcHz, f.gainDb, f.q, fs)
                for (v in c) sos.add(v)
            }
            sb.append("SR_").append(fs).append(':')
            sb.append(sos.joinToString(",") { String.format(Locale.US, "%.9g", it) })
            sb.append('\n')
        }
        return sb.toString()
    }

    fun validateVdc(text: String): Boolean {
        if (!text.contains("SR_44100:") || !text.contains("SR_48000:")) return false
        val a = vdcSection(text, "SR_44100:") ?: return false
        val b = vdcSection(text, "SR_48000:") ?: return false
        if (a.size != b.size) return false
        if (a.isEmpty() || a.size % 5 != 0) return false
        return true
    }

    private fun vdcSection(text: String, tag: String): List<Double>? {
        val firstLine = text.substringAfter(tag).lineSequence().firstOrNull() ?: return null
        val cut = firstLine.split("SR_")[0]
        val tokens = cut.replace(';', ',').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val vals = ArrayList<Double>(tokens.size)
        for (t in tokens) {
            val v = t.toDoubleOrNull() ?: return null
            if (!v.isFinite()) return null
            vals.add(v)
        }
        return vals
    }
}

class AutoEqDownloader(
    private val baseUrl: String = AutoEq.BASE_URL,
) {
    fun profileUrl(relativePath: String): String = "$baseUrl/$relativePath"

    fun fetchText(urlString: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val opened = URL(urlString).openConnection() as? HttpURLConnection ?: return null
            conn = opened
            conn.connectTimeout = AutoEq.TIMEOUT_MS
            conn.readTimeout = AutoEq.TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", AutoEq.USER_AGENT)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val out = ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > AutoEq.MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
            }
            if (out.size() == 0) return null
            return String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }
}
