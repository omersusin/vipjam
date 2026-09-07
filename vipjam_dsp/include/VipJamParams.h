#ifndef VIPJAM_PARAMS_H
#define VIPJAM_PARAMS_H

#include <stdint.h>

#define VIPJAM_FORMAT_VERSION 1
#define VIPJAM_SHM_MAGIC 0x534D3456u
#define VIPJAM_SHM_VERSION 5

typedef enum {
    VIPJAM_MASTER_ENABLE     = 0x20001,
    VIPJAM_LIMITER           = 0x20010,
    VIPJAM_AGC               = 0x20020,
    VIPJAM_FET_MBC           = 0x20030,
    VIPJAM_BASS              = 0x20040,
    VIPJAM_BASS_MONO         = 0x20041,
    VIPJAM_EQ                = 0x20050,
    VIPJAM_DDC               = 0x20060,
    VIPJAM_CONVOLVER         = 0x20070,
    VIPJAM_SPACE             = 0x20080,
    VIPJAM_REVERB            = 0x20090,
    // Companion to VIPJAM_REVERB carrying (wet, dry) in (v0, v1); v2 ignored.
    // Why a second id: every fused record on the wire is (id, v0, v1, v2) --
    // effect_param_t vsize is 1..3 int32 slots, HAL/AIDL/SHM all assume it --
    // so the 5-tuple (room, width, damp, wet, dry) cannot fit one record.
    // The chain stores both halves and re-applies the full 5-arg engine call
    // on either half, so order does not matter. Absent => wet=0, dry=50
    // (engine Reverberation ctor defaults). Inside the 0x20000..0x200FF
    // passthrough range, so vipjam_shim_to_fused() forwards it untouched.
    VIPJAM_REVERB_WETDRY     = 0x20091,
    VIPJAM_DYN_SYS           = 0x200A0,
    VIPJAM_CLARITY_SPECEX    = 0x200B0,
    VIPJAM_XFEED             = 0x200C0,
    VIPJAM_TUBE              = 0x200D0,
    VIPJAM_OUT_VOL_PAN       = 0x200E0,
    VIPJAM_SPEAKER_CORR      = 0x200F0,
    VIPJAM_CHAIN_ORDER       = 0x200F1,
} vipjam_fused_param_t;

typedef enum {
    VJ_GET_ENABLED = 1,
    VJ_GET_DISABLE_REASON_OK = 2,
    VJ_GET_STREAMING = 3,
    VJ_GET_SAMPLING_RATE = 4,
    VJ_GET_KERNEL_ID = 5,
    VJ_GET_VERSION_CODE = 6,
    VJ_GET_VERSION_NAME = 7,
} vipjam_get_param_t;

#define VIPER_CLASSIC_UPDATE_STATUS 0x9002
#define VIPER_CLASSIC_RESET_STATUS 0x9003
#define VIPER_CLASSIC_CONV_EN 65538
#define VIPER_CLASSIC_FET_FIRST 65610
#define VIPER_CLASSIC_FET_LAST 65626

#define VIPER_NEW_RST_ALL 0x10101
#define VIPER_NEW_LIMITER_FIRST 0x10110
#define VIPER_NEW_LIMITER_LAST 0x10112
#define VIPER_NEW_PGC_FIRST 0x10120
#define VIPER_NEW_PGC_LAST 0x10123
#define VIPER_NEW_LUFS_FIRST 0x10130
#define VIPER_NEW_LUFS_LAST 0x10133
#define VIPER_NEW_FET_FIRST 0x10140
#define VIPER_NEW_FET_LAST 0x10150
#define VIPER_NEW_BASS_FIRST 0x10160
#define VIPER_NEW_BASS_LAST 0x10164
#define VIPER_NEW_BASS_MONO_FIRST 0x10170
#define VIPER_NEW_BASS_MONO_LAST 0x10174
#define VIPER_NEW_PSYCHO_FIRST 0x10180
#define VIPER_NEW_PSYCHO_LAST 0x10184
#define VIPER_NEW_SPECEX_FIRST 0x10190
#define VIPER_NEW_SPECEX_LAST 0x10192
#define VIPER_NEW_EQ_FIRST 0x101A0
#define VIPER_NEW_EQ_LAST 0x101A3
#define VIPER_NEW_CONV_FIRST 0x101B0
#define VIPER_NEW_CONV_LAST 0x101B5
#define VIPER_NEW_DDC_FIRST 0x101C0
#define VIPER_NEW_DDC_LAST 0x101C1
#define VIPER_NEW_FIELD_FIRST 0x101D0
#define VIPER_NEW_FIELD_LAST 0x101D3
#define VIPER_NEW_DIFF_FIRST 0x101E0
#define VIPER_NEW_DIFF_LAST 0x101E4
#define VIPER_NEW_STEREO_FIRST 0x101F0
#define VIPER_NEW_STEREO_LAST 0x101F5
#define VIPER_NEW_HSURR_FIRST 0x10200
#define VIPER_NEW_HSURR_LAST 0x10201
#define VIPER_NEW_REVERB_FIRST 0x10210
#define VIPER_NEW_REVERB_LAST 0x10215
#define VIPER_NEW_DYNSYS_FIRST 0x10220
#define VIPER_NEW_DYNSYS_LAST 0x10227
#define VIPER_NEW_CLARITY_FIRST 0x10230
#define VIPER_NEW_CLARITY_LAST 0x10232
#define VIPER_NEW_CURE_FIRST 0x10240
#define VIPER_NEW_CURE_LAST 0x10241
#define VIPER_NEW_TUBE 0x10250
#define VIPER_NEW_ANALOGX_FIRST 0x10260
#define VIPER_NEW_ANALOGX_LAST 0x10261
#define VIPER_NEW_SPK 0x10270
#define VIPER_NEW_MBC_FIRST 0x10280
#define VIPER_NEW_MBC_LAST 0x10293
#define VIPER_NEW_DYNEQ_FIRST 0x102A0
#define VIPER_NEW_DYNEQ_LAST 0x102A8

#define JDSP_REVERB_MODE 128
#define JDSP_WIDEN 137
#define JDSP_XFEED_MODE 188
#define JDSP_TUBE 150
#define JDSP_BASS_MAXGAIN 112
#define JDSP_EN_COMP 1200
#define JDSP_EN_BASS 1201
#define JDSP_EN_EQ 1202
#define JDSP_EN_REVERB 1203
#define JDSP_EN_WIDEN 1204
#define JDSP_EN_CONV 1205
#define JDSP_EN_TUBE 1206
#define JDSP_EN_XFEED 1208
#define JDSP_EN_ARBEQ 1210
#define JDSP_EN_DDC 1212
#define JDSP_EN_LIVEPROG 1213
#define JDSP_COMPANDER_FLOAT 115
#define JDSP_EQ_FLOAT 116
#define JDSP_LIMITER_FLOAT 1500
#define JDSP_STR_ALLOC 8888
#define JDSP_CONV_INFO 9999
#define JDSP_CONV_CHUNK 12000
#define JDSP_STR_CHUNK 12001
#define JDSP_COMMIT_CONV 10004
#define JDSP_COMMIT_ARBEQ 10006
#define JDSP_COMMIT_DDC 10009
#define JDSP_COMMIT_LIVEPROG 10010
#define JDSP_GET_INIT_COUNT 19998
#define JDSP_GET_BLOCK_SIZE 19999
#define JDSP_GET_BLOCK_MAX 20000
#define JDSP_GET_FS 20001
#define JDSP_GET_PID 20002

static inline int32_t vipjam_shim_to_fused(int32_t id) {
    if (id == VIPJAM_MASTER_ENABLE) return VIPJAM_MASTER_ENABLE;
    if (id >= 0x20000 && id <= 0x200FF) return id;
    if (id == VIPER_NEW_RST_ALL) return VIPJAM_MASTER_ENABLE;
    if (id >= VIPER_NEW_LIMITER_FIRST && id <= VIPER_NEW_LIMITER_LAST)
        return VIPJAM_LIMITER;
    if (id >= VIPER_NEW_PGC_FIRST && id <= VIPER_NEW_LUFS_LAST)
        return VIPJAM_AGC;
    if ((id >= VIPER_NEW_FET_FIRST && id <= VIPER_NEW_FET_LAST) ||
        (id >= VIPER_NEW_MBC_FIRST && id <= VIPER_NEW_MBC_LAST))
        return VIPJAM_FET_MBC;
    if (id >= VIPER_NEW_BASS_MONO_FIRST && id <= VIPER_NEW_BASS_MONO_LAST)
        return VIPJAM_BASS_MONO;
    if (id >= VIPER_NEW_BASS_FIRST && id <= VIPER_NEW_PSYCHO_LAST)
        return VIPJAM_BASS;
    if (id >= VIPER_NEW_SPECEX_FIRST && id <= VIPER_NEW_SPECEX_LAST)
        return VIPJAM_CLARITY_SPECEX;
    if (id >= VIPER_NEW_EQ_FIRST && id <= VIPER_NEW_EQ_LAST)
        return VIPJAM_EQ;
    if (id >= VIPER_NEW_CONV_FIRST && id <= VIPER_NEW_CONV_LAST)
        return VIPJAM_CONVOLVER;
    if (id >= VIPER_NEW_DDC_FIRST && id <= VIPER_NEW_DDC_LAST)
        return VIPJAM_DDC;
    if ((id >= VIPER_NEW_FIELD_FIRST && id <= VIPER_NEW_DIFF_LAST) ||
        (id >= VIPER_NEW_STEREO_FIRST && id <= VIPER_NEW_HSURR_LAST))
        return VIPJAM_SPACE;
    if (id >= VIPER_NEW_REVERB_FIRST && id <= VIPER_NEW_REVERB_LAST)
        return VIPJAM_REVERB;
    if (id >= VIPER_NEW_DYNSYS_FIRST && id <= VIPER_NEW_DYNSYS_LAST)
        return VIPJAM_DYN_SYS;
    if (id >= VIPER_NEW_CLARITY_FIRST && id <= VIPER_NEW_CLARITY_LAST)
        return VIPJAM_CLARITY_SPECEX;
    if (id >= VIPER_NEW_CURE_FIRST && id <= VIPER_NEW_CURE_LAST)
        return VIPJAM_XFEED;
    if (id == VIPER_NEW_TUBE) return VIPJAM_TUBE;
    if (id >= VIPER_NEW_ANALOGX_FIRST && id <= VIPER_NEW_ANALOGX_LAST)
        return VIPJAM_TUBE;
    if (id == VIPER_NEW_SPK) return VIPJAM_SPEAKER_CORR;
    if (id >= VIPER_NEW_DYNEQ_FIRST && id <= VIPER_NEW_DYNEQ_LAST)
        return VIPJAM_EQ;
    if (id >= 65538 && id <= 65626) {
        if (id <= 65543) return VIPJAM_CONVOLVER;
        if (id <= 65545) return VIPJAM_SPACE;
        if (id <= 65547) return VIPJAM_DDC;
        if (id <= 65550) return VIPJAM_CLARITY_SPECEX;
        if (id <= 65552) return VIPJAM_EQ;
        if (id <= 65558) return VIPJAM_SPACE;
        if (id <= 65564) return VIPJAM_REVERB;
        if (id <= 65568) return VIPJAM_AGC;
        if (id <= 65573) return VIPJAM_DYN_SYS;
        if (id <= 65577) return VIPJAM_BASS;
        if (id <= 65580) return VIPJAM_CLARITY_SPECEX;
        if (id <= 65582) return VIPJAM_XFEED;
        if (id == 65583) return VIPJAM_TUBE;
        if (id <= 65585) return VIPJAM_TUBE;
        if (id <= 65588) return VIPJAM_OUT_VOL_PAN;
        if (id == 65603) return VIPJAM_SPEAKER_CORR;
        return VIPJAM_FET_MBC;
    }
    switch (id) {
    case JDSP_LIMITER_FLOAT: return VIPJAM_LIMITER;
    case JDSP_COMPANDER_FLOAT: case JDSP_EN_COMP: return VIPJAM_AGC;
    case JDSP_BASS_MAXGAIN: case JDSP_EN_BASS: return VIPJAM_BASS;
    case JDSP_EQ_FLOAT: case JDSP_EN_EQ:
    case JDSP_EN_ARBEQ: return VIPJAM_EQ;
    case JDSP_EN_DDC: return VIPJAM_DDC;
    case JDSP_EN_CONV: case JDSP_CONV_INFO:
    case JDSP_CONV_CHUNK: case JDSP_COMMIT_CONV: return VIPJAM_CONVOLVER;
    case JDSP_WIDEN: case JDSP_EN_WIDEN: return VIPJAM_SPACE;
    case JDSP_REVERB_MODE: case JDSP_EN_REVERB: return VIPJAM_REVERB;
    case JDSP_XFEED_MODE: case JDSP_EN_XFEED: return VIPJAM_XFEED;
    case JDSP_TUBE: case JDSP_EN_TUBE: return VIPJAM_TUBE;
    case JDSP_EN_LIVEPROG: case JDSP_COMMIT_LIVEPROG: return VIPJAM_SPACE;
    case JDSP_STR_ALLOC: case JDSP_STR_CHUNK:
    case JDSP_COMMIT_ARBEQ: case JDSP_COMMIT_DDC: return VIPJAM_EQ;
    default: break;
    }
    return 0;
}

#endif
