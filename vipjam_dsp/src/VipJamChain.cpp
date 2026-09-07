#include "VipJamChain.h"
#include "VipJamChainOrder.h"
#include "james_bridge.h"
#include "viper_bridge.h"
#include "VipJamParams.h"
#include <cmath>

VipJamChain::VipJamChain()
    : samplingRate_(44100), master_(true), limiterGate_(0.999999f),
      bassMonoA_(0.0f), bassMonoLpL_(0.0f), bassMonoLpR_(0.0f),
      reverbRoom_(0.0f), reverbWidth_(0.0f), reverbDamp_(0.0f),
      reverbWet_(0.0f), reverbDry_(50.0f), pendingOrderLen_(0) {
    for (int i = 0; i < VJ_STAGE_COUNT; i++) enabled_[i] = false;
    for (int i = 0; i < 32; i++) pendingOrder_[i] = -1;
    recomputeBassMono();
    jdsp_ = vj_james_create(samplingRate_);
    viper_ = vj_viper_create(samplingRate_);
}

VipJamChain::~VipJamChain() {
    vj_james_free(static_cast<vj_james_t *>(jdsp_));
    vj_viper_free(static_cast<vj_viper *>(viper_));
}

void VipJamChain::setSamplingRate(uint32_t rate) {
    if (rate < 8000 || rate > 192000) return;
    samplingRate_ = rate;
    loudness_.setSampleRate(rate);
    recomputeBassMono();
    vj_james_set_rate(static_cast<vj_james_t *>(jdsp_), rate);
    vj_viper_set_rate(static_cast<vj_viper *>(viper_), rate);
}

void VipJamChain::recomputeBassMono() {
    float sr = static_cast<float>(samplingRate_);
    if (!(sr >= 8000.0f && sr <= 192000.0f)) sr = 44100.0f;
    float x = 2.0f * 3.14159265358979323846f * 120.0f / sr;
    float a = 1.0f - expf(-x);
    if (!(a > 0.0f && a < 1.0f)) a = 1.0f - expf(-2.0f * 3.14159265358979323846f * 120.0f / 44100.0f);
    if (!(a > 0.0f && a < 1.0f)) a = 0.0169f;
    bassMonoA_ = a;
    bassMonoLpL_ = 0.0f;
    bassMonoLpR_ = 0.0f;
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
    if (stage == VJ_STAGE_BASS_MONO) {
        if (enabled) {
            bassMonoLpL_ = 0.0f;
            bassMonoLpR_ = 0.0f;
        }
        return;
    }
    if (stage == VJ_STAGE_LOUDNESS) loudness_.setEnabled(enabled);
    else if (stage <= VJ_STAGE_JAMES_REVERB) applyJamesStage(stage, enabled);
    else if (stage < VJ_STAGE_LIMITER) applyViperStage(stage, enabled);
}

bool VipJamChain::isStageEnabled(vj_stage_t stage) const {
    if (stage < 0 || stage >= VJ_STAGE_COUNT) return false;
    return enabled_[stage];
}

int VipJamChain::loadDDC(const char *vdcText) {
    if (!jdsp_ || !vdcText) return -1;
    return vj_james_load_ddc(static_cast<vj_james_t *>(jdsp_), vdcText);
}

int VipJamChain::loadIR(const float *frames, unsigned int channels,
                        unsigned int len) {
    if (!jdsp_ || !frames) return -1;
    return vj_james_load_ir(static_cast<vj_james_t *>(jdsp_), frames,
                            channels, len);
}

int VipJamChain::loadLiveProg(const char *eelText) {
    if (!jdsp_ || !eelText) return -1;
    return vj_james_load_liveprog(static_cast<vj_james_t *>(jdsp_), eelText);
}

int VipJamChain::loadLiveProgMulti(const char **scripts, int n) {
    if (!jdsp_ || !scripts) return -1;
    return vj_james_load_liveprog_multi(static_cast<vj_james_t *>(jdsp_),
                                        scripts, n);
}

void VipJamChain::setJamesEQ(const double *freqHz, const double *gainDb,
                             int interp) {
    if (!jdsp_ || !freqHz || !gainDb) return;
    vj_james_set_eq15(static_cast<vj_james_t *>(jdsp_), freqHz, gainDb,
                      interp);
}

void VipJamChain::setJamesBass(float maxGainDb) {
    vj_james_set_bass(static_cast<vj_james_t *>(jdsp_), maxGainDb);
}

void VipJamChain::setJamesComp(float tc, int gran, int tfres) {
    vj_james_set_comp(static_cast<vj_james_t *>(jdsp_), tc, gran, tfres);
}

void VipJamChain::setJamesReverb(int preset) {
    vj_james_set_reverb(static_cast<vj_james_t *>(jdsp_), preset);
}

void VipJamChain::setJamesTube(double dbGain) {
    vj_james_set_tube(static_cast<vj_james_t *>(jdsp_), dbGain);
}

void VipJamChain::setJamesStereo(float mix01) {
    vj_james_set_stereo(static_cast<vj_james_t *>(jdsp_), mix01);
}

void VipJamChain::setJamesXfeed(int mode) {
    vj_james_set_xfeed(static_cast<vj_james_t *>(jdsp_), mode);
}

void VipJamChain::setViperEQBand(unsigned int band, float levelDb) {
    vj_viper_set_eq_band(static_cast<vj_viper *>(viper_), band, levelDb);
}

void VipJamChain::setViperBass(int mode, float factor) {
    vj_viper_set_bass(static_cast<vj_viper *>(viper_), mode, factor);
}

void VipJamChain::setViperReverb(float room, float width, float damp,
                                 float wet, float dry) {
    reverbRoom_ = room;
    reverbWidth_ = width;
    reverbDamp_ = damp;
    reverbWet_ = wet;
    reverbDry_ = dry;
    vj_viper_set_reverb(static_cast<vj_viper *>(viper_), room, width, damp,
                        wet, dry);
}

void VipJamChain::setViperClarity(int mode, float gain) {
    vj_viper_set_clarity(static_cast<vj_viper *>(viper_), mode, gain);
}

void VipJamChain::setViperFET(int param, float value) {
    vj_viper_set_fet(static_cast<vj_viper *>(viper_), param, value);
}

void VipJamChain::setViperAnalogX(int mode) {
    vj_viper_set_analogx(static_cast<vj_viper *>(viper_), mode);
}

void VipJamChain::setLoudnessVolume(float device01, float app01) {
    loudness_.setVolume(device01, app01);
}

void VipJamChain::setMasterEnabled(bool on) {
    master_ = on;
}

void VipJamChain::setLimiter(float threshold01) {
    if (!(threshold01 >= 0.01f)) threshold01 = 0.01f;
    else if (!(threshold01 <= 1.0f)) threshold01 = 1.0f;
    limiterGate_ = threshold01;
}

static vj_stage_t enableIdToStage(int32_t id) {
    switch (id) {
    case 65565: return VJ_STAGE_VIPER_PGC;
    case 65574: return VJ_STAGE_VIPER_BASS;
    case 65578: return VJ_STAGE_VIPER_CLARITY;
    case 65551: return VJ_STAGE_VIPER_IIR;
    case 65559: return VJ_STAGE_VIPER_REVERB;
    case 65538: return VJ_STAGE_VIPER_CONV;
    case 65546: return VJ_STAGE_VIPER_DDC;
    case 65569: return VJ_STAGE_VIPER_DYNSYS;
    case 65583: return VJ_STAGE_JAMES_TUBE;
    case 65581: return VJ_STAGE_VIPER_CURE;
    case 65584: return VJ_STAGE_VIPER_ANALOGX;
    case 65610: return VJ_STAGE_VIPER_FET;
    case 65544: return VJ_STAGE_VIPER_VHE;
    case 65557: return VJ_STAGE_VIPER_DIFF;
    case 65603: return VJ_STAGE_VIPER_SPK;
    case VIPER_NEW_BASS_MONO_FIRST: return VJ_STAGE_BASS_MONO;
    default: return VJ_STAGE_COUNT;
    }
}

int VipJamChain::setFusedParam(int32_t id, float v0, float v1, float v2) {
    if (id == 36868) {
        setMasterEnabled(v0 != 0.0f);
        return 0;
    }
    if (id == 65577) {
        float g = v0 / 100.0f;
        if (!(g >= 0.0f && g <= 10.0f)) g = 0.0f;
        vj_viper_set_bass(static_cast<vj_viper *>(viper_), 0, g);
        setStageEnabled(VJ_STAGE_VIPER_BASS, v0 != 0.0f);
        return 0;
    }
    vj_stage_t stage = enableIdToStage(id);
    if (stage != VJ_STAGE_COUNT) {
        setStageEnabled(stage, v0 != 0.0f);
        return 0;
    }
    int32_t fused = vipjam_shim_to_fused(id);
    vj_viper *viper = static_cast<vj_viper *>(viper_);
    switch (fused) {
    case VIPJAM_MASTER_ENABLE:
        setMasterEnabled(v0 != 0.0f);
        return 0;
    case VIPJAM_LIMITER:
        setLimiter(v0);
        setStageEnabled(VJ_STAGE_LIMITER, true);
        return 0;
    case VIPJAM_BASS:
        if (!(v0 >= -100.0f && v0 <= 1000.0f)) return -1;
        if (!(v1 >= 0.0f && v1 <= 5.0f)) return -1;
        vj_viper_set_bass(viper, (int)v1, v0);
        setStageEnabled(VJ_STAGE_VIPER_BASS, v0 != 0.0f);
        return 0;
    case VIPJAM_BASS_MONO:
        setStageEnabled(VJ_STAGE_BASS_MONO, v0 != 0.0f);
        return 0;
    case VIPJAM_EQ:
        if (!(v0 >= 0.0f && v0 <= 30.0f)) return -1;
        if (!(v1 >= -12.0f && v1 <= 12.0f)) return -1;
        vj_viper_set_eq_band(viper, (unsigned int)v0, v1);
        setStageEnabled(VJ_STAGE_VIPER_IIR, true);
        return 0;
    case VIPJAM_CLARITY_SPECEX:
        if (!(v0 >= 0.0f && v0 <= 450.0f)) return -1;
        if (!(v1 >= 0.0f && v1 <= 5.0f)) return -1;
        vj_viper_set_clarity(viper, (int)v1, v0);
        setStageEnabled(VJ_STAGE_VIPER_CLARITY, true);
        return 0;
    case VIPJAM_REVERB:
        if (!(v0 >= 0.0f && v0 <= 100.0f) || !(v1 >= 0.0f && v1 <= 100.0f) ||
            !(v2 >= 0.0f && v2 <= 100.0f))
            return -1;
        // 3-slot wire record cannot carry wet/dry: latch this half and
        // re-apply the full 5-arg call with the latched wet/dry half
        // (engine defaults wet=0, dry=50 when VIPJAM_REVERB_WETDRY never came).
        reverbRoom_ = v0;
        reverbWidth_ = v1;
        reverbDamp_ = v2;
        vj_viper_set_reverb(viper, reverbRoom_, reverbWidth_, reverbDamp_,
                            reverbWet_, reverbDry_);
        setStageEnabled(VJ_STAGE_VIPER_REVERB, true);
        return 0;
    case VIPJAM_REVERB_WETDRY:
        if (!(v0 >= 0.0f && v0 <= 100.0f) || !(v1 >= 0.0f && v1 <= 100.0f))
            return -1;
        reverbWet_ = v0;
        reverbDry_ = v1;
        vj_viper_set_reverb(viper, reverbRoom_, reverbWidth_, reverbDamp_,
                            reverbWet_, reverbDry_);
        setStageEnabled(VJ_STAGE_VIPER_REVERB, true);
        return 0;
    case VIPJAM_XFEED:
        if (!(v0 >= 0.0f && v0 <= 5.0f)) return -1;
        vj_james_set_xfeed(static_cast<vj_james_t *>(jdsp_), (int)v0);
        setStageEnabled(VJ_STAGE_JAMES_XFEED, true);
        return 0;
    case VIPJAM_TUBE:
        if (!(v0 >= -3.0 && v0 <= 12.0)) return -1;
        vj_james_set_tube(static_cast<vj_james_t *>(jdsp_), (double)v0);
        setStageEnabled(VJ_STAGE_JAMES_TUBE, true);
        return 0;
    default:
        break;
    }
    return -1;
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

int VipJamChain::setChainOrder(const int *stages, unsigned n) {
    int rc = vipjam_chain_order_validate(stages, n);
    if (rc != VJ_ORDER_OK) return rc;
    for (unsigned i = 0; i < n; i++) pendingOrder_[i] = stages[i];
    pendingOrderLen_ = n;
    return VJ_ORDER_OK;
}

unsigned VipJamChain::getChainOrder(int *out, unsigned cap) const {
    if (!out || cap == 0) return pendingOrderLen_;
    unsigned n = pendingOrderLen_ < cap ? pendingOrderLen_ : cap;
    for (unsigned i = 0; i < n; i++) out[i] = pendingOrder_[i];
    return pendingOrderLen_;
}

void VipJamChain::reset() {
    limiterGate_ = 0.999999f;
    bassMonoLpL_ = 0.0f;
    bassMonoLpR_ = 0.0f;
    loudness_.reset();
    vj_viper_reset(static_cast<vj_viper *>(viper_));
    vj_viper_kernel_prepare(static_cast<vj_viper *>(viper_), 0, 0);
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
    if (!master_) return;
    // NOTE: pendingOrder_ is stored metadata only (VIPJAM_CHAIN_ORDER_PENDING_NOTE).
    // Audio still runs the fixed order below; true reorder needs per-stage
    // processing inside both upstream engines plus protocol + app.
    if (anyJamesStageOn()) {
        vj_james_process(static_cast<vj_james_t *>(jdsp_),
                         interleavedStereo.data(), frames);
    }
    if (anyViperStageOn(enabled_)) {
        vj_viper_process(static_cast<vj_viper *>(viper_), interleavedStereo,
                         frames);
    }
    if (enabled_[VJ_STAGE_LOUDNESS]) {
        loudness_.process(interleavedStereo.data(), frames);
    }
    if (enabled_[VJ_STAGE_BASS_MONO]) {
        float a = bassMonoA_;
        if (!(a > 0.0f && a < 1.0f)) {
            recomputeBassMono();
            a = bassMonoA_;
        }
        float lpL = bassMonoLpL_;
        float lpR = bassMonoLpR_;
        if (!(lpL >= -8.0f && lpL <= 8.0f)) lpL = 0.0f;
        if (!(lpR >= -8.0f && lpR <= 8.0f)) lpR = 0.0f;
        float *d = interleavedStereo.data();
        for (uint32_t i = 0; i < frames; i++) {
            float xl = d[i * 2];
            float xr = d[i * 2 + 1];
            if (!(xl >= -8.0f && xl <= 8.0f)) xl = 0.0f;
            if (!(xr >= -8.0f && xr <= 8.0f)) xr = 0.0f;
            lpL += a * (xl - lpL);
            lpR += a * (xr - lpR);
            if (!(lpL >= -8.0f && lpL <= 8.0f)) lpL = 0.0f;
            if (!(lpR >= -8.0f && lpR <= 8.0f)) lpR = 0.0f;
            float lowMono = 0.5f * (lpL + lpR);
            d[i * 2] = xl + (lowMono - lpL);
            d[i * 2 + 1] = xr + (lowMono - lpR);
        }
        bassMonoLpL_ = lpL;
        bassMonoLpR_ = lpR;
    }
    for (uint32_t i = 0; i < interleavedStereo.size(); i++) {
        float v = interleavedStereo[i];
        if (!(v >= -8.0f && v <= 8.0f)) v = 0.0f;
        if (enabled_[VJ_STAGE_LIMITER]) {
            float g = limiterGate_;
            float knee = 0.92f * g;
            float a = fabsf(v);
            if (a > knee && g > knee) {
                float over = (a - knee) / (g - knee);
                float shaped = knee + (g - knee) * tanhf(over);
                v = (v < 0.0f ? -1.0f : 1.0f) * shaped;
            }
        }
        if (v > 1.0f) v = 1.0f;
        else if (v < -1.0f) v = -1.0f;
        interleavedStereo[i] = v;
    }
}
