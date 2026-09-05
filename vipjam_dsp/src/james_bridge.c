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
