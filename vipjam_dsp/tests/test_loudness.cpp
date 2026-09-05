#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <vector>
#include "VipJamLoudness.h"
#include "VipJamChain.h"

static int failures = 0;
#define CHECK(cond, name)                                             \
    do {                                                              \
        if (cond) {                                                   \
            printf("PASS %s\n", name);                                \
        } else {                                                      \
            printf("FAIL %s\n", name);                                \
            failures++;                                               \
        }                                                             \
    } while (0)

static std::vector<float> sineStereo(float freqHz, uint32_t rate,
                                     uint32_t frames, float amp) {
    std::vector<float> buf(frames * 2);
    for (uint32_t i = 0; i < frames; i++) {
        float v = amp * sinf(2.0f * 3.14159265f * freqHz * (float)i
                             / (float)rate);
        buf[i * 2] = v;
        buf[i * 2 + 1] = v;
    }
    return buf;
}

static float tailRms(const std::vector<float> &buf, uint32_t frames,
                     uint32_t tailFrames) {
    double acc = 0.0;
    uint32_t start = frames > tailFrames ? frames - tailFrames : 0;
    uint32_t n = 0;
    for (uint32_t i = start; i < frames; i++) {
        acc += (double)buf[i * 2] * (double)buf[i * 2];
        n++;
    }
    return (float)sqrt(acc / (n ? n : 1));
}

static float runGain(float freqHz, float volume) {
    const uint32_t rate = 48000;
    const uint32_t frames = rate * 2;
    VipJamLoudness loud;
    loud.setSampleRate(rate);
    loud.setEnabled(true);
    loud.setVolume(volume, 1.0f);
    std::vector<float> buf = sineStereo(freqHz, rate, frames, 0.2f);
    const uint32_t block = 512;
    for (uint32_t off = 0; off < frames; off += block) {
        uint32_t n = frames - off < block ? frames - off : block;
        loud.process(buf.data() + off * 2, n);
    }
    float inRms = 0.2f / 1.41421356f;
    return tailRms(buf, frames, rate / 2) / inRms;
}

static void test_bypass_and_transparency(void) {
    const uint32_t rate = 48000;
    const uint32_t frames = 4096;
    VipJamLoudness loud;
    loud.setSampleRate(rate);
    std::vector<float> buf = sineStereo(440.0f, rate, frames, 0.5f);
    std::vector<float> ref = buf;
    loud.process(buf.data(), frames);
    CHECK(memcmp(buf.data(), ref.data(), buf.size() * 4) == 0,
          "loudness disabled bypass bit-exact");
    loud.setEnabled(true);
    loud.setVolume(1.0f, 1.0f);
    loud.process(buf.data(), frames);
    float maxDiff = 0.0f;
    for (size_t i = 0; i < buf.size(); i++) {
        float d = fabsf(buf[i] - ref[i]);
        if (d > maxDiff) maxDiff = d;
    }
    CHECK(maxDiff < 1e-6f, "loudness full volume transparent");
}

static void test_contour_shape(void) {
    float g50 = runGain(50.0f, 0.1f);
    float g100 = runGain(100.0f, 0.1f);
    float g1k = runGain(1000.0f, 0.1f);
    float g10k = runGain(10000.0f, 0.1f);
    CHECK(g50 > 2.0f && g50 < 2.5f, "loudness 50hz lift near +8db");
    CHECK(g100 > 1.8f && g100 < 2.2f, "loudness 100hz lift near +6db");
    CHECK(g1k > 0.95f && g1k < 1.05f, "loudness 1khz neutral");
    CHECK(g10k > 1.0f && g10k < 1.35f, "loudness 10khz slight lift");
    float g50half = runGain(50.0f, 0.31622777f);
    CHECK(g50half > 1.3f && g50half < 1.7f, "loudness half strength scales");
}

static void test_ramp_glide(void) {
    const uint32_t rate = 48000;
    const uint32_t frames = rate * 2;
    VipJamLoudness loud;
    loud.setSampleRate(rate);
    loud.setEnabled(true);
    loud.setVolume(0.1f, 1.0f);
    std::vector<float> buf = sineStereo(50.0f, rate, frames, 0.2f);
    loud.process(buf.data(), 64);
    float early = tailRms(buf, 64, 64);
    for (uint32_t off = 64; off < frames; off += 512) {
        uint32_t n = frames - off < 512 ? frames - off : 512;
        loud.process(buf.data() + off * 2, n);
    }
    float late = tailRms(buf, frames, rate / 2);
    CHECK(late > early * 1.2f, "loudness ramps up over time");
    bool finite = true;
    for (size_t i = 0; i < buf.size(); i++) {
        if (!isfinite(buf[i]) || fabsf(buf[i]) > 2.0f) { finite = false; break; }
    }
    CHECK(finite, "loudness ramp stays finite and bounded");
}

static void test_chain_integration(void) {
    VipJamChain chain;
    chain.setSamplingRate(48000);
    chain.setStageEnabled(VJ_STAGE_LOUDNESS, true);
    CHECK(chain.isStageEnabled(VJ_STAGE_LOUDNESS), "chain loudness stage on");
    chain.setLoudnessVolume(0.1f, 1.0f);
    std::vector<float> buf = sineStereo(50.0f, 48000, 48000, 0.2f);
    std::vector<float> ref = buf;
    chain.process(buf);
    float late = tailRms(buf, 48000, 24000);
    float inRms = 0.2f / 1.41421356f;
    CHECK(late / inRms > 1.5f, "chain loudness lifts bass pre-limiter");
    bool finite = true;
    for (size_t i = 0; i < buf.size(); i++)
        if (!isfinite(buf[i])) { finite = false; break; }
    CHECK(finite, "chain loudness output finite");
}

int main(void) {
    test_bypass_and_transparency();
    test_contour_shape();
    test_ramp_glide();
    test_chain_integration();
    if (failures == 0) printf("ALL GREEN\n");
    else printf("%d FAILURES\n", failures);
    return failures ? 1 : 0;
}
