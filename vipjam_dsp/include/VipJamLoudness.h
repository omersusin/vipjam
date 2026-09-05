#ifndef VIPJAM_LOUDNESS_H
#define VIPJAM_LOUDNESS_H

#include <stdint.h>

#define VIPJAM_LOUDNESS_BANDS 8
#define VIPJAM_LOUDNESS_REF_PHON 80.0f
#define VIPJAM_LOUDNESS_RAMP_SEC 0.05f

class VipJamLoudness {
public:
    VipJamLoudness();
    void setSampleRate(uint32_t rate);
    void setEnabled(bool enabled);
    bool isEnabled() const { return enabled_; }
    void setVolume(float device01, float app01, float thresholdDb = 0.0f);
    void reset();
    void process(float *interleavedStereo, uint32_t frames);

private:
    struct Coeff { float b0, b1, b2, a1, a2; };
    struct State { float x1, x2, y1, y2; };
    void recompute();
    static Coeff shelfLow(float fc, float q, float gainDb, float sr);
    static Coeff peak(float fc, float q, float gainDb, float sr);
    static Coeff shelfHigh(float fc, float q, float gainDb, float sr);

    uint32_t sampleRate_;
    bool enabled_;
    float startG_[VIPJAM_LOUDNESS_BANDS];
    float curG_[VIPJAM_LOUDNESS_BANDS];
    float tgtG_[VIPJAM_LOUDNESS_BANDS];
    uint32_t rampPos_;
    uint32_t rampFrames_;
    Coeff coeff_[VIPJAM_LOUDNESS_BANDS];
    State state_[VIPJAM_LOUDNESS_BANDS][2];
};

#endif
