package com.vipjam.kernel

import com.vipjam.dsp.VipJamDispatcher

data class BulkStep(
    val id: Int,
    val values: FloatArray,
    val index: Int = -1,
    val v0: Int = 0,
    val v1: Int = 0,
    val v2: Int = 0,
)

data class ConvPush(
    val steps: List<BulkStep>,
    val totalFloats: Int,
    val channels: Int,
    val crc32: Int,
    val sampleRate: Int?,
    val rateMismatch: Boolean,
)

fun ddcStep(vdcText: String): Result<BulkStep> = runCatching {
    val coeffs = parseVdcText(vdcText).getOrThrow()
    val combined = coeffs.c44 + coeffs.c48
    VipJamDispatcher.buildBulkParam(VipJamDispatcher.DDC_NEW, combined)
    BulkStep(VipJamDispatcher.DDC_NEW, combined)
}

fun convPush(
    samples: FloatArray,
    channels: Int,
    sampleRate: Int?,
    deviceRate: Int,
    chunkSize: Int = VipJamDispatcher.KERNEL_MAX_FLOATS_PER_CHUNK,
    kernelId: Int = 0,
    resetFlag: Int = 1,
): Result<ConvPush> = runCatching {
    require(channels == 1 || channels == 2) { "unsupported channel count: $channels" }
    val total = samples.size
    VipJamDispatcher.buildKernelPrepare(total, channels, resetFlag)
    val crc = VipJamDispatcher.crc32IEEE(VipJamDispatcher.encodeFloatArrayLE(samples))
    VipJamDispatcher.buildKernelCommit(total, crc, kernelId)
    val chunks = VipJamDispatcher.chunkFloats(samples, chunkSize)
    val steps = ArrayList<BulkStep>(chunks.size + 2)
    steps.add(
        BulkStep(
            VipJamDispatcher.CONV_PREP_NEW,
            floatArrayOf(),
            v0 = total,
            v1 = channels,
            v2 = resetFlag,
        ),
    )
    chunks.forEachIndexed { i, chunk ->
        VipJamDispatcher.buildKernelChunk(i, chunk)
        steps.add(BulkStep(VipJamDispatcher.CONV_CHUNK_NEW, chunk, i, v0 = i))
    }
    steps.add(
        BulkStep(
            VipJamDispatcher.CONV_COMMIT_NEW,
            floatArrayOf(),
            v0 = total,
            v1 = crc,
            v2 = kernelId,
        ),
    )
    ConvPush(
        steps = steps,
        totalFloats = total,
        channels = channels,
        crc32 = crc,
        sampleRate = sampleRate,
        rateMismatch = sampleRate != null && sampleRate != deviceRate,
    )
}
