#include "viper_bridge.h"
#include "VipJamChain.h"
#include "viper/ViPER.h"
#include "viper/effects/FETCompressor.h"

struct vj_viper {
    ViPER engine;
    std::vector<float> pendingKernel;
    unsigned int pendingTotal;
    unsigned int pendingChannels;
    unsigned int lastCrc;
    unsigned int lastId;
};

vj_viper *vj_viper_create(uint32_t sampleRate) {
    vj_viper *v = new vj_viper();
    v->engine.samplingRate = sampleRate;
    v->pendingTotal = 0;
    v->pendingChannels = 0;
    v->lastCrc = 0;
    v->lastId = 0;
    return v;
}

void vj_viper_free(vj_viper *v) { if (v) delete v; }

void vj_viper_set_rate(vj_viper *v, uint32_t sampleRate) {
    if (!v) return;
    if (sampleRate < 8000 || sampleRate > 192000) return;
    v->engine.samplingRate = sampleRate;
    v->engine.resetAllEffects();
}

void vj_viper_reset(vj_viper *v) { if (v) v->engine.resetAllEffects(); }

void vj_viper_set_stage(vj_viper *v, int stage, int enabled) {
    if (!v) return;
    bool on = enabled != 0;
    switch (stage) {
    case VJ_STAGE_VIPER_CONV: v->engine.convolver.SetEnable(on); break;
    case VJ_STAGE_VIPER_VHE: v->engine.vhe.SetEnable(on); break;
    case VJ_STAGE_VIPER_DDC: v->engine.viperDdc.SetEnable(on); break;
    case VJ_STAGE_VIPER_SPECTRUM: v->engine.spectrumExtend.SetEnable(on); break;
    case VJ_STAGE_VIPER_IIR: v->engine.iirFilter.SetEnable(on); break;
    case VJ_STAGE_VIPER_COLOR: v->engine.colorfulMusic.SetEnable(on); break;
    case VJ_STAGE_VIPER_DIFF: v->engine.diffSurround.SetEnable(on); break;
    case VJ_STAGE_VIPER_REVERB: v->engine.reverberation.SetEnable(on); break;
    case VJ_STAGE_VIPER_SPK: v->engine.speakerCorrection.SetEnable(on); break;
    case VJ_STAGE_VIPER_PGC: v->engine.playbackGain.SetEnable(on); break;
    case VJ_STAGE_VIPER_FET:
        v->engine.fetCompressor.SetParameter(FETCompressor::ENABLE,
                                             on ? 1.0f : 0.0f);
        break;
    case VJ_STAGE_VIPER_DYNSYS: v->engine.dynamicSystem.SetEnable(on); break;
    case VJ_STAGE_VIPER_BASS: v->engine.viperBass.SetEnable(on); break;
    case VJ_STAGE_VIPER_CLARITY: v->engine.viperClarity.SetEnable(on); break;
    case VJ_STAGE_VIPER_CURE: v->engine.cure.SetEnable(on); break;
    case VJ_STAGE_VIPER_TUBE: v->engine.tubeSimulator.SetEnable(on); break;
    case VJ_STAGE_VIPER_ANALOGX: v->engine.analogX.SetEnable(on); break;
    default: break;
    }
}

void vj_viper_process(vj_viper *v, std::vector<float> &interleavedStereo,
                      uint32_t frames) {
    if (!v || frames == 0) return;
    v->engine.process(interleavedStereo, frames);
}

void vj_viper_set_ddc(vj_viper *v, const float *c44, unsigned int n44,
                      const float *c48, unsigned int n48) {
    if (!v || !c44 || !c48 || n44 == 0 || n48 == 0) return;
    if (n44 > 1024 || n48 > 1024 || n44 != n48) return;
    std::vector<float> a44(c44, c44 + n44), a48(c48, c48 + n48);
    v->engine.viperDdc.SetCoeffs(n44, a44.data(), a48.data());
}

void vj_viper_set_kernel_mono(vj_viper *v, const float *frames,
                              unsigned int len) {
    if (!v || !frames || len == 0 || len > 4194304) return;
    std::vector<float> k(frames, frames + len);
    v->engine.convolver.SetKernel(k.data(), len);
}

unsigned int vj_viper_kernel_id(vj_viper *v) { return v ? v->lastId : 0; }

void vj_viper_kernel_prepare(vj_viper *v, unsigned int totalFloats,
                             unsigned int channels) {
    if (!v) return;
    if (totalFloats == 0 && channels == 0) {
        v->pendingKernel.clear();
        v->pendingTotal = 0;
        v->pendingChannels = 0;
        v->lastCrc = 0;
        v->lastId = 0;
        return;
    }
    if (totalFloats == 0 || totalFloats > 4194304) return;
    if (channels != 1 && channels != 2) return;
    v->pendingKernel.clear();
    v->pendingKernel.reserve(totalFloats);
    v->pendingTotal = totalFloats;
    v->pendingChannels = channels;
}

void vj_viper_kernel_append(vj_viper *v, unsigned int totalFloats,
                            const float *buf, unsigned int len) {
    if (!v) return;
    if (totalFloats != v->pendingTotal || buf == 0 || len == 0) return;
    if (v->pendingKernel.size() + len > v->pendingTotal) return;
    v->pendingKernel.insert(v->pendingKernel.end(), buf, buf + len);
}

static unsigned vj_crc32(const unsigned char *data, unsigned len) {
    static unsigned table[256];
    static int ready = 0;
    if (!ready) {
        for (unsigned i = 0; i < 256; i++) {
            unsigned c = i;
            for (int k = 0; k < 8; k++)
                c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            table[i] = c;
        }
        ready = 1;
    }
    unsigned crc = 0xFFFFFFFFu;
    for (unsigned i = 0; i < len; i++)
        crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return crc ^ 0xFFFFFFFFu;
}

void vj_viper_kernel_commit(vj_viper *v, unsigned int totalFloats,
                            unsigned int crc32, unsigned int kernelId) {    if (!v) return;
    if (v->pendingKernel.size() != totalFloats || totalFloats == 0) {
        v->pendingKernel.clear();
        v->pendingTotal = 0;
        return;
    }
    unsigned calc =
        vj_crc32(reinterpret_cast<const unsigned char *>(v->pendingKernel.data()),
                 totalFloats * 4);
    if (calc != crc32 || calc == v->lastCrc) {
        v->pendingKernel.clear();
        v->pendingTotal = 0;
        return;
    }
    v->lastCrc = calc;
    if (v->pendingChannels == 1) {
        v->engine.convolver.SetKernel(v->pendingKernel.data(), totalFloats);
    } else if (v->pendingChannels == 2) {
        if (totalFloats % 2 != 0) {
            v->pendingKernel.clear();
            v->pendingTotal = 0;
            return;
        }
        unsigned per = totalFloats / 2;
        std::vector<float> left(per), right(per);
        for (unsigned i = 0; i < per; i++) {
            left[i] = v->pendingKernel[i * 2];
            right[i] = v->pendingKernel[i * 2 + 1];
        }
        v->engine.convolver.SetKernelStereo(left.data(), right.data(), per);
    } else {
        v->pendingKernel.clear();
        v->pendingTotal = 0;
        return;
    }
    v->lastId = kernelId;
    v->pendingKernel.clear();
    v->pendingTotal = 0;
    v->engine.convolver.Reset();
}

static float vj_clamp(float v, float lo, float hi) {
    if (!(v >= lo)) return lo;
    if (!(v <= hi)) return hi;
    return v;
}

static float vj_clamp_finite(float v, float lo, float hi) {
    if (!(v >= lo && v <= hi)) {
        if (!(v > lo)) return lo;
        return hi;
    }
    return v;
}

void vj_viper_set_eq_band(vj_viper *v, unsigned int band, float levelDb) {
    if (!v) return;
    if (band >= 10) return;
    v->engine.iirFilter.SetBandLevel(band, vj_clamp(levelDb, -12.0f, 12.0f));
}

void vj_viper_set_bass(vj_viper *v, int mode, float factor) {
    if (!v) return;
    if (mode < 0) mode = 0;
    if (mode > 2) mode = 2;
    v->engine.viperBass.SetProcessMode((ViPERBass::ProcessMode)mode);
    v->engine.viperBass.SetBassFactor(vj_clamp_finite(factor, 0.0f, 10.0f));
}

void vj_viper_set_reverb(vj_viper *v, float room, float width, float damp,
                         float wet, float dry) {
    if (!v) return;
    v->engine.reverberation.SetRoomSize(vj_clamp(room, 0.0f, 100.0f) / 100.0f);
    v->engine.reverberation.SetWidth(vj_clamp(width, 0.0f, 100.0f) / 100.0f);
    v->engine.reverberation.SetDamp(vj_clamp(damp, 0.0f, 100.0f) / 100.0f);
    v->engine.reverberation.SetWet(vj_clamp(wet, 0.0f, 100.0f) / 100.0f);
    v->engine.reverberation.SetDry(vj_clamp(dry, 0.0f, 100.0f) / 100.0f);
}

void vj_viper_set_clarity(vj_viper *v, int mode, float gain) {
    if (!v) return;
    if (mode < 0) mode = 0;
    if (mode > 2) mode = 2;
    v->engine.viperClarity.SetProcessMode((ViPERClarity::ClarityMode)mode);
    v->engine.viperClarity.SetClarity(vj_clamp(gain, 0.0f, 100.0f) / 100.0f);
}

void vj_viper_set_reverb3(vj_viper *v, float room, float width, float damp) {
    if (!v) return;
    v->engine.reverberation.SetRoomSize(vj_clamp(room, 0.0f, 100.0f) / 100.0f);
    v->engine.reverberation.SetWidth(vj_clamp(width, 0.0f, 100.0f) / 100.0f);
    v->engine.reverberation.SetDamp(vj_clamp(damp, 0.0f, 100.0f) / 100.0f);
}

void vj_viper_set_fet(vj_viper *v, int param, float value) {
    if (!v) return;
    if (param < 0 || param > 16) return;
    v->engine.fetCompressor.SetParameter((FETCompressor::Parameter)param,
                                         vj_clamp_finite(value, -100.0f, 100.0f));
}

void vj_viper_set_analogx(vj_viper *v, int mode) {
    if (!v) return;
    if (mode < 0) mode = 0;
    if (mode > 3) mode = 3;
    v->engine.analogX.SetProcessingModel(mode);
}
