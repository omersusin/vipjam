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

void vj_viper_free(vj_viper *v) { delete v; }

void vj_viper_set_rate(vj_viper *v, uint32_t sampleRate) {
    v->engine.samplingRate = sampleRate;
    v->engine.resetAllEffects();
}

void vj_viper_reset(vj_viper *v) { v->engine.resetAllEffects(); }

void vj_viper_set_stage(vj_viper *v, int stage, int enabled) {
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
    v->engine.process(interleavedStereo, frames);
}

void vj_viper_set_ddc(vj_viper *v, const float *c44, unsigned int n44,
                      const float *c48, unsigned int n48) {
    std::vector<float> a44(c44, c44 + n44), a48(c48, c48 + n48);
    v->engine.viperDdc.SetCoeffs(n44, a44.data(), a48.data());
}

void vj_viper_set_kernel_mono(vj_viper *v, const float *frames,
                              unsigned int len) {
    std::vector<float> k(frames, frames + len);
    v->engine.convolver.SetKernel(k.data(), len);
}

unsigned int vj_viper_kernel_id(vj_viper *v) { return v->lastId; }

void vj_viper_kernel_prepare(vj_viper *v, unsigned int totalFloats,
                             unsigned int channels) {
    v->pendingKernel.clear();
    v->pendingKernel.reserve(totalFloats);
    v->pendingTotal = totalFloats;
    v->pendingChannels = channels;
}

void vj_viper_kernel_append(vj_viper *v, unsigned int totalFloats,
                            const float *buf, unsigned int len) {
    if (totalFloats != v->pendingTotal || buf == 0 || len == 0) return;
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
                            unsigned int crc32, unsigned int kernelId) {
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
    } else {
        unsigned per = totalFloats / 2;
        std::vector<float> left(per), right(per);
        for (unsigned i = 0; i < per; i++) {
            left[i] = v->pendingKernel[i * 2];
            right[i] = v->pendingKernel[i * 2 + 1];
        }
        v->engine.convolver.SetKernelStereo(left.data(), right.data(), per);
    }
    v->lastId = kernelId;
    v->pendingKernel.clear();
    v->pendingTotal = 0;
    v->engine.convolver.Reset();
}
