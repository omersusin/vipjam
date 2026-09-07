#include "VipJamChain.h"
#include "VipJamParams.h"

#ifdef VIPJAM_HOST_BUILD
#include "audio_effect_stub.h"
#else
#include <hardware/audio_effect.h>
#include <log/log.h>
#define VIPJAM_OK 0
#define VIPJAM_EINVAL -EINVAL
#define VIPJAM_ENODATA -ENODATA
#endif

#include <string.h>

typedef struct {
    VipJamChain chain;
    bool enabled;
    int disableReason;
} vipjam_context_t;

static int32_t vipjam_handle_set_param(vipjam_context_t *ctx,
                                       const effect_param_t *param) {
    if (!ctx || !param) return VIPJAM_EINVAL;
    if (param->psize != sizeof(int32_t)) return VIPJAM_EINVAL;
    int32_t id;
    memcpy(&id, param->data, sizeof(id));
    int32_t fused = vipjam_shim_to_fused(id);
    if (fused == 0) return VIPJAM_EINVAL;
    if (fused == VIPJAM_MASTER_ENABLE && param->vsize == sizeof(int32_t)) {
        int32_t on;
        memcpy(&on, param->data + vj_padded_psize(param->psize), sizeof(on));
        ctx->enabled = (on != 0);
        return VIPJAM_OK;
    }
    const char *v = param->data + vj_padded_psize(param->psize);
    float v0 = 0.0f, v1 = 0.0f, v2 = 0.0f;
    if (param->vsize == sizeof(int32_t)) {
        int32_t t; memcpy(&t, v, 4); v0 = (float)t;
    } else if (param->vsize == 2 * sizeof(int32_t)) {
        int32_t t0, t1; memcpy(&t0, v, 4); memcpy(&t1, v + 4, 4);
        v0 = (float)t0; v1 = (float)t1;
    } else if (param->vsize == 3 * sizeof(int32_t)) {
        int32_t t0, t1, t2; memcpy(&t0, v, 4); memcpy(&t1, v + 4, 4); memcpy(&t2, v + 8, 4);
        v0 = (float)t0; v1 = (float)t1; v2 = (float)t2;
    } else {
        return VIPJAM_EINVAL;
    }
    return ctx->chain.setFusedParam(fused, v0, v1, v2);
}

static int32_t vipjam_handle_get_param(vipjam_context_t *ctx,
                                       const effect_param_t *req,
                                       effect_param_t *reply,
                                       uint32_t *replySize) {
    if (!ctx || !req || !reply || !replySize) return VIPJAM_EINVAL;
    if (*replySize < sizeof(effect_param_t) + req->psize + sizeof(int32_t)) return VIPJAM_EINVAL;
    if (req->psize != sizeof(int32_t)) return VIPJAM_EINVAL;
    int32_t id;
    memcpy(&id, req->data, sizeof(id));
    memcpy(reply, req, sizeof(effect_param_t) + req->psize);
    reply->status = 0;
    char *val = reply->data + vj_padded_psize(req->psize);
    switch (id) {
    case VJ_GET_ENABLED:
        reply->vsize = sizeof(int32_t);
        memcpy(val, &ctx->enabled, sizeof(int32_t));
        break;
    case VJ_GET_SAMPLING_RATE: {
        int32_t sr = static_cast<int32_t>(ctx->chain.samplingRate());
        reply->vsize = sizeof(int32_t);
        memcpy(val, &sr, sizeof(int32_t));
        break;
    }
    default:
        return VIPJAM_EINVAL;
    }
    *replySize = sizeof(effect_param_t) + req->psize + reply->vsize;
    return VIPJAM_OK;
}

int32_t vipjam_context_command(vipjam_context_t *ctx, uint32_t cmd,
                               uint32_t cmdSize, void *cmdData,
                               uint32_t *replySize, void *replyData) {
    if (!ctx || !replySize) return VIPJAM_EINVAL;
    switch (cmd) {
    case EFFECT_CMD_INIT:
        if (*replySize < sizeof(int)) return VIPJAM_EINVAL;
        *static_cast<int *>(replyData) = VIPJAM_OK;
        *replySize = sizeof(int);
        return VIPJAM_OK;
    case EFFECT_CMD_RESET:
        ctx->chain.reset();
        ctx->enabled = false;
        return VIPJAM_OK;
    case EFFECT_CMD_ENABLE:
        ctx->enabled = true;
        return VIPJAM_OK;
    case EFFECT_CMD_DISABLE:
        ctx->enabled = false;
        return VIPJAM_OK;
    case EFFECT_CMD_SET_PARAM:
        if (!cmdData) return VIPJAM_EINVAL;
        return vipjam_handle_set_param(
            ctx, static_cast<const effect_param_t *>(cmdData));
    case EFFECT_CMD_GET_PARAM:
        if (!cmdData || !replyData) return VIPJAM_EINVAL;
        return vipjam_handle_get_param(
            ctx, static_cast<const effect_param_t *>(cmdData),
            static_cast<effect_param_t *>(replyData), replySize);
    default:
        return VIPJAM_EINVAL;
    }
}

vipjam_context_t *vipjam_context_create(uint32_t samplingRate) {
    vipjam_context_t *ctx = new vipjam_context_t();
    ctx->chain.setSamplingRate(samplingRate);
    ctx->enabled = false;
    ctx->disableReason = 0;
    return ctx;
}

void vipjam_context_release(vipjam_context_t *ctx) { delete ctx; }
