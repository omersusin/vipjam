#ifndef VIPJAM_SHM_H
#define VIPJAM_SHM_H

#include <stddef.h>
#include <stdint.h>

#define VIPJAM_SHM_DIR "/data/local/tmp/vipjam"
#define VIPJAM_SHM_STATUS_PATH VIPJAM_SHM_DIR "/shm_status.bin"
#define VIPJAM_SHM_PARAMS_PATH VIPJAM_SHM_DIR "/shm_params.bin"
#define VIPJAM_SHM_BULK_PATH VIPJAM_SHM_DIR "/shm_bulk.bin"

#define VIPJAM_SHM_STATUS_SIZE 256
#define VIPJAM_SHM_PARAMS_SIZE 4096
#define VIPJAM_SHM_BULK_SIZE 4096

#define VIPJAM_SHM_SLOT_SIZE 1144
#define VIPJAM_SHM_SLOT_A 16
#define VIPJAM_SHM_SLOT_B 1160
#define VIPJAM_SHM_EXT_BASE 2304
#define VIPJAM_SHM_EXT_SIZE 1792
#define VIPJAM_SHM_EXT_MAGIC 0x564A4558u
#define VIPJAM_SHM_EXT_VERSION 1

#define VIPJAM_SHM_BULK_REGION 2048
#define VIPJAM_SHM_BULK_HDR 32
#define VIPJAM_SHM_BULK_MAX (VIPJAM_SHM_BULK_REGION - VIPJAM_SHM_BULK_HDR)

#define VIPJAM_BULK_DDC 1
#define VIPJAM_BULK_CONV_PATH 2
#define VIPJAM_BULK_DDC_RESET 3
#define VIPJAM_BULK_CONV_RESET 4
#define VIPJAM_BULK_STREQ_TEXT 5
#define VIPJAM_BULK_LIVEPROG_SCRIPT 6
#define VIPJAM_BULK_VIPJAM_FULL 7

#define VIPJAM_JAMES_EN_TONE (1u << 0)
#define VIPJAM_JAMES_EN_BASS (1u << 1)
#define VIPJAM_JAMES_EN_REVERB (1u << 2)
#define VIPJAM_JAMES_EN_WIDEN (1u << 3)
#define VIPJAM_JAMES_EN_CONV (1u << 4)
#define VIPJAM_JAMES_EN_TUBE (1u << 5)
#define VIPJAM_JAMES_EN_XFEED (1u << 6)
#define VIPJAM_JAMES_EN_ARBEQ (1u << 7)
#define VIPJAM_JAMES_EN_DDC (1u << 8)
#define VIPJAM_JAMES_EN_LIVEPROG (1u << 9)
#define VIPJAM_JAMES_EN_COMP (1u << 10)

typedef struct {
    uint32_t enables;
    float limThreshold;
    float limRelease;
    float postGain;
    float compTimeConstant;
    float compGranularity;
    float compTfResolution;
    float compFreqs[7];
    float compGains[7];
    int32_t bassMaxGain;
    int32_t toneFilterType;
    int32_t toneInterp;
    float toneFreqs[15];
    float toneGains[15];
    float tubeDrive;
    int32_t widenMode;
    int32_t xfeedMode;
    int32_t reverbPreset;
    char kernelPath[256];
    char ddcDevice[128];
    char liveprog[4][128];
    char streqText[256];
} VipJamJamesBlock;

typedef struct {
    int32_t enabled;
    int32_t configured;
    uint64_t processedFrames;
    int32_t sampleRate;
    int32_t versionCode;
    char versionName[64];
    char arch[32];
} VipJamStatus;

#ifdef __cplusplus
extern "C" {
#endif

int vipjam_shm_params_init(void *base, size_t len);
int vipjam_shm_write_viper(void *base, size_t len, const void *block);
int vipjam_shm_read_viper(const void *base, size_t len, void *out);
int vipjam_shm_write_james(void *base, size_t len, const VipJamJamesBlock *jb);
int vipjam_shm_read_james(const void *base, size_t len, VipJamJamesBlock *out);
uint32_t vipjam_shm_update_count(const void *base, size_t len);

int vipjam_shm_bulk_write(void *base, size_t len, uint32_t cmd,
                          const void *data, uint32_t size);
int vipjam_shm_bulk_read(const void *base, size_t len, uint32_t region,
                         uint32_t *cmd, const void **data, uint32_t *size);

int vipjam_shm_status_init(void *base, size_t len);
int vipjam_shm_status_write(void *base, size_t len, const VipJamStatus *st);
int vipjam_shm_status_read(const void *base, size_t len, VipJamStatus *out);

#ifdef __cplusplus
}
#endif

#endif
