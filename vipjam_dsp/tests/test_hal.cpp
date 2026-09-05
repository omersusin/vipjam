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

int main(void) {
    CHECK(EFFECT_CMD_ENABLE == 3, "hal cmd codes match aosp");
    test_lifecycle();
    test_bad_config();
    test_params();
    test_process();
    if (failures == 0) printf("ALL GREEN\n");
    else printf("%d FAILURES\n", failures);
    return failures ? 1 : 0;
}
