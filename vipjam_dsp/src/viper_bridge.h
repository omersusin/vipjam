#ifndef VJ_VIPER_BRIDGE_H
#define VJ_VIPER_BRIDGE_H

#include <stdint.h>
#include <vector>

struct vj_viper;

vj_viper *vj_viper_create(uint32_t sampleRate);
void vj_viper_free(vj_viper *v);
void vj_viper_set_rate(vj_viper *v, uint32_t sampleRate);
void vj_viper_set_stage(vj_viper *v, int stage, int enabled);
void vj_viper_reset(vj_viper *v);
void vj_viper_process(vj_viper *v, std::vector<float> &interleavedStereo,
                      uint32_t frames);
void vj_viper_set_ddc(vj_viper *v, const float *c44, unsigned int n44,
                      const float *c48, unsigned int n48);
void vj_viper_set_kernel_mono(vj_viper *v, const float *frames,
                              unsigned int len);
unsigned int vj_viper_kernel_id(vj_viper *v);
void vj_viper_kernel_prepare(vj_viper *v, unsigned int totalFloats,
                             unsigned int channels);
void vj_viper_kernel_append(vj_viper *v, unsigned int totalFloats,
                            const float *buf, unsigned int len);
void vj_viper_kernel_commit(vj_viper *v, unsigned int totalFloats,
                            unsigned int crc32, unsigned int kernelId);

#endif
