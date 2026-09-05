#include "VipJamShm.h"
#include "VipJamParams.h"
#include <string.h>

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t activeSlot;
    uint32_t updateCount;
} ShmParamsHdr;

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t seq;
    uint32_t jamesSize;
} ShmExtHdr;

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t seq;
    uint32_t cmd;
    uint32_t dataSize;
    uint32_t reserved[3];
} ShmBulkHdr;

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t seq;
    uint32_t count;
    int32_t enabled;
    int32_t configured;
    uint64_t processedFrames;
    int32_t sampleRate;
    int32_t versionCode;
    char versionName[64];
    char arch[32];
} ShmStatusRaw;

static void put_u32(void *p, uint32_t v) { memcpy(p, &v, 4); }
static uint32_t get_u32(const void *p) { uint32_t v; memcpy(&v, p, 4); return v; }

static const uint8_t *at(const void *base, size_t off) {
    return (const uint8_t *)base + off;
}
static uint8_t *at_mut(void *base, size_t off) {
    return (uint8_t *)base + off;
}

static int check_params(const void *base, size_t len) {
    if (!base || len < VIPJAM_SHM_PARAMS_SIZE) return -1;
    if (get_u32(at(base, 0)) != VIPJAM_SHM_MAGIC) return -2;
    if (get_u32(at(base, 4)) != VIPJAM_SHM_VERSION) return -3;
    return 0;
}

int vipjam_shm_params_init(void *base, size_t len) {
    if (!base || len < VIPJAM_SHM_PARAMS_SIZE) return -1;
    memset(base, 0, VIPJAM_SHM_PARAMS_SIZE);
    put_u32(at_mut(base, 0), VIPJAM_SHM_MAGIC);
    put_u32(at_mut(base, 4), VIPJAM_SHM_VERSION);
    put_u32(at_mut(base, 8), 0);
    put_u32(at_mut(base, 12), 0);
    put_u32(at_mut(base, VIPJAM_SHM_EXT_BASE), VIPJAM_SHM_EXT_MAGIC);
    put_u32(at_mut(base, VIPJAM_SHM_EXT_BASE + 4), VIPJAM_SHM_EXT_VERSION);
    return 0;
}

static uint32_t active_slot_off(const void *base) {
    return get_u32(at(base, 8)) ? VIPJAM_SHM_SLOT_B : VIPJAM_SHM_SLOT_A;
}

int vipjam_shm_write_viper(void *base, size_t len, const void *block) {
    if (check_params(base, len) || !block) return -1;
    uint32_t cur = active_slot_off(base);
    uint32_t next = (cur == VIPJAM_SHM_SLOT_A) ? VIPJAM_SHM_SLOT_B
                                              : VIPJAM_SHM_SLOT_A;
    memcpy(at_mut(base, next), block, VIPJAM_SHM_SLOT_SIZE);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    put_u32(at_mut(base, 8), next == VIPJAM_SHM_SLOT_B ? 1 : 0);
    uint32_t n = get_u32(at(base, 12));
    put_u32(at_mut(base, 12), n + 1);
    return 0;
}

int vipjam_shm_read_viper(const void *base, size_t len, void *out) {
    if (check_params(base, len) || !out) return -1;
    memcpy(out, at(base, active_slot_off(base)), VIPJAM_SHM_SLOT_SIZE);
    return 0;
}

int vipjam_shm_write_james(void *base, size_t len, const VipJamJamesBlock *jb) {
    if (check_params(base, len) || !jb) return -1;
    if (sizeof(VipJamJamesBlock) > VIPJAM_SHM_EXT_SIZE - sizeof(ShmExtHdr))
        return -4;
    uint8_t *dst = at_mut(base, VIPJAM_SHM_EXT_BASE + sizeof(ShmExtHdr));
    memcpy(dst, jb, sizeof(VipJamJamesBlock));
    __atomic_thread_fence(__ATOMIC_RELEASE);
    uint32_t seq = get_u32(at(base, VIPJAM_SHM_EXT_BASE + 8));
    put_u32(at_mut(base, VIPJAM_SHM_EXT_BASE + 8), seq + 1);
    put_u32(at_mut(base, VIPJAM_SHM_EXT_BASE + 12),
            (uint32_t)sizeof(VipJamJamesBlock));
    return 0;
}

int vipjam_shm_read_james(const void *base, size_t len, VipJamJamesBlock *out) {
    if (check_params(base, len) || !out) return -1;
    if (get_u32(at(base, VIPJAM_SHM_EXT_BASE)) != VIPJAM_SHM_EXT_MAGIC) return -2;
    if (get_u32(at(base, VIPJAM_SHM_EXT_BASE + 12)) != sizeof(VipJamJamesBlock))
        return -3;
    memcpy(out, at(base, VIPJAM_SHM_EXT_BASE + sizeof(ShmExtHdr)),
           sizeof(VipJamJamesBlock));
    return 0;
}

uint32_t vipjam_shm_update_count(const void *base, size_t len) {
    if (check_params(base, len)) return 0;
    return get_u32(at(base, 12));
}

static uint32_t bulk_region(uint32_t cmd) {
    if (cmd == VIPJAM_BULK_CONV_PATH || cmd == VIPJAM_BULK_CONV_RESET)
        return VIPJAM_SHM_BULK_REGION;
    return 0;
}

int vipjam_shm_bulk_write(void *base, size_t len, uint32_t cmd,
                          const void *data, uint32_t size) {
    if (!base || len < VIPJAM_SHM_BULK_SIZE) return -1;
    if (cmd < VIPJAM_BULK_DDC || cmd > VIPJAM_BULK_VIPJAM_FULL) return -2;
    if (size > VIPJAM_SHM_BULK_MAX) return -3;
    if (size && !data) return -4;
    uint32_t region = bulk_region(cmd);
    uint8_t *hdr = at_mut(base, region);
    uint32_t seq = (get_u32(hdr) == VIPJAM_SHM_MAGIC) ? get_u32(hdr + 8) + 1 : 1;
    if (size) memcpy(hdr + VIPJAM_SHM_BULK_HDR, data, size);
    put_u32(hdr, VIPJAM_SHM_MAGIC);
    put_u32(hdr + 4, VIPJAM_SHM_VERSION);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    put_u32(hdr + 8, seq);
    put_u32(hdr + 12, cmd);
    put_u32(hdr + 16, size);
    return 0;
}

int vipjam_shm_bulk_read(const void *base, size_t len, uint32_t region,
                         uint32_t *cmd, const void **data, uint32_t *size) {
    if (!base || len < VIPJAM_SHM_BULK_SIZE) return -1;
    if (region != 0 && region != VIPJAM_SHM_BULK_REGION) return -2;
    const uint8_t *hdr = at(base, region);
    if (get_u32(hdr) != VIPJAM_SHM_MAGIC) return -3;
    if (get_u32(hdr + 4) != VIPJAM_SHM_VERSION) return -4;
    uint32_t sz = get_u32(hdr + 16);
    if (sz > VIPJAM_SHM_BULK_MAX) return -5;
    if (cmd) *cmd = get_u32(hdr + 12);
    if (data) *data = hdr + VIPJAM_SHM_BULK_HDR;
    if (size) *size = sz;
    return 0;
}

int vipjam_shm_status_init(void *base, size_t len) {
    if (!base || len < VIPJAM_SHM_STATUS_SIZE) return -1;
    memset(base, 0, VIPJAM_SHM_STATUS_SIZE);
    put_u32(at_mut(base, 0), VIPJAM_SHM_MAGIC);
    put_u32(at_mut(base, 4), VIPJAM_SHM_VERSION);
    return 0;
}

int vipjam_shm_status_write(void *base, size_t len, const VipJamStatus *st) {
    if (!base || len < VIPJAM_SHM_STATUS_SIZE || !st) return -1;
    if (get_u32(at(base, 0)) != VIPJAM_SHM_MAGIC) return -2;
    uint8_t *raw = at_mut(base, 0);
    uint32_t seq = get_u32(raw + 8);
    memcpy(raw + 20, &st->enabled, 4);
    memcpy(raw + 24, &st->configured, 4);
    memcpy(raw + 28, &st->processedFrames, 8);
    memcpy(raw + 36, &st->sampleRate, 4);
    memcpy(raw + 40, &st->versionCode, 4);
    memcpy(raw + 44, st->versionName, 64);
    memcpy(raw + 108, st->arch, 32);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    put_u32(raw + 8, seq + 1);
    uint32_t n = get_u32(raw + 12);
    put_u32(raw + 12, n + 1);
    return 0;
}

int vipjam_shm_status_read(const void *base, size_t len, VipJamStatus *out) {
    if (!base || len < VIPJAM_SHM_STATUS_SIZE || !out) return -1;
    if (get_u32(at(base, 0)) != VIPJAM_SHM_MAGIC) return -2;
    if (get_u32(at(base, 4)) != VIPJAM_SHM_VERSION) return -3;
    const uint8_t *raw = at(base, 0);
    memcpy(&out->enabled, raw + 20, 4);
    memcpy(&out->configured, raw + 24, 4);
    memcpy(&out->processedFrames, raw + 28, 8);
    memcpy(&out->sampleRate, raw + 36, 4);
    memcpy(&out->versionCode, raw + 40, 4);
    memcpy(out->versionName, raw + 44, 64);
    memcpy(out->arch, raw + 108, 32);
    return 0;
}
