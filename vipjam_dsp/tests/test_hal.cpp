#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <errno.h>
#include "../hal/VipJamEffect.cpp"

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

static effect_config_t makeConfig(uint32_t rate, uint32_t channels,
                                  uint8_t format) {
    effect_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.input_cfg.sampling_rate = rate;
    cfg.output_cfg.sampling_rate = rate;
    cfg.input_cfg.channels = channels;
    cfg.output_cfg.channels = channels;
    cfg.input_cfg.format = format;
    cfg.output_cfg.format = format;
    cfg.input_cfg.access_mode = EFFECT_BUFFER_ACCESS_WRITE;
    cfg.output_cfg.access_mode = EFFECT_BUFFER_ACCESS_WRITE;
    return cfg;
}

static int32_t sendParam(VipJamContext *ctx, int32_t id, int32_t v0) {
    char buf[sizeof(effect_param_t) + 8];
    effect_param_t *p = (effect_param_t *)buf;
    p->psize = 4;
    p->vsize = 4;
    memcpy(p->data, &id, 4);
    memcpy(p->data + 4, &v0, 4);
    int32_t reply = -999;
    uint32_t rs = 4;
    int32_t rc =
        ctx->handleCommand(EFFECT_CMD_SET_PARAM,
                           (uint32_t)(sizeof(effect_param_t) + 8), p, &rs,
                           &reply);
    return (rc == 0) ? reply : rc;
}

static int32_t sendParam2(VipJamContext *ctx, int32_t id, int32_t v0,
                          int32_t v1) {
    char buf[sizeof(effect_param_t) + 12];
    effect_param_t *p = (effect_param_t *)buf;
    p->psize = 4;
    p->vsize = 8;
    memcpy(p->data, &id, 4);
    memcpy(p->data + 4, &v0, 4);
    memcpy(p->data + 8, &v1, 4);
    int32_t reply = -999;
    uint32_t rs = 4;
    int32_t rc =
        ctx->handleCommand(EFFECT_CMD_SET_PARAM,
                           (uint32_t)(sizeof(effect_param_t) + 12), p, &rs,
                           &reply);
    return (rc == 0) ? reply : rc;
}

static int32_t sendParam3(VipJamContext *ctx, int32_t id, int32_t v0,
                          int32_t v1, int32_t v2) {
    char buf[sizeof(effect_param_t) + 16];
    effect_param_t *p = (effect_param_t *)buf;
    p->psize = 4;
    p->vsize = 12;
    memcpy(p->data, &id, 4);
    memcpy(p->data + 4, &v0, 4);
    memcpy(p->data + 8, &v1, 4);
    memcpy(p->data + 12, &v2, 4);
    int32_t reply = -999;
    uint32_t rs = 4;
    int32_t rc =
        ctx->handleCommand(EFFECT_CMD_SET_PARAM,
                           (uint32_t)(sizeof(effect_param_t) + 16), p, &rs,
                           &reply);
    return (rc == 0) ? reply : rc;
}

static int32_t sendBlob(VipJamContext *ctx, int32_t id, const void *data,
                        uint32_t n) {
    uint32_t need = (uint32_t)sizeof(effect_param_t) + 4 + n;
    char *buf = (char *)malloc(need);
    effect_param_t *p = (effect_param_t *)buf;
    p->psize = 4;
    p->vsize = n;
    memcpy(p->data, &id, 4);
    memcpy(p->data + 4, data, n);
    int32_t reply = -999;
    uint32_t rs = 4;
    int32_t rc =
        ctx->handleCommand(EFFECT_CMD_SET_PARAM, need, p, &rs, &reply);
    free(buf);
    return (rc == 0) ? reply : rc;
}

static uint32_t crc32ieee(const void *data, uint32_t len) {
    static uint32_t table[256];
    static int ready = 0;
    if (!ready) {
        for (uint32_t i = 0; i < 256; i++) {
            uint32_t c = i;
            for (int k = 0; k < 8; k++)
                c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            table[i] = c;
        }
        ready = 1;
    }
    uint32_t crc = 0xFFFFFFFFu;
    const unsigned char *b = (const unsigned char *)data;
    for (uint32_t i = 0; i < len; i++)
        crc = table[(crc ^ b[i]) & 0xFF] ^ (crc >> 8);
    return crc ^ 0xFFFFFFFFu;
}

static int32_t getParam(VipJamContext *ctx, int32_t id, char *out,
                        uint32_t outSize);
static int32_t getParam(VipJamContext *ctx, int32_t id, char *out,
                        uint32_t outSize) {
    char buf[sizeof(effect_param_t) + 4];
    effect_param_t *p = (effect_param_t *)buf;
    p->psize = 4;
    p->vsize = 0;
    memcpy(p->data, &id, 4);
    uint32_t rs = outSize;
    return ctx->handleCommand(EFFECT_CMD_GET_PARAM,
                              (uint32_t)sizeof(buf), p, &rs, out);
}

static int32_t getParamI32(VipJamContext *ctx, int32_t id, int32_t *out) {
    char gout[128];
    if (getParam(ctx, id, gout, sizeof(gout)) != 0) return -1;
    effect_param_t *rp = (effect_param_t *)gout;
    if (rp->vsize != 4) return -1;
    memcpy(out, rp->data + 4, 4);
    return 0;
}

static void test_lifecycle(void) {
    VipJamContext ctx;
    int32_t reply = -999;
    uint32_t rs = 4;
    CHECK(ctx.handleCommand(EFFECT_CMD_INIT, 0, nullptr, &rs, &reply) == 0 &&
              reply == 0,
          "hal init");
    effect_config_t cfg =
        makeConfig(48000, AUDIO_CHANNEL_OUT_STEREO,
                   AUDIO_FORMAT_PCM_FLOAT);
    rs = 4;
    reply = -999;
    CHECK(ctx.handleCommand(EFFECT_CMD_SET_CONFIG, sizeof(cfg), &cfg, &rs,
                            &reply) == 0 &&
              reply == 0,
          "hal set config stereo float");
    char gout[128];
    CHECK(getParam(&ctx, 2, gout, sizeof(gout)) == 0, "hal get configured");
    rs = 4;
    reply = -999;
    CHECK(ctx.handleCommand(EFFECT_CMD_ENABLE, 0, nullptr, &rs, &reply) == 0,
          "hal enable");
    CHECK(ctx.enabled(), "hal enabled flag");
    rs = 4;
    CHECK(ctx.handleCommand(EFFECT_CMD_DISABLE, 0, nullptr, &rs, &reply) == 0 &&
              !ctx.enabled(),
          "hal disable");
}

static void test_bad_config(void) {
    VipJamContext ctx;
    effect_config_t cfg =
        makeConfig(48000, AUDIO_CHANNEL_OUT_MONO,
                   AUDIO_FORMAT_PCM_FLOAT);
    int32_t reply = -999;
    uint32_t rs = 4;
    ctx.handleCommand(EFFECT_CMD_SET_CONFIG, sizeof(cfg), &cfg, &rs, &reply);
    char gout[128];
    getParam(&ctx, 2, gout, sizeof(gout));
    effect_param_t *rp = (effect_param_t *)gout;
    int32_t v = -1;
    memcpy(&v, rp->data + 4, 4);
    CHECK(v == 0, "hal mono config disables engine");
}

static void test_params(void) {
    VipJamContext ctx;
    CHECK(sendParam(&ctx, 36868, 1) == 0, "hal master on");
    CHECK(sendParam(&ctx, 65574, 1) == 0, "hal bass enable");
    CHECK(sendParam(&ctx, 65577, 300) == 0, "hal bass gain");
    CHECK(sendParam2(&ctx, 0x200B0, 150, 2) == 0, "hal clarity fused");
    CHECK(sendParam(&ctx, 123456789, 1) != 0, "hal unknown param rejected");
    char gout[128];
    CHECK(getParam(&ctx, 6, gout, sizeof(gout)) == 0, "hal get version code");
    CHECK(getParam(&ctx, 7, gout, sizeof(gout)) == 0 &&
              strstr(((effect_param_t *)gout)->data + 4, "0.1.0-fused") !=
                  nullptr,
          "hal get version name");
    CHECK(getParam(&ctx, 99, gout, sizeof(gout)) != 0,
          "hal unknown get rejected");
}

static void test_process(void) {
    VipJamContext ctx;
    effect_config_t cfg =
        makeConfig(48000, AUDIO_CHANNEL_OUT_STEREO,
                   AUDIO_FORMAT_PCM_FLOAT);
    int32_t reply = 0;
    uint32_t rs = 4;
    ctx.handleCommand(EFFECT_CMD_SET_CONFIG, sizeof(cfg), &cfg, &rs, &reply);
    ctx.handleCommand(EFFECT_CMD_ENABLE, 0, nullptr, &rs, &reply);

    const uint32_t frames = 48000;
    float *in = (float *)calloc(frames * 2, 4);
    float *out = (float *)calloc(frames * 2, 4);
    for (uint32_t i = 0; i < frames; i++) {
        float v = 0.2f * sinf(2.0f * 3.14159265f * 55.0f * (float)i / 48000.0f);
        in[i * 2] = v;
        in[i * 2 + 1] = v;
    }
    audio_buffer_t inBuf, outBuf;
    inBuf.frame_count = frames;
    inBuf.f32 = in;
    outBuf.frame_count = frames;
    outBuf.f32 = out;
    sendParam(&ctx, 65574, 1);
    sendParam(&ctx, 65577, 400);
    CHECK(ctx.process(&inBuf, &outBuf) == 0, "hal process float ok");
    double acc = 0.0;
    for (uint32_t i = frames - 24000; i < frames; i++)
        acc += (double)out[i * 2] * (double)out[i * 2];
    float rms = (float)sqrt(acc / 24000);
    CHECK(rms > 0.1414f * 1.5f, "hal bass audibly lifts 55hz");

    sendParam(&ctx, 36868, 0);
    memset(out, 0, frames * 2 * 4);
    CHECK(ctx.process(&inBuf, &outBuf) == 0, "hal master off processes");
    CHECK(memcmp(out, in, frames * 2 * 4) == 0, "hal master off bit-transparent");

    ctx.handleCommand(EFFECT_CMD_DISABLE, 0, nullptr, &rs, &reply);
    CHECK(ctx.process(&inBuf, &outBuf) == -ENODATA, "hal disabled no data");
    free(in);
    free(out);
}

static void test_eq_array(void) {
    VipJamContext ctx;
    unsigned char payload[256];
    memset(payload, 0, sizeof(payload));
    uint32_t count = 10;
    memcpy(payload, &count, 4);
    for (uint32_t i = 0; i < 10; i++) {
        float lvl = (i % 2) ? 3.0f : -3.0f;
        memcpy(payload + 4 + i * 4, &lvl, 4);
    }
    CHECK(sendBlob(&ctx, 65552, payload, sizeof(payload)) == 0,
          "hal eq array classic ok");
    CHECK(sendBlob(&ctx, 0x101A3, payload, sizeof(payload)) == 0,
          "hal eq array new ok");
    CHECK(ctx.chain()->isStageEnabled(VJ_STAGE_VIPER_IIR),
          "hal eq array enables iir");

    effect_config_t cfg =
        makeConfig(48000, AUDIO_CHANNEL_OUT_STEREO,
                   AUDIO_FORMAT_PCM_FLOAT);
    int32_t reply = 0;
    uint32_t rs = 4;
    ctx.handleCommand(EFFECT_CMD_SET_CONFIG, sizeof(cfg), &cfg, &rs, &reply);
    ctx.handleCommand(EFFECT_CMD_ENABLE, 0, nullptr, &rs, &reply);
    const uint32_t frames = 4096;
    float *in = (float *)calloc(frames * 2, 4);
    float *outA = (float *)calloc(frames * 2, 4);
    float *outB = (float *)calloc(frames * 2, 4);
    static const float freqs[] = {55.0f, 220.0f, 880.0f, 3520.0f, 14080.0f};
    for (uint32_t i = 0; i < frames; i++) {
        float v = 0.0f;
        for (unsigned k = 0; k < 5; k++)
            v += sinf(2.0f * 3.14159265f * freqs[k] * (float)i / 48000.0f);
        v *= 0.05f;
        in[i * 2] = v;
        in[i * 2 + 1] = v;
    }
    audio_buffer_t inBuf, outBuf;
    inBuf.frame_count = frames;
    inBuf.f32 = in;
    outBuf.frame_count = frames;
    memset(payload, 0, sizeof(payload));
    memcpy(payload, &count, 4);
    for (uint32_t i = 0; i < 10; i++) {
        float lvl = 12.0f;
        memcpy(payload + 4 + i * 4, &lvl, 4);
    }
    CHECK(sendBlob(&ctx, 65552, payload, sizeof(payload)) == 0,
          "hal eq array plus12 ok");
    outBuf.f32 = outA;
    CHECK(ctx.process(&inBuf, &outBuf) == 0, "hal eq array process plus12");
    for (uint32_t i = 0; i < 10; i++) {
        float lvl = -12.0f;
        memcpy(payload + 4 + i * 4, &lvl, 4);
    }
    CHECK(sendBlob(&ctx, 65552, payload, sizeof(payload)) == 0,
          "hal eq array minus12 ok");
    outBuf.f32 = outB;
    CHECK(ctx.process(&inBuf, &outBuf) == 0, "hal eq array process minus12");
    CHECK(memcmp(outA, outB, frames * 2 * 4) != 0,
          "hal eq array changes audio");
    free(in);
    free(outA);
    free(outB);
}

static void test_kernel_chunked_one(int32_t prep, int32_t chunk, int32_t commit,
                                    int32_t kernelId, float seed,
                                    const char *tag) {
    char name[64];
    VipJamContext ctx;
    const uint32_t total = 64;
    float kern[64];
    for (uint32_t i = 0; i < total; i++)
        kern[i] = seed / (float)(i + 1);
    unsigned char raw[64 * 4];
    memcpy(raw, kern, sizeof(raw));
    uint32_t crc = crc32ieee(raw, sizeof(raw));
    snprintf(name, sizeof(name), "hal %s prepare", tag);
    CHECK(sendParam3(&ctx, prep, (int32_t)total, 1, 0) == 0, name);
    for (int c = 0; c < 2; c++) {
        unsigned char wire[8192];
        memset(wire, 0, sizeof(wire));
        int32_t idx = c;
        uint32_t len = 32;
        memcpy(wire, &idx, 4);
        memcpy(wire + 4, &len, 4);
        memcpy(wire + 8, kern + c * 32, 32 * 4);
        snprintf(name, sizeof(name), "hal %s chunk%d", tag, c);
        CHECK(sendBlob(&ctx, chunk, wire, sizeof(wire)) == 0, name);
    }
    snprintf(name, sizeof(name), "hal %s commit", tag);
    CHECK(sendParam3(&ctx, commit, (int32_t)total, (int32_t)crc, kernelId) ==
              0,
          name);
    int32_t got = -1;
    snprintf(name, sizeof(name), "hal %s kernel id", tag);
    CHECK(getParamI32(&ctx, 5, &got) == 0 && got == kernelId, name);
}

static void test_kernel_chunked(void) {
    test_kernel_chunked_one(0x101B2, 0x101B3, 0x101B4, 1234, 1.0f, "conv new");
    test_kernel_chunked_one(65540, 65541, 65542, 5678, 0.5f, "conv classic");
}

static void test_array_reject(void) {
    VipJamContext ctx;
    unsigned char wire[8192];
    memset(wire, 0, sizeof(wire));
    int32_t idx = 0;
    uint32_t len = 32;
    memcpy(wire, &idx, 4);
    memcpy(wire + 4, &len, 4);
    CHECK(sendBlob(&ctx, 0x101B3, wire, sizeof(wire)) != 0,
          "hal chunk w/o prepare rejected");
    CHECK(sendParam3(&ctx, 0x101B2, 64, 1, 0) == 0, "hal reject prepare ok");
    len = 100;
    memcpy(wire + 4, &len, 4);
    CHECK(sendBlob(&ctx, 0x101B3, wire, sizeof(wire)) != 0,
          "hal oversize chunk rejected");
    len = 32;
    memcpy(wire + 4, &len, 4);
    CHECK(sendBlob(&ctx, 0x101B3, wire, sizeof(wire)) == 0,
          "hal reject chunk0 ok");
    idx = 5;
    memcpy(wire, &idx, 4);
    CHECK(sendBlob(&ctx, 0x101B3, wire, sizeof(wire)) != 0,
          "hal inconsistent index rejected");
    CHECK(sendParam3(&ctx, 0x101B4, 999, 0, 1) != 0,
          "hal inconsistent commit rejected");
    unsigned char eq[256];
    memset(eq, 0, sizeof(eq));
    uint32_t count = 100;
    memcpy(eq, &count, 4);
    CHECK(sendBlob(&ctx, 65552, eq, sizeof(eq)) != 0,
          "hal eq bad count rejected");
    unsigned char ddc[256];
    memset(ddc, 0, sizeof(ddc));
    uint32_t per = 100;
    memcpy(ddc, &per, 4);
    CHECK(sendBlob(&ctx, 0x101C1, ddc, sizeof(ddc)) != 0,
          "hal ddc oversize rejected");
    memset(ddc, 0, sizeof(ddc));
    per = 4;
    memcpy(ddc, &per, 4);
    for (uint32_t i = 0; i < 8; i++) {
        float c = 0.1f;
        memcpy(ddc + 4 + i * 4, &c, 4);
    }
    CHECK(sendBlob(&ctx, 0x101C1, ddc, sizeof(ddc)) == 0, "hal ddc round ok");
    CHECK(sendBlob(&ctx, 7777777, eq, sizeof(eq)) != 0,
          "hal unknown array rejected");
}

int main(void) {
    CHECK(EFFECT_CMD_ENABLE == 3, "hal cmd codes match aosp");
    test_lifecycle();
    test_bad_config();
    test_params();
    test_process();
    test_eq_array();
    test_kernel_chunked();
    test_array_reject();
    if (failures == 0) printf("ALL GREEN\n");
    else printf("%d FAILURES\n", failures);
    return failures ? 1 : 0;
}
