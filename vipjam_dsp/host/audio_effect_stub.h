#ifndef VIPJAM_HOST_STUB_H
#define VIPJAM_HOST_STUB_H

#include <stdint.h>
#include <string.h>

typedef struct effect_param_s {
    int32_t status;
    uint32_t psize;
    uint32_t vsize;
    char data[];
} effect_param_t;

typedef struct {
    uint8_t data[16];
} effect_uuid_t;

enum {
    EFFECT_CMD_INIT = 0,
    EFFECT_CMD_SET_CONFIG = 1,
    EFFECT_CMD_RESET = 2,
    EFFECT_CMD_ENABLE = 3,
    EFFECT_CMD_DISABLE = 4,
    EFFECT_CMD_SET_PARAM = 5,
    EFFECT_CMD_SET_PARAM_DEFERRED = 6,
    EFFECT_CMD_SET_PARAM_COMMIT = 7,
    EFFECT_CMD_GET_PARAM = 8,
    EFFECT_CMD_GET_CONFIG = 9,
};

#define VIPJAM_OK 0
#define VIPJAM_EINVAL -22
#define VIPJAM_ENODATA -61

static inline uint32_t vj_padded_psize(uint32_t psize) {
    return ((psize + 3) / 4) * 4;
}

#endif
