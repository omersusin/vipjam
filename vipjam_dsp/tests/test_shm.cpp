#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "VipJamShm.h"
#include "VipJamParams.h"

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

static void test_params_transport(void) {
    uint8_t *mem = (uint8_t *)calloc(1, VIPJAM_SHM_PARAMS_SIZE);
    CHECK(vipjam_shm_params_init(0, VIPJAM_SHM_PARAMS_SIZE) != 0,
          "shm params init rejects null");
    CHECK(vipjam_shm_params_init(mem, 100) != 0,
          "shm params init rejects short buffer");
    CHECK(vipjam_shm_params_init(mem, VIPJAM_SHM_PARAMS_SIZE) == 0,
          "shm params init ok");
    CHECK(vipjam_shm_update_count(mem, VIPJAM_SHM_PARAMS_SIZE) == 0,
          "shm update count starts zero");

    uint8_t block[VIPJAM_SHM_SLOT_SIZE];
    for (size_t i = 0; i < sizeof(block); i++) block[i] = (uint8_t)(i * 7 + 1);
    CHECK(vipjam_shm_write_viper(mem, VIPJAM_SHM_PARAMS_SIZE, block) == 0,
          "shm viper write ok");
    CHECK(vipjam_shm_update_count(mem, VIPJAM_SHM_PARAMS_SIZE) == 1,
          "shm update count bumps");
    uint8_t out[VIPJAM_SHM_SLOT_SIZE];
    memset(out, 0, sizeof(out));
    CHECK(vipjam_shm_read_viper(mem, VIPJAM_SHM_PARAMS_SIZE, out) == 0,
          "shm viper read ok");
    CHECK(memcmp(block, out, sizeof(block)) == 0, "shm viper round trip");

    for (size_t i = 0; i < sizeof(block); i++) block[i] = (uint8_t)(255 - i);
    vipjam_shm_write_viper(mem, VIPJAM_SHM_PARAMS_SIZE, block);
    vipjam_shm_read_viper(mem, VIPJAM_SHM_PARAMS_SIZE, out);
    CHECK(memcmp(block, out, sizeof(block)) == 0, "shm slot flip keeps latest");

    mem[0] = 0x00;
    CHECK(vipjam_shm_read_viper(mem, VIPJAM_SHM_PARAMS_SIZE, out) != 0,
          "shm viper rejects bad magic");
    CHECK(vipjam_shm_write_viper(mem, VIPJAM_SHM_PARAMS_SIZE, block) != 0,
          "shm viper write rejects bad magic");
    free(mem);
}

static void test_james_transport(void) {
    uint8_t *mem = (uint8_t *)calloc(1, VIPJAM_SHM_PARAMS_SIZE);
    vipjam_shm_params_init(mem, VIPJAM_SHM_PARAMS_SIZE);
    VipJamJamesBlock jb;
    memset(&jb, 0, sizeof(jb));
    jb.enables = VIPJAM_JAMES_EN_TONE | VIPJAM_JAMES_EN_BASS
               | VIPJAM_JAMES_EN_LIVEPROG;
    jb.limThreshold = -0.1f;
    jb.limRelease = 60.0f;
    jb.bassMaxGain = 5;
    jb.toneFilterType = 3;
    for (int i = 0; i < 15; i++) {
        jb.toneFreqs[i] = 25.0f + i * 100.0f;
        jb.toneGains[i] = (float)(i - 7) * 0.5f;
    }
    jb.xfeedMode = 5;
    strcpy(jb.kernelPath, "demo.irs");
    strcpy(jb.liveprog[0], "demo.eel");
    strcpy(jb.streqText, "GraphicEQ: 1.0 2.0;");
    CHECK(vipjam_shm_write_james(mem, VIPJAM_SHM_PARAMS_SIZE, &jb) == 0,
          "shm james write ok");
    VipJamJamesBlock back;
    memset(&back, 0, sizeof(back));
    CHECK(vipjam_shm_read_james(mem, VIPJAM_SHM_PARAMS_SIZE, &back) == 0,
          "shm james read ok");
    CHECK(back.enables == jb.enables, "shm james enables round trip");
    CHECK(back.limThreshold == jb.limThreshold, "shm james float exact");
    CHECK(memcmp(back.toneFreqs, jb.toneFreqs, sizeof(jb.toneFreqs)) == 0,
          "shm james curve round trip");
    CHECK(strcmp(back.kernelPath, "demo.irs") == 0, "shm james path round trip");
    CHECK(strcmp(back.liveprog[0], "demo.eel") == 0, "shm james script ref");
    CHECK(strcmp(back.streqText, "GraphicEQ: 1.0 2.0;") == 0,
          "shm james streq text");
    CHECK(sizeof(VipJamJamesBlock) <= VIPJAM_SHM_EXT_SIZE - 16,
          "shm james block fits ext region");
    free(mem);
}

static void test_bulk_transport(void) {
    uint8_t *mem = (uint8_t *)calloc(1, VIPJAM_SHM_BULK_SIZE);
    const char *path = "/data/local/tmp/vipjam/kernel/demo.irs";
    CHECK(vipjam_shm_bulk_write(mem, VIPJAM_SHM_BULK_SIZE,
                                VIPJAM_BULK_CONV_PATH, path,
                                (uint32_t)strlen(path)) == 0,
          "shm bulk conv path write ok");
    uint32_t cmd = 0;
    const void *data = 0;
    uint32_t size = 0;
    CHECK(vipjam_shm_bulk_read(mem, VIPJAM_SHM_BULK_SIZE,
                               VIPJAM_SHM_BULK_REGION, &cmd, &data,
                               &size) == 0,
          "shm bulk conv path read ok");
    CHECK(cmd == VIPJAM_BULK_CONV_PATH, "shm bulk cmd round trip");
    CHECK(size == strlen(path) && memcmp(data, path, size) == 0,
          "shm bulk path bytes round trip");

    float ddc[] = {44100.0f, 2.0f, 20.0f, 1.0f, 0.5f, 48000.0f, 2.0f};
    CHECK(vipjam_shm_bulk_write(mem, VIPJAM_SHM_BULK_SIZE, VIPJAM_BULK_DDC,
                                ddc, sizeof(ddc)) == 0,
          "shm bulk ddc write ok");
    CHECK(vipjam_shm_bulk_read(mem, VIPJAM_SHM_BULK_SIZE, 0, &cmd, &data,
                               &size) == 0,
          "shm bulk ddc read ok");
    CHECK(cmd == VIPJAM_BULK_DDC && size == sizeof(ddc)
          && memcmp(data, ddc, size) == 0,
          "shm bulk ddc floats round trip");

    CHECK(vipjam_shm_bulk_write(mem, VIPJAM_SHM_BULK_SIZE,
                                VIPJAM_BULK_CONV_RESET, 0, 0) == 0,
          "shm bulk reset write ok");
    CHECK(vipjam_shm_bulk_read(mem, VIPJAM_SHM_BULK_SIZE,
                               VIPJAM_SHM_BULK_REGION, &cmd, &data,
                               &size) == 0 && cmd == VIPJAM_BULK_CONV_RESET
          && size == 0,
          "shm bulk reset round trip");
    CHECK(vipjam_shm_bulk_write(mem, VIPJAM_SHM_BULK_SIZE, 99, 0, 0) != 0,
          "shm bulk rejects bad cmd");
    CHECK(vipjam_shm_bulk_write(mem, VIPJAM_SHM_BULK_SIZE, VIPJAM_BULK_DDC,
                                ddc, VIPJAM_SHM_BULK_MAX + 1) != 0,
          "shm bulk rejects oversize");
    free(mem);
}

static void test_status_transport(void) {
    uint8_t *mem = (uint8_t *)calloc(1, VIPJAM_SHM_STATUS_SIZE);
    CHECK(vipjam_shm_status_init(mem, VIPJAM_SHM_STATUS_SIZE) == 0,
          "shm status init ok");
    VipJamStatus st;
    memset(&st, 0, sizeof(st));
    st.enabled = 1;
    st.configured = 1;
    st.processedFrames = 123456789ULL;
    st.sampleRate = 48000;
    st.versionCode = 1;
    strcpy(st.versionName, "0.1.0-fused");
    strcpy(st.arch, "ARM64");
    CHECK(vipjam_shm_status_write(mem, VIPJAM_SHM_STATUS_SIZE, &st) == 0,
          "shm status write ok");
    VipJamStatus back;
    memset(&back, 0, sizeof(back));
    CHECK(vipjam_shm_status_read(mem, VIPJAM_SHM_STATUS_SIZE, &back) == 0,
          "shm status read ok");
    CHECK(back.enabled == 1 && back.sampleRate == 48000
          && back.processedFrames == 123456789ULL,
          "shm status scalars round trip");
    CHECK(strcmp(back.versionName, "0.1.0-fused") == 0
          && strcmp(back.arch, "ARM64") == 0,
          "shm status strings round trip");
    free(mem);
}

int main(void) {
    CHECK(VIPJAM_SHM_MAGIC == 0x534D3456u, "shm magic matches v4a");
    CHECK(VIPJAM_SHM_VERSION == 5, "shm version matches v4a");
    CHECK(VIPJAM_SHM_SLOT_A + VIPJAM_SHM_SLOT_SIZE <= VIPJAM_SHM_SLOT_B,
          "shm slots do not overlap");
    CHECK(VIPJAM_SHM_SLOT_B + VIPJAM_SHM_SLOT_SIZE <= VIPJAM_SHM_EXT_BASE,
          "shm slots stay clear of ext");
    CHECK(VIPJAM_SHM_EXT_BASE + VIPJAM_SHM_EXT_SIZE <= VIPJAM_SHM_PARAMS_SIZE,
          "shm ext fits params file");
    test_params_transport();
    test_james_transport();
    test_bulk_transport();
    test_status_transport();
    if (failures == 0) printf("ALL GREEN\n");
    else printf("%d FAILURES\n", failures);
    return failures ? 1 : 0;
}
