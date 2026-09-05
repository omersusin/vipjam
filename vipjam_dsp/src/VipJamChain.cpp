#include "VipJamChain.h"
#include "james_bridge.h"
#include "viper_bridge.h"

VipJamChain::VipJamChain() : samplingRate_(44100), limiterGate_(0.999999f) {
    for (int i = 0; i < VJ_STAGE_COUNT; i++) enabled_[i] = false;
    jdsp_ = vj_james_create(samplingRate_);
    viper_ = vj_viper_create(samplingRate_);
}

VipJamChain::~VipJamChain() {
    vj_james_free(static_cast<vj_james_t *>(jdsp_));
    vj_viper_free(static_cast<vj_viper *>(viper_));
}

void VipJamChain::setSamplingRate(uint32_t rate) {
    samplingRate_ = rate;
    vj_james_set_rate(static_cast<vj_james_t *>(jdsp_), rate);
    vj_viper_set_rate(static_cast<vj_viper *>(viper_), rate);
}

bool VipJamChain::anyJamesStageOn() const {
    for (int s = VJ_STAGE_JAMES_TUBE; s <= VJ_STAGE_JAMES_REVERB; s++)
        if (enabled_[s]) return true;
    return false;
}

static bool anyViperStageOn(const bool *enabled) {
    for (int s = VJ_STAGE_VIPER_CONV; s <= VJ_STAGE_VIPER_ANALOGX; s++)
        if (enabled[s]) return true;
    return false;
}

void VipJamChain::applyJamesStage(vj_stage_t stage, bool enabled) {
    vj_james_set_stage(static_cast<vj_james_t *>(jdsp_), static_cast<int>(stage),
                       enabled ? 1 : 0);
}

void VipJamChain::applyViperStage(vj_stage_t stage, bool enabled) {
    vj_viper_set_stage(static_cast<vj_viper *>(viper_), static_cast<int>(stage),
                       enabled ? 1 : 0);
}

void VipJamChain::setStageEnabled(vj_stage_t stage, bool enabled) {
    if (stage < 0 || stage >= VJ_STAGE_COUNT) return;
    enabled_[stage] = enabled;
    if (stage <= VJ_STAGE_JAMES_REVERB) applyJamesStage(stage, enabled);
    else if (stage < VJ_STAGE_LIMITER) applyViperStage(stage, enabled);
}

bool VipJamChain::isStageEnabled(vj_stage_t stage) const {
    if (stage < 0 || stage >= VJ_STAGE_COUNT) return false;
    return enabled_[stage];
}

int VipJamChain::loadDDC(const char *vdcText) {
    return vj_james_load_ddc(static_cast<vj_james_t *>(jdsp_), vdcText);
}

int VipJamChain::loadIR(const float *frames, unsigned int channels,
                        unsigned int len) {
    return vj_james_load_ir(static_cast<vj_james_t *>(jdsp_), frames,
                            channels, len);
}

void VipJamChain::viperSetDDC(const float *c44, unsigned int n44,
                              const float *c48, unsigned int n48) {
    vj_viper_set_ddc(static_cast<vj_viper *>(viper_), c44, n44, c48, n48);
}

void VipJamChain::viperSetKernelMono(const float *frames, unsigned int len) {
    vj_viper_set_kernel_mono(static_cast<vj_viper *>(viper_), frames, len);
}

void VipJamChain::viperKernelPrepare(unsigned int totalFloats,
                                     unsigned int channels) {
    vj_viper_kernel_prepare(static_cast<vj_viper *>(viper_), totalFloats,
                            channels);
}

void VipJamChain::viperKernelAppend(unsigned int totalFloats, const float *buf,
                                    unsigned int len) {
    vj_viper_kernel_append(static_cast<vj_viper *>(viper_), totalFloats, buf,
                           len);
}

void VipJamChain::viperKernelCommit(unsigned int totalFloats,
                                    unsigned int crc32, unsigned int kernelId) {
    vj_viper_kernel_commit(static_cast<vj_viper *>(viper_), totalFloats,
                           crc32, kernelId);
}

unsigned int VipJamChain::viperKernelID() const {
    return vj_viper_kernel_id(static_cast<vj_viper *>(viper_));
}

void VipJamChain::reset() {
    limiterGate_ = 0.999999f;
    vj_viper_reset(static_cast<vj_viper *>(viper_));
    for (int s = 0; s < VJ_STAGE_COUNT; s++)
        if (enabled_[s]) setStageEnabled(static_cast<vj_stage_t>(s), true);
}

void VipJamChain::deinterleave(const float *in, float *left, float *right,
                               uint32_t frames) {
    for (uint32_t i = 0; i < frames; i++) {
        left[i] = in[i * 2];
        right[i] = in[i * 2 + 1];
    }
}

void VipJamChain::interleave(const float *left, const float *right, float *out,
                             uint32_t frames) {
    for (uint32_t i = 0; i < frames; i++) {
        out[i * 2] = left[i];
        out[i * 2 + 1] = right[i];
    }
}

void VipJamChain::process(std::vector<float> &interleavedStereo) {
    uint32_t frames =
        static_cast<uint32_t>(interleavedStereo.size() / 2);
    if (frames == 0) return;
    if (anyJamesStageOn()) {
        vj_james_process(static_cast<vj_james_t *>(jdsp_),
                         interleavedStereo.data(), frames);
    }
    if (anyViperStageOn(enabled_)) {
        vj_viper_process(static_cast<vj_viper *>(viper_), interleavedStereo,
                         frames);
    }
    for (uint32_t i = 0; i < interleavedStereo.size(); i++) {
        float v = interleavedStereo[i];
        if (enabled_[VJ_STAGE_LIMITER]) {
            if (v > limiterGate_) v = limiterGate_;
            else if (v < -limiterGate_) v = -limiterGate_;
        }
        if (v > 1.0f) v = 1.0f;
        else if (v < -1.0f) v = -1.0f;
        interleavedStereo[i] = v;
    }
}
