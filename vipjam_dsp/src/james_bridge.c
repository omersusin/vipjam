#include "james_bridge.h"
#include "VipJamStages.h"
#include "jdsp/jdsp_header.h"
#include <stdlib.h>
#include <string.h>

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
}

void vj_james_set_stage(vj_james_t *j, int stage, int enabled) {
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
    if (j->lib.processFloatMultiplexd) {
        j->lib.processFloatMultiplexd(&j->lib, interleaved, interleaved,
                                      (size_t)frames);
    }
}

int vj_james_load_ddc(vj_james_t *j, const char *vdcText) {
    if (!vdcText || !vdcText[0]) return -1;
    size_t n = strlen(vdcText);
    char *copy = (char *)malloc(n + 1);
    if (!copy) return -1;
    memcpy(copy, vdcText, n + 1);
    int rc = DDCStringParser(&j->lib, copy);
    free(copy);
    return rc;
}

int vj_james_load_liveprog(vj_james_t *j, const char *eelText) {
    if (!eelText || !eelText[0]) return -1;
    size_t n = strlen(eelText);
    char *copy = (char *)malloc(n + 1);
    if (!copy) return -1;
    memcpy(copy, eelText, n + 1);
    int rc = LiveProgStringParser(&j->lib, copy);
    free(copy);
    return rc;
}

static void appendSection(char **dst, size_t *len, size_t *cap,
                          const char *src, size_t n) {
    if (n == 0) return;
    while (*len + n + 2 > *cap) {
        *cap = *cap ? *cap * 2 : 1024;
        *dst = (char *)realloc(*dst, *cap);
        if (!*dst) return;
    }
    if (!*dst) return;
    memcpy(*dst + *len, src, n);
    *len += n;
    (*dst)[(*len)++] = '\n';
}

int vj_james_load_liveprog_multi(vj_james_t *j, const char **scripts, int n) {
    if (!scripts || n <= 0 || n > 4) return -1;
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

int vj_james_load_ir(vj_james_t *j, const float *frames, unsigned int channels,
                     unsigned int len) {
    if (!frames || len == 0) return -1;
    if (channels != 1 && channels != 2 && channels != 4) return -1;
    float *tmp = (float *)malloc(len * channels * sizeof(float));
    if (!tmp) return -1;
    memcpy(tmp, frames, len * channels * sizeof(float));
    int rc = Convolver1DLoadImpulseResponse(&j->lib, tmp, channels, len, 0);
    free(tmp);
    return rc;
}
