#ifndef VIPJAM_CHAIN_ORDER_H
#define VIPJAM_CHAIN_ORDER_H

#include "VipJamStages.h"

#define VIPJAM_CHAIN_ORDER_MAX 32

#define VJ_ORDER_OK 0
#define VJ_ORDER_BAD_ARG -1
#define VJ_ORDER_LIMITER_NOT_LAST -2
#define VJ_ORDER_DUPLICATE -3

#ifdef __cplusplus
extern "C" {
#endif

static inline int vipjam_chain_order_validate(const int *stages,
                                              unsigned n) {
    if (!stages || n == 0 || n > VIPJAM_CHAIN_ORDER_MAX)
        return VJ_ORDER_BAD_ARG;
    if (stages[n - 1] != (int)VJ_STAGE_LIMITER)
        return VJ_ORDER_LIMITER_NOT_LAST;
    for (unsigned i = 0; i < n; i++) {
        if (stages[i] < 0 || stages[i] >= (int)VJ_STAGE_COUNT)
            return VJ_ORDER_BAD_ARG;
        for (unsigned j = i + 1; j < n; j++)
            if (stages[j] == stages[i]) return VJ_ORDER_DUPLICATE;
    }
    return VJ_ORDER_OK;
}

#ifdef __cplusplus
}
#endif

#endif
