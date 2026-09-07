#include "james_bridge.h"
#include "VipJamStages.h"
#include "jdsp/jdsp_header.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

static float vj_finite_clamp(float v, float lo, float hi, float dflt) {
    if (!(v >= lo && v <= hi)) return dflt;
    return v;
}

struct vj_james {
    JamesDSPLib lib;
};

static int g_live_instances = 0;

vj_james_t *vj_james_create(uint32_t sampleRate) {
    vj_james_t *j = (vj_james_t *)calloc(1, sizeof(vj_james_t));
    if (!j) return 0;
    if (g_live_instances == 0) JamesDSPGlobalMemoryAllocation();
    g_live_instances++;
    JamesDSPInit(&j->lib, 2048, (float)sampleRate);
    return j;
}

void vj_james_free(vj_james_t *j) {
    if (!j) return;
    JamesDSPFree(&j->lib);
    free(j);
    g_live_instances--;
    if (g_live_instances <= 0) {
        g_live_instances = 0;
        JamesDSPGlobalMemoryDeallocation();
    }
}

void vj_james_set_rate(vj_james_t *j, uint32_t sampleRate) {
    if (!j) return;
    if (sampleRate < 8000 || sampleRate > 192000) return;
    JamesDSPLib *d = &j->lib;
    JamesDSPSetSampleRate(d, (float)sampleRate, 0);
    BassBoostSetParam(d, d->dbb.maxGain);
    d->ddcForceRefresh = 1;
    DDCEnable(d, d->ddcEnabled);
    d->crossfeedForceRefresh = 1;
    CrossfeedEnable(d, d->crossfeedEnabled);
    d->arbMagForceRefresh = 1;
    ArbitraryResponseEqualizerEnable(d, d->arbitraryMagEnabled);
    d->equalizerForceRefresh = 1;
    MultimodalEqualizerEnable(d, d->equalizerEnabled);
    d->compForceRefresh = 1;
    CompressorEnable(d, d->compEnabled);
    StereoEnhancementRefresh(d);
    // Rate-dependent stages without a ForceRefresh flag: re-enable with
    // current state so coeffects rebuild at the new rate.
    if (d->tubeEnabled) VacuumTubeEnable(d);
    if (d->convolverEnabled) Convolver1DEnable(d);
    if (d->liveprogEnabled) LiveProgEnable(d);
    if (d->reverbEnabled) ReverbEnable(d);
}

void vj_james_set_stage(vj_james_t *j, int stage, int enabled) {
    if (!j) return;
    char c = enabled ? 1 : 0;
    JamesDSPLib *d = &j->lib;
    switch (stage) {
    case VJ_STAGE_JAMES_TUBE:
        if (enabled) VacuumTubeEnable(d);
        else d->tubeEnabled = 0;
        break;
    case VJ_STAGE_JAMES_COMP: CompressorEnable(d, c); break;
    case VJ_STAGE_JAMES_BASS:
        if (enabled) BassBoostEnable(d);
        else d->bassBoostEnabled = 0;
        break;
    case VJ_STAGE_JAMES_EQ: MultimodalEqualizerEnable(d, c); break;
    case VJ_STAGE_JAMES_ARBEQ: ArbitraryResponseEqualizerEnable(d, c); break;
    case VJ_STAGE_JAMES_CONV:
        if (enabled) Convolver1DEnable(d);
        else d->convolverEnabled = 0;
        break;
    case VJ_STAGE_JAMES_DDC: DDCEnable(d, c); break;
    case VJ_STAGE_JAMES_LIVEPROG:
        if (enabled) LiveProgEnable(d);
        else d->liveprogEnabled = 0;
        break;
    case VJ_STAGE_JAMES_XFEED: CrossfeedEnable(d, c); break;
    case VJ_STAGE_JAMES_STEREO:
        if (enabled) StereoEnhancementEnable(d);
        else d->sterEnhEnabled = 0;
        break;
    case VJ_STAGE_JAMES_REVERB:
        if (enabled) ReverbEnable(d);
        else d->reverbEnabled = 0;
        break;
    default: break;
    }
}

void vj_james_process(vj_james_t *j, float *interleaved, uint32_t frames) {
    if (!j || !interleaved || frames == 0) return;
    if (j->lib.processFloatMultiplexd) {
        j->lib.processFloatMultiplexd(&j->lib, interleaved, interleaved,
                                      (size_t)frames);
    }
}

int vj_james_load_ddc(vj_james_t *j, const char *vdcText) {
    if (!j || !vdcText || !vdcText[0]) return -1;
    size_t n = strlen(vdcText);
    if (n > 1048576) return -1;
    char *copy = (char *)malloc(n + 1);
    if (!copy) return -1;
    memcpy(copy, vdcText, n + 1);
    int rc = DDCStringParser(&j->lib, copy);
    free(copy);
    return rc;
}

int vj_james_load_liveprog(vj_james_t *j, const char *eelText) {
    if (!j || !eelText || !eelText[0]) return -1;
    size_t n = strlen(eelText);
    if (n > 1048576) return -1;
    char *copy = (char *)malloc(n + 1);
    if (!copy) return -1;
    memcpy(copy, eelText, n + 1);
    int rc = LiveProgStringParser(&j->lib, copy);
    free(copy);
    return rc;
}

static void appendSection(char **dst, size_t *len, size_t *cap,
                          const char *src, size_t n) {
    if (!dst || !len || !cap || !src || n == 0) return;
    if (n > 1048576) return;
    while (*len + n + 2 > *cap) {
        size_t ncap = *cap ? *cap * 2 : 1024;
        if (ncap > 2097152) return;
        char *nd = (char *)realloc(*dst, ncap);
        if (!nd) return;
        *dst = nd;
        *cap = ncap;
    }
    if (!*dst) return;
    memcpy(*dst + *len, src, n);
    *len += n;
    (*dst)[(*len)++] = '\n';
}

int vj_james_load_liveprog_multi(vj_james_t *j, const char **scripts, int n) {
    if (!j || !scripts || n <= 0 || n > 4) return -1;
    const char initTag[] = "@init";
    const char sampleTag[] = "@sample";
    char *initBuf = 0, *sampleBuf = 0;
    size_t initLen = 0, initCap = 0, sampleLen = 0, sampleCap = 0;
    for (int s = 0; s < n; s++) {
        const char *sc = scripts[s];
        if (!sc || !sc[0]) continue;
        const char *pi = strstr(sc, initTag);
        const char *ps = strstr(sc, sampleTag);
        const char *iStart = 0, *iEnd = 0, *sStart = 0, *sEnd = 0;
        size_t total = strlen(sc);
        if (pi) {
            iStart = pi + 5;
            iEnd = (ps && ps > pi) ? ps : sc + total;
        }
        if (ps) {
            sStart = ps + 7;
            sEnd = (pi && pi > ps) ? pi : sc + total;
        }
        if (iStart) appendSection(&initBuf, &initLen, &initCap, iStart,
                                  (size_t)(iEnd - iStart));
        if (sStart) appendSection(&sampleBuf, &sampleLen, &sampleCap, sStart,
                                  (size_t)(sEnd - sStart));
    }
    if (sampleLen == 0) {
        free(initBuf);
        free(sampleBuf);
        return -2;
    }
    size_t total = 6 + initLen + 8 + sampleLen + 2;
    char *merged2 = (char *)malloc(total);
    if (!merged2) {
        free(initBuf);
        free(sampleBuf);
        return -1;
    }
    size_t off = 0;
    memcpy(merged2 + off, initTag, 5);
    off += 5;
    merged2[off++] = '\n';
    memcpy(merged2 + off, initBuf, initLen);
    off += initLen;
    memcpy(merged2 + off, sampleTag, 7);
    off += 7;
    merged2[off++] = '\n';
    memcpy(merged2 + off, sampleBuf, sampleLen);
    off += sampleLen;
    merged2[off] = '\0';
    free(initBuf);
    free(sampleBuf);
    int rc = LiveProgStringParser(&j->lib, merged2);
    free(merged2);
    return rc;
}

void vj_james_set_eq15(vj_james_t *j, const double *freqHz,
                       const double *gainDb, int interp) {
    if (!j || !freqHz || !gainDb) return;
    double f[15], g[15];
    for (int i = 0; i < 15; i++) {
        double fi = freqHz[i];
        double gi = gainDb[i];
        if (!(fi > 0.0)) fi = 20.0;
        if (!(gi >= -12.0 && gi <= 12.0)) gi = 0.0;
        f[i] = fi;
        g[i] = gi;
    }
    MultimodalEqualizerAxisInterpolation(&j->lib, interp ? 1 : 0, 5, f, g);
}

void vj_james_set_bass(vj_james_t *j, float maxGainDb) {
    if (!j) return;
    maxGainDb = vj_finite_clamp(maxGainDb, 0.0f, 15.0f, 0.0f);
    BassBoostSetParam(&j->lib, maxGainDb);
}

void vj_james_set_comp(vj_james_t *j, float tc, int gran, int tfres) {
    if (!j) return;
    tc = vj_finite_clamp(tc, 0.06f, 0.3f, 0.06f);
    if (gran < 0) gran = 0;
    if (gran > 4) gran = 4;
    if (tfres < 0) tfres = 0;
    if (tfres > 3) tfres = 3;
    CompressorSetParam(&j->lib, tc, gran, tfres, 1);
}

void vj_james_set_reverb(vj_james_t *j, int preset) {
    if (!j) return;
    if (preset < 0 || preset > 18) return;
    Reverb_SetParam(&j->lib, preset);
}

void vj_james_set_tube(vj_james_t *j, double dbGain) {
    if (!j) return;
    if (!(dbGain >= -3.0 && dbGain <= 12.0)) return;
    VacuumTubeSetGain(&j->lib, dbGain);
}

void vj_james_set_stereo(vj_james_t *j, float mix01) {
    if (!j) return;
    mix01 = vj_finite_clamp(mix01, 0.0f, 1.0f, 0.0f);
    StereoEnhancementSetParam(&j->lib, mix01);
}

void vj_james_set_xfeed(vj_james_t *j, int mode) {
    if (!j) return;
    if (mode < 0 || mode > 5) return;
    CrossfeedChangeMode(&j->lib, mode);
}

int vj_james_load_ir(vj_james_t *j, const float *frames, unsigned int channels,
                     unsigned int len) {
    if (!j || !frames || len == 0) return -1;
    if (channels != 1 && channels != 2 && channels != 4) return -1;
    if (len > 1048576) return -1;
    if ((size_t)len * channels > 8388608) return -1;
    float *tmp = (float *)malloc(len * channels * sizeof(float));
    if (!tmp) return -1;
    memcpy(tmp, frames, len * channels * sizeof(float));
    int rc = Convolver1DLoadImpulseResponse(&j->lib, tmp, channels, len, 0);
    free(tmp);
    return rc;
}
