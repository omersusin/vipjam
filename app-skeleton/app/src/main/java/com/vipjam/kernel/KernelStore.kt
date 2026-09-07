package com.vipjam.kernel

import android.content.Context
import android.net.Uri
import com.vipjam.autoeq.AutoEq
import com.vipjam.dsp.VipJamDispatcher
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

enum class KernelKind {
    VDC,
    WAV,
    IRS,
}

data class StagedKernel(
    val fileName: String,
    val displayName: String,
    val kind: KernelKind,
    val sizeBytes: Long,
)

data class WavPcm(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
)

data class VdcCoeffs(
    val c44: FloatArray,
    val c48: FloatArray,
)

data class KernelPcm(
    val samples: FloatArray,
    val sampleRate: Int?,
    val channels: Int,
)

fun kindForName(name: String): KernelKind? {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        "vdc" -> KernelKind.VDC
        "wav" -> KernelKind.WAV
        "irs" -> KernelKind.IRS
        else -> null
    }
}

fun parseVdcText(text: String): Result<VdcCoeffs> = runCatching {
    if (!text.contains("SR_44100:")) {
        val eq = AutoEq.parseParametric(text)
        return parseVdcText(AutoEq.toVdc(eq))
    }
    val c44 = vdcSection(text, "SR_44100:")
        ?: throw IllegalArgumentException("missing SR_44100 section")
    val c48 = vdcSection(text, "SR_48000:")
        ?: throw IllegalArgumentException("missing SR_48000 section")
    require(c44.isNotEmpty()) { "empty SR_44100 section" }
    require(c44.size == c48.size) { "rate section size mismatch" }
    require(c44.size % 5 == 0) { "coefficient count not a multiple of 5" }
    VdcCoeffs(c44, c48)
}

private fun vdcSection(text: String, tag: String): FloatArray? {
    val line = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(tag) }
        ?: return null
    var rest = line.substring(tag.length)
    rest = rest.split("SR_")[0]
    val tokens = rest.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val out = FloatArray(tokens.size)
    for (i in tokens.indices) {
        val v = tokens[i].toDoubleOrNull()
            ?: return null
        require(v.isFinite()) { "non-finite coefficient" }
        out[i] = v.toFloat()
    }
    return out
}

fun parseWavBytes(bytes: ByteArray): Result<WavPcm> = runCatching {
    require(bytes.size >= 44) { "too small for WAV header" }
    require(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) { "missing RIFF tag" }
    require(bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
        bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()) { "missing WAVE tag" }
    var fmtAudio = -1
    var channels = 0
    var rate = 0
    var bits = 0
    var data: ByteArray? = null
    var pos = 12
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val len = leInt(bytes, pos + 4)
        require(len >= 0 && pos + 8 + len <= bytes.size) { "truncated $id chunk" }
        when (id) {
            "fmt " -> {
                require(len >= 16) { "truncated fmt chunk" }
                fmtAudio = leU16(bytes, pos + 8)
                channels = leU16(bytes, pos + 10)
                rate = leInt(bytes, pos + 12)
                bits = leU16(bytes, pos + 22)
            }
            "data" -> {
                data = bytes.sliceArray(pos + 8 until pos + 8 + len)
            }
        }
        pos += 8 + len + (len and 1)
    }
    require(fmtAudio == 1 || fmtAudio == 3) { "unsupported WAV format: $fmtAudio" }
    if (fmtAudio == 1) require(bits == 16) { "unsupported PCM depth: $bits" }
    else require(bits == 32) { "unsupported float depth: $bits" }
    require(channels in 1..8) { "unsupported channel count: $channels" }
    require(rate in 8000..192000) { "unsupported rate: $rate" }
    val raw = data ?: throw IllegalArgumentException("missing data chunk")
    val bytesPerSample = bits / 8
    val stride = channels * bytesPerSample
    require(stride > 0) { "bad stride" }
    val frames = raw.size / stride
    require(frames > 0) { "empty data chunk" }
    require(frames <= VipJamDispatcher.KERNEL_MAX_TOTAL_FLOATS) { "kernel too large: $frames frames" }
    val mono = FloatArray(frames)
    for (f in 0 until frames) {
        var acc = 0.0
        for (ch in 0 until channels) {
            val off = f * stride + ch * bytesPerSample
            val s = if (fmtAudio == 1) {
                leI16(bytes = raw, off = off) / 32768.0
            } else {
                val v = leF32(raw, off).toDouble()
                require(v.isFinite()) { "non-finite float sample" }
                v
            }
            acc += s
        }
        mono[f] = (acc / channels).toFloat()
    }
    WavPcm(mono, rate, channels)
}

private fun leInt(bytes: ByteArray, off: Int): Int {
    return (bytes[off].toInt() and 0xFF) or
        ((bytes[off + 1].toInt() and 0xFF) shl 8) or
        ((bytes[off + 2].toInt() and 0xFF) shl 16) or
        ((bytes[off + 3].toInt() and 0xFF) shl 24)
}

private fun leU16(bytes: ByteArray, off: Int): Int {
    return (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
}

private fun leI16(bytes: ByteArray, off: Int): Int {
    val u = leU16(bytes, off)
    return if (u >= 32768) u - 65536 else u
}

private fun leF32(bytes: ByteArray, off: Int): Float {
    return Float.fromBits(leInt(bytes, off))
}

fun parseIrsBytes(bytes: ByteArray): Result<FloatArray> = runCatching {
    require(bytes.size >= 4) { "empty IRS file" }
    require(bytes.size % 4 == 0) { "IRS size not a multiple of 4" }
    val n = bytes.size / 4
    require(n <= VipJamDispatcher.KERNEL_MAX_TOTAL_FLOATS) { "kernel too large: $n floats" }
    val out = FloatArray(n)
    for (i in 0 until n) {
        val v = leF32(bytes, i * 4)
        require(v.isFinite()) { "non-finite IRS sample at $i" }
        out[i] = v
    }
    out
}

class KernelStore(private val context: Context) {
    fun stage(uri: Uri, displayName: String): Result<StagedKernel> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { ins ->
            readCapped(ins, STAGE_MAX_BYTES)
        } ?: throw IllegalArgumentException("cannot open file")
        require(bytes.isNotEmpty()) { "empty file" }
        writeStaged(displayName, bytes)
    }

    fun stageBytes(displayName: String, bytes: ByteArray): Result<StagedKernel> = runCatching {
        require(bytes.isNotEmpty()) { "empty file" }
        require(bytes.size <= STAGE_MAX_BYTES) { "file too large" }
        writeStaged(displayName, bytes)
    }

    private fun writeStaged(displayName: String, bytes: ByteArray): StagedKernel {
        val kind = kindForName(displayName)
            ?: throw IllegalArgumentException("unsupported file type: $displayName")
        when (kind) {
            KernelKind.VDC -> {
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrThrow()
                parseVdcText(text).getOrThrow()
            }
            KernelKind.WAV -> parseWavBytes(bytes).getOrThrow()
            KernelKind.IRS -> parseIrsBytes(bytes).getOrThrow()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha8 = digest.joinToString("") { String.format(Locale.US, "%02x", it) }.take(8)
        val safe = displayName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(64).ifBlank { "kernel" }
        val fileName = "$sha8-$safe"
        dir().resolve(fileName).writeBytes(bytes)
        return StagedKernel(fileName, displayName.substringAfterLast('/').take(128), kind, bytes.size.toLong())
    }

    fun list(): List<StagedKernel> = runCatching {
        dir().listFiles()?.sortedBy { it.name }?.mapNotNull { f ->
            val kind = kindForName(f.name) ?: return@mapNotNull null
            StagedKernel(f.name, f.name.substringAfter('-').ifBlank { f.name }, kind, f.length())
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun delete(fileName: String): Boolean = runCatching {
        require(fileName.isNotBlank()) { "blank name" }
        require(!fileName.contains('/') && !fileName.contains('\\') && !fileName.contains("..")) { "bad name" }
        val f = dir().resolve(fileName)
        require(f.isFile) { "not found" }
        f.delete()
    }.getOrDefault(false)

    fun readVdcText(fileName: String): Result<String> = runCatching {
        val bytes = readStaged(fileName, TEXT_MAX_BYTES).getOrThrow()
        val text = String(bytes, Charsets.UTF_8)
        require(text.isNotBlank()) { "empty VDC file" }
        parseVdcText(text).getOrThrow()
        text
    }

    fun readKernelPcm(fileName: String): Result<KernelPcm> = runCatching {
        val kind = kindForName(fileName) ?: throw IllegalArgumentException("unsupported file type")
        val bytes = readStaged(fileName, STAGE_MAX_BYTES).getOrThrow()
        when (kind) {
            KernelKind.WAV -> {
                val wav = parseWavBytes(bytes).getOrThrow()
                KernelPcm(wav.samples, wav.sampleRate, wav.channels)
            }
            KernelKind.IRS -> {
                KernelPcm(parseIrsBytes(bytes).getOrThrow(), null, 1)
            }
            KernelKind.VDC -> throw IllegalArgumentException("not a kernel file")
        }
    }

    fun probeMeta(fileName: String, deviceRate: Int): Result<String> = runCatching {
        val kind = kindForName(fileName) ?: throw IllegalArgumentException("unsupported file type")
        val bytes = readStaged(fileName, STAGE_MAX_BYTES).getOrThrow()
        when (kind) {
            KernelKind.VDC -> {
                val text = String(bytes, Charsets.UTF_8)
                val coeffs = parseVdcText(text).getOrThrow()
                "${coeffs.c44.size / 5} filters, dual 44.1/48 kHz"
            }
            KernelKind.WAV -> {
                val wav = parseWavBytes(bytes).getOrThrow()
                val ch = when (wav.channels) {
                    1 -> "mono"
                    2 -> "stereo"
                    else -> "${wav.channels}-ch"
                }
                val base = "${String.format(Locale.US, "%.1f", wav.sampleRate / 1000.0)} kHz, $ch, ${wav.samples.size} frames"
                if (wav.sampleRate != deviceRate) "$base, rate mismatch" else base
            }
            KernelKind.IRS -> {
                val n = parseIrsBytes(bytes).getOrThrow().size
                "$n frames, raw f32 mono, rate unknown"
            }
        }
    }

    private fun readStaged(fileName: String, cap: Long): Result<ByteArray> = runCatching {
        require(!fileName.contains('/') && !fileName.contains('\\') && !fileName.contains("..")) { "bad name" }
        val f = dir().resolve(fileName)
        require(f.isFile) { "not staged" }
        require(f.length() in 1..cap) { "bad staged size" }
        f.inputStream().use { readCapped(it, cap) }
    }

    private fun dir() = context.filesDir.resolve("kernels").apply { mkdirs() }

    private fun readCapped(ins: java.io.InputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw IllegalArgumentException("file too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val STAGE_MAX_BYTES = 32L * 1024L * 1024L
        const val TEXT_MAX_BYTES = 2L * 1024L * 1024L
    }
}
