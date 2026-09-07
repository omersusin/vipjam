#ifndef VIPJAM_CHAIN_H
#define VIPJAM_CHAIN_H

#include <stdint.h>
#include <string>
#include <vector>
#include "VipJamStages.h"
#include "VipJamLoudness.h"

typedef struct vj_james vj_james_t;
struct vj_viper;

class VipJamChain {
public:
    VipJamChain();
    ~VipJamChain();
    void setSamplingRate(uint32_t rate);
    void setStageEnabled(vj_stage_t stage, bool enabled);
    bool isStageEnabled(vj_stage_t stage) const;
    int loadDDC(const char *vdcText);
    int loadIR(const float *frames, unsigned int channels, unsigned int len);
    int loadLiveProg(const char *eelText);
    int loadLiveProgMulti(const char **scripts, int n);
    void setJamesEQ(const double *freqHz, const double *gainDb, int interp);
    void setJamesBass(float maxGainDb);
    void setJamesComp(float tc, int gran, int tfres);
    void setJamesReverb(int preset);
    void setJamesTube(double dbGain);
    void setJamesStereo(float mix01);
    void setJamesXfeed(int mode);
    void setViperEQBand(unsigned int band, float levelDb);
    void setViperBass(int mode, float factor);
    void setViperReverb(float room, float width, float damp, float wet,
                        float dry);
    void setViperClarity(int mode, float gain);
    void setViperFET(int param, float value);
    void setViperAnalogX(int mode);
    void setLoudnessVolume(float device01, float app01);
    void setMasterEnabled(bool on);
    bool isMasterEnabled() const { return master_; }
    void setLimiter(float threshold01);
    int setFusedParam(int32_t id, float v0, float v1, float v2);
    void viperSetDDC(const float *c44, unsigned int n44, const float *c48,
                     unsigned int n48);
    void viperSetKernelMono(const float *frames, unsigned int len);
    void viperKernelPrepare(unsigned int totalFloats, unsigned int channels);
    void viperKernelAppend(unsigned int totalFloats, const float *buf,
                           unsigned int len);
    void viperKernelCommit(unsigned int totalFloats, unsigned int crc32,
                           unsigned int kernelId);
    unsigned int viperKernelID() const;
    void reset();
    int setChainOrder(const int *stages, unsigned n);
    unsigned getChainOrder(int *out, unsigned cap) const;
    void process(std::vector<float> &interleavedStereo);
    static void deinterleave(const float *in, float *left, float *right,
                             uint32_t frames);
    static void interleave(const float *left, const float *right, float *out,
                           uint32_t frames);
    uint32_t samplingRate() const { return samplingRate_; }

private:
    bool anyJamesStageOn() const;
    void applyJamesStage(vj_stage_t stage, bool enabled);
    void applyViperStage(vj_stage_t stage, bool enabled);
    void recomputeBassMono();

    uint32_t samplingRate_;
    bool enabled_[VJ_STAGE_COUNT];
    bool master_;
    float limiterGate_;
    float bassMonoA_;
    float bassMonoLpL_;
    float bassMonoLpR_;
    // Fused reverb is split across two wire records (see VipJamParams.h):
    // VIPJAM_REVERB carries (room, width, damp), VIPJAM_REVERB_WETDRY
    // carries (wet, dry). Both halves are latched here so each handler can
    // re-apply the full 5-arg engine call. Defaults match the engine
    // Reverberation ctor (wet 0, dry 50) when the wet/dry half never arrives.
    float reverbRoom_, reverbWidth_, reverbDamp_, reverbWet_, reverbDry_;
    VipJamLoudness loudness_;
    int pendingOrder_[32];
    unsigned pendingOrderLen_;
    vj_james_t *jdsp_;
    vj_viper *viper_;
};

#endif
