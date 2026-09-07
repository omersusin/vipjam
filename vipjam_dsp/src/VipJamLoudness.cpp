#include "VipJamLoudness.h"
#include <math.h>
#include <string.h>

namespace {
struct BandDef {
    int kind;
    float fc;
    float q;
    float gFull;
};
const BandDef kBands[VIPJAM_LOUDNESS_BANDS] = {
    {0, 35.0f, 0.7f, 6.0f},
    {1, 55.0f, 0.9f, 4.0f},
    {1, 90.0f, 1.0f, 3.0f},
    {1, 160.0f, 1.0f, 1.8f},
    {1, 400.0f, 0.8f, 0.4f},
    {1, 3500.0f, 1.2f, -0.7f},
    {2, 9000.0f, 0.7f, 1.2f},
    {2, 13000.0f, 0.8f, 1.8f},
};
const float kPi = 3.14159265358979323846f;
}

VipJamLoudness::VipJamLoudness()
    : sampleRate_(44100), enabled_(false), rampPos_(0),
      rampFrames_(2205) {
    for (int i = 0; i < VIPJAM_LOUDNESS_BANDS; i++)
        startG_[i] = curG_[i] = tgtG_[i] = 0.0f;
    recompute();
    reset();
}

VipJamLoudness::Coeff VipJamLoudness::shelfLow(float fc, float q, float gainDb,
                                               float sr) {
    if (!(q > 0.1f) || !(q < 10.0f)) q = 0.7f;
    if (!(sr > 0.0f)) sr = 44100.0f;
    if (fc > sr * 0.45f) fc = sr * 0.45f;
    if (fc < 10.0f) fc = 10.0f;
    float a = powf(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * kPi * fc / sr;
    float cw = cosf(w0);
    float inner = (a + 1.0f / a) * (1.0f / q - 1.0f) + 2.0f;
    if (!(inner > 0.0f)) inner = 0.0f;
    float alpha = sinf(w0) / 2.0f * sqrtf(inner);
    float sq = 2.0f * sqrtf(a) * alpha;
    float b0 = a * ((a + 1.0f) - (a - 1.0f) * cw + sq);
    float b1 = 2.0f * a * ((a - 1.0f) - (a + 1.0f) * cw);
    float b2 = a * ((a + 1.0f) - (a - 1.0f) * cw - sq);
    float a0 = (a + 1.0f) + (a - 1.0f) * cw + sq;
    if (!(a0 > 1e-6f) && !(a0 < -1e-6f)) a0 = 1.0f;
    float a1 = -2.0f * ((a - 1.0f) + (a + 1.0f) * cw);
    float a2 = (a + 1.0f) + (a - 1.0f) * cw - sq;
    return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

VipJamLoudness::Coeff VipJamLoudness::peak(float fc, float q, float gainDb,
                                           float sr) {
    if (!(q > 0.1f) || !(q < 10.0f)) q = 1.0f;
    if (!(sr > 0.0f)) sr = 44100.0f;
    if (fc > sr * 0.45f) fc = sr * 0.45f;
    if (fc < 10.0f) fc = 10.0f;
    float a = powf(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * kPi * fc / sr;
    float cw = cosf(w0);
    float alpha = sinf(w0) / (2.0f * q);
    float b0 = 1.0f + alpha * a;
    float b1 = -2.0f * cw;
    float b2 = 1.0f - alpha * a;
    float a0 = 1.0f + alpha / a;
    if (!(a0 > 1e-6f) && !(a0 < -1e-6f)) a0 = 1.0f;
    float a1 = -2.0f * cw;
    float a2 = 1.0f - alpha / a;
    return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

VipJamLoudness::Coeff VipJamLoudness::shelfHigh(float fc, float q,
                                                float gainDb, float sr) {
    if (!(q > 0.1f) || !(q < 10.0f)) q = 0.7f;
    if (!(sr > 0.0f)) sr = 44100.0f;
    if (fc > sr * 0.45f) fc = sr * 0.45f;
    if (fc < 10.0f) fc = 10.0f;
    float a = powf(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * kPi * fc / sr;
    float cw = cosf(w0);
    float inner = (a + 1.0f / a) * (1.0f / q - 1.0f) + 2.0f;
    if (!(inner > 0.0f)) inner = 0.0f;
    float alpha = sinf(w0) / 2.0f * sqrtf(inner);
    float sq = 2.0f * sqrtf(a) * alpha;
    float b0 = a * ((a + 1.0f) + (a - 1.0f) * cw + sq);
    float b1 = -2.0f * a * ((a - 1.0f) + (a + 1.0f) * cw);
    float b2 = a * ((a + 1.0f) + (a - 1.0f) * cw - sq);
    float a0 = (a + 1.0f) - (a - 1.0f) * cw + sq;
    if (!(a0 > 1e-6f) && !(a0 < -1e-6f)) a0 = 1.0f;
    float a1 = 2.0f * ((a - 1.0f) - (a + 1.0f) * cw);
    float a2 = (a + 1.0f) - (a - 1.0f) * cw - sq;
    return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

void VipJamLoudness::recompute() {
    float sr = (float)sampleRate_;
    Coeff fresh[VIPJAM_LOUDNESS_BANDS];
    for (int i = 0; i < VIPJAM_LOUDNESS_BANDS; i++) {
        if (kBands[i].kind == 0)
            fresh[i] = shelfLow(kBands[i].fc, kBands[i].q, curG_[i], sr);
        else if (kBands[i].kind == 2)
            fresh[i] = shelfHigh(kBands[i].fc, kBands[i].q, curG_[i], sr);
        else
            fresh[i] = peak(kBands[i].fc, kBands[i].q, curG_[i], sr);
    }
    memcpy(coeff_, fresh, sizeof(coeff_));
}

void VipJamLoudness::setSampleRate(uint32_t rate) {
    if (rate < 8000 || rate > 192000) return;
    sampleRate_ = rate;
    rampFrames_ = (uint32_t)(VIPJAM_LOUDNESS_RAMP_SEC * (float)rate);
    if (rampFrames_ < 1) rampFrames_ = 1;
    recompute();
}

void VipJamLoudness::setEnabled(bool enabled) {
    enabled_ = enabled;
}

void VipJamLoudness::setVolume(float device01, float app01,
                               float thresholdDb) {
    if (!isfinite(device01) || !isfinite(app01) || !isfinite(thresholdDb)) return;
    if (thresholdDb < -80.0f || thresholdDb > 0.0f) return;
    float v = device01 * app01;
    if (v < 1e-6f) v = 1e-6f;
    if (v > 1.0f) v = 1.0f;
    float levelDb = 20.0f * log10f(v);
    float s = (thresholdDb - levelDb) / 20.0f;
    if (s < 0.0f) s = 0.0f;
    if (s > 1.0f) s = 1.0f;
    memcpy(startG_, curG_, sizeof(startG_));
    for (int i = 0; i < VIPJAM_LOUDNESS_BANDS; i++)
        tgtG_[i] = kBands[i].gFull * s;
    rampPos_ = 0;
}

void VipJamLoudness::reset() {
    memset(state_, 0, sizeof(state_));
    memcpy(curG_, tgtG_, sizeof(curG_));
    memcpy(startG_, tgtG_, sizeof(startG_));
    rampPos_ = rampFrames_;
    recompute();
}

void VipJamLoudness::process(float *interleavedStereo, uint32_t frames) {
    if (!enabled_ || !interleavedStereo || frames == 0) return;
    bool live = false;
    for (int i = 0; i < VIPJAM_LOUDNESS_BANDS; i++) {
        if (curG_[i] != 0.0f || tgtG_[i] != 0.0f) { live = true; break; }
    }
    if (!live) return;
    if (rampPos_ < rampFrames_) {
        rampPos_ += frames;
        float t = (float)rampPos_ / (float)rampFrames_;
        if (t > 1.0f) t = 1.0f;
        for (int i = 0; i < VIPJAM_LOUDNESS_BANDS; i++)
            curG_[i] = startG_[i] + (tgtG_[i] - startG_[i]) * t;
        recompute();
    } else if (memcmp(curG_, tgtG_, sizeof(curG_)) != 0) {
        memcpy(curG_, tgtG_, sizeof(curG_));
        recompute();
    }
    for (uint32_t n = 0; n < frames; n++) {
        for (int ch = 0; ch < 2; ch++) {
            float x = interleavedStereo[n * 2 + ch];
            for (int i = 0; i < VIPJAM_LOUDNESS_BANDS; i++) {
                const Coeff &c = coeff_[i];
                State &s = state_[i][ch];
                float y = c.b0 * x + c.b1 * s.x1 + c.b2 * s.x2
                        - c.a1 * s.y1 - c.a2 * s.y2;
                s.x2 = s.x1;
                s.x1 = x;
                s.y2 = s.y1;
                s.y1 = y;
                x = y;
            }
            interleavedStereo[n * 2 + ch] = x;
        }
    }
}
