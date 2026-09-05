#ifndef VJ_JAMES_BRIDGE_H
#define VJ_JAMES_BRIDGE_H

#include <stdint.h>

typedef struct vj_james vj_james_t;

#ifdef __cplusplus
extern "C" {
#endif

vj_james_t *vj_james_create(uint32_t sampleRate);
void vj_james_free(vj_james_t *j);
void vj_james_set_rate(vj_james_t *j, uint32_t sampleRate);
void vj_james_set_stage(vj_james_t *j, int stage, int enabled);
void vj_james_process(vj_james_t *j, float *interleaved, uint32_t frames);
int vj_james_load_ddc(vj_james_t *j, const char *vdcText);
int vj_james_load_ir(vj_james_t *j, const float *frames, unsigned int channels,
                     unsigned int len);
int vj_james_load_liveprog(vj_james_t *j, const char *eelText);
int vj_james_load_liveprog_multi(vj_james_t *j, const char **scripts, int n);
void vj_james_set_eq15(vj_james_t *j, const double *freqHz,
                       const double *gainDb, int interpPchip0Makima1);
void vj_james_set_bass(vj_james_t *j, float maxGainDb);
void vj_james_set_comp(vj_james_t *j, float tc, int gran, int tfres);
void vj_james_set_reverb(vj_james_t *j, int preset);
void vj_james_set_tube(vj_james_t *j, double dbGain);
void vj_james_set_stereo(vj_james_t *j, float mix01);
void vj_james_set_xfeed(vj_james_t *j, int mode);

#ifdef __cplusplus
}
#endif

#endif
