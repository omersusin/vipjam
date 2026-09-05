#include "vipjam_audio_hal.h"
#include "VipJamChain.h"
#include "VipJamParams.h"
#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <string>
#include <vector>

#define VJ_VERSION_CODE 1
#define VJ_VERSION_NAME "0.1.0-fused"

// Array/chunk protocol IDs: classic 655xx (ViPER4Android.h) + new 0x101xx
// (ViperParams.kt) alias the same wire format.
//   EQ levels array: 256B = u32 count + float dB levels (eqBandLevelsToBytes).
//   DDC coeffs array: 256/1024B = u32 floatsPerRate + 44.1k floats + 48k floats.
//   Convolver upload: PREPARE [totalFloats,channels,resetFlag] (vsize 12),
//     SET_BUFFER 8192B = [s32 index,u32 len,floats...] (max 2046 floats/chunk,
//     CRC32-IEEE over LE float bytes), COMMIT [totalFloats,crc32,kernelId].
#define VJ_PID_EQ_LEVELS_CLASSIC 65552
#define VJ_PID_EQ_LEVELS_NEW 0x101A3
#define VJ_PID_DDC_CLASSIC 65547
#define VJ_PID_DDC_NEW 0x101C1
#define VJ_PID_CONV_PREP_CLASSIC 65540
#define VJ_PID_CONV_PREP_NEW 0x101B2
#define VJ_PID_CONV_CHUNK_CLASSIC 65541
#define VJ_PID_CONV_CHUNK_NEW 0x101B3
#define VJ_PID_CONV_COMMIT_CLASSIC 65542
#define VJ_PID_CONV_COMMIT_NEW 0x101B4
#define VJ_KERNEL_CHUNK_BYTES 8192
#define VJ_KERNEL_MAX_FLOATS_PER_CHUNK 2046
#define VJ_KERNEL_MAX_TOTAL_FLOATS (1u << 22)
#define VJ_EQ_MAX_BANDS 31
#if defined(__aarch64__)
#define VJ_ARCH "ARM64"
#elif defined(__arm__)
#define VJ_ARCH "ARM"
#elif defined(__x86_64__)
#define VJ_ARCH "x86_64"
#elif defined(__i386__)
#define VJ_ARCH "x86"
#else
#define VJ_ARCH "unknown"
#endif

struct VipJamHandle {
    const effect_interface_s *interface;
    class VipJamContext *context;
};

class VipJamContext {
public:
    enum class DisableReason : int32_t {
        UNKNOWN = -1,
        NONE = 0,
        INVALID_FRAME_COUNT,
        INVALID_SAMPLING_RATE,
        INVALID_CHANNEL_COUNT,
        INVALID_FORMAT,
    };

    VipJamContext()
        : disableReason_(DisableReason::NONE), enable_(false),
          processedFrames_(0), lastStreamingFrames_(0), kernelTotal_(0),
          kernelChannels_(0), kernelNext_(0) {
        memset(&config_, 0, sizeof(config_));
    }

    int32_t handleCommand(uint32_t cmdCode, uint32_t cmdSize, void *cmdData,
                          uint32_t *replySize, void *replyData) {
        switch (cmdCode) {
        case EFFECT_CMD_INIT:
            if (replySize == nullptr || *replySize != sizeof(int32_t)) return -EINVAL;
            *static_cast<int32_t *>(replyData) = 0;
            return 0;
        case EFFECT_CMD_SET_CONFIG:
            if (cmdSize < sizeof(effect_config_t) || replySize == nullptr ||
                *replySize != sizeof(int32_t))
                return -EINVAL;
            handleSetConfig(static_cast<effect_config_t *>(cmdData));
            *static_cast<int32_t *>(replyData) = 0;
            return 0;
        case EFFECT_CMD_RESET:
            if (replySize == nullptr || *replySize != sizeof(int32_t)) return -EINVAL;
            chain_.reset();
            kernelReset();
            *static_cast<int32_t *>(replyData) = 0;
            return 0;
        case EFFECT_CMD_ENABLE:
            if (replySize == nullptr || *replySize != sizeof(int32_t)) return -EINVAL;
            chain_.reset();
            enable_ = true;
            *static_cast<int32_t *>(replyData) = 0;
            return 0;
        case EFFECT_CMD_DISABLE:
            if (replySize == nullptr || *replySize != sizeof(int32_t)) return -EINVAL;
            enable_ = false;
            *static_cast<int32_t *>(replyData) = 0;
            return 0;
        case EFFECT_CMD_SET_PARAM:
            if (cmdSize < sizeof(effect_param_t) || replySize == nullptr ||
                *replySize != sizeof(int32_t))
                return -EINVAL;
            return handleSetParam(static_cast<effect_param_t *>(cmdData),
                                  replyData);
        case EFFECT_CMD_GET_PARAM:
            if (cmdSize < sizeof(effect_param_t) || replySize == nullptr ||
                replyData == nullptr)
                return -EINVAL;
            return handleGetParam(static_cast<effect_param_t *>(cmdData),
                                  static_cast<effect_param_t *>(replyData),
                                  replySize);
        case EFFECT_CMD_GET_CONFIG:
            if (replySize == nullptr || *replySize != sizeof(effect_config_t))
                return -EINVAL;
            *static_cast<effect_config_t *>(replyData) = config_;
            return 0;
        default:
            break;
        }
        return -EINVAL;
    }

    int32_t process(audio_buffer_t *inBuffer, audio_buffer_t *outBuffer) {
        if (disableReason_ != DisableReason::NONE) return -EINVAL;
        if (!enable_) return -ENODATA;
        if (inBuffer == nullptr || outBuffer == nullptr) return -EINVAL;
        if (inBuffer->frame_count != outBuffer->frame_count ||
            inBuffer->frame_count == 0)
            return -EINVAL;
        uint32_t frames = inBuffer->frame_count;
        if (work_.size() < frames * 2) work_.resize(frames * 2);
        audio_format_t fmt = (audio_format_t)config_.input_cfg.format;
        if (fmt == AUDIO_FORMAT_PCM_16_BIT) {
            const int16_t *in = inBuffer->s16;
            for (uint32_t i = 0; i < frames * 2; i++)
                work_[i] = (float)in[i] / 32768.0f;
        } else if (fmt == AUDIO_FORMAT_PCM_32_BIT) {
            const int32_t *in = inBuffer->s32;
            for (uint32_t i = 0; i < frames * 2; i++)
                work_[i] = (float)in[i] / 2147483648.0f;
        } else {
            const float *in = inBuffer->f32;
            for (uint32_t i = 0; i < frames * 2; i++) work_[i] = in[i];
        }
        chain_.process(work_);
        bool accumulate =
            config_.output_cfg.access_mode == EFFECT_BUFFER_ACCESS_ACCUMULATE;
        if (fmt == AUDIO_FORMAT_PCM_16_BIT) {
            int16_t *out = outBuffer->s16;
            for (uint32_t i = 0; i < frames * 2; i++) {
                float v = work_[i] * 32768.0f;
                if (v > 32767.0f) v = 32767.0f;
                else if (v < -32768.0f) v = -32768.0f;
                int16_t s = (int16_t)v;
                out[i] = accumulate ? out[i] + s : s;
            }
        } else if (fmt == AUDIO_FORMAT_PCM_32_BIT) {
            int32_t *out = outBuffer->s32;
            for (uint32_t i = 0; i < frames * 2; i++) {
                double v = (double)work_[i] * 2147483648.0;
                if (v > 2147483647.0) v = 2147483647.0;
                else if (v < -2147483648.0) v = -2147483648.0;
                int32_t s = (int32_t)v;
                out[i] = accumulate ? out[i] + s : s;
            }
        } else {
            float *out = outBuffer->f32;
            for (uint32_t i = 0; i < frames * 2; i++)
                out[i] = accumulate ? out[i] + work_[i] : work_[i];
        }
        processedFrames_ += frames;
        return 0;
    }

    VipJamChain *chain() { return &chain_; }
    bool enabled() const { return enable_; }

private:
    void handleSetConfig(effect_config_t *cfg) {
        config_ = *cfg;
        if (config_.input_cfg.sampling_rate != config_.output_cfg.sampling_rate) {
            disableReason_ = DisableReason::INVALID_SAMPLING_RATE;
            return;
        }
        if (config_.input_cfg.channels != config_.output_cfg.channels ||
            config_.input_cfg.channels != AUDIO_CHANNEL_OUT_STEREO) {
            disableReason_ = DisableReason::INVALID_CHANNEL_COUNT;
            return;
        }
        audio_format_t fmt = (audio_format_t)config_.input_cfg.format;
        if (fmt != AUDIO_FORMAT_PCM_16_BIT &&
            fmt != AUDIO_FORMAT_PCM_32_BIT && fmt != AUDIO_FORMAT_PCM_FLOAT) {
            disableReason_ = DisableReason::INVALID_FORMAT;
            return;
        }
        disableReason_ = DisableReason::NONE;
        chain_.setSamplingRate(config_.input_cfg.sampling_rate);
        chain_.reset();
        kernelReset();
        work_.clear();
    }

    int32_t handleSetParam(effect_param_t *param, void *replyData) {
        if (param->psize != sizeof(int32_t)) return -EINVAL;
        int32_t id;
        memcpy(&id, param->data, 4);
        uint32_t off = ((param->psize + 3) / 4) * 4;
        const char *v = param->data + off;
        int rc;
        if (param->vsize == 256 || param->vsize == 1024) {
            rc = handleArrayParam(id, v, param->vsize);
            *static_cast<int32_t *>(replyData) = rc;
            return rc;
        }
        if (param->vsize == VJ_KERNEL_CHUNK_BYTES) {
            rc = handleKernelChunk(id, v);
            *static_cast<int32_t *>(replyData) = rc;
            return rc;
        }
        if (param->vsize == 3 * sizeof(int32_t) &&
            (isConvPrepare(id) || isConvCommit(id))) {
            rc = handleKernelControl(id, v);
            *static_cast<int32_t *>(replyData) = rc;
            return rc;
        }
        float v0 = 0.0f, v1 = 0.0f, v2 = 0.0f;
        if (param->vsize == sizeof(int32_t)) {
            int32_t t;
            memcpy(&t, v, 4);
            v0 = (float)t;
        } else if (param->vsize == 2 * sizeof(int32_t)) {
            int32_t t0, t1;
            memcpy(&t0, v, 4);
            memcpy(&t1, v + 4, 4);
            v0 = (float)t0;
            v1 = (float)t1;
        } else if (param->vsize == 3 * sizeof(int32_t)) {
            int32_t t0, t1, t2;
            memcpy(&t0, v, 4);
            memcpy(&t1, v + 4, 4);
            memcpy(&t2, v + 8, 4);
            v0 = (float)t0;
            v1 = (float)t1;
            v2 = (float)t2;
        } else {
            return -EINVAL;
        }
        rc = chain_.setFusedParam(id, v0, v1, v2);
        *static_cast<int32_t *>(replyData) = rc;
        return rc;
    }

    int32_t handleGetParam(effect_param_t *cmd, effect_param_t *reply,
                           uint32_t *replySize) {
        if (cmd->psize != sizeof(int32_t)) return -EINVAL;
        int32_t id;
        memcpy(&id, cmd->data, 4);
        int32_t i32 = 0;
        const char *bytes = nullptr;
        uint32_t nbytes = 0;
        char strbuf[64];
        switch (id) {
        case 1:
            i32 = (enable_ && chain_.isMasterEnabled()) ? 1 : 0;
            bytes = (const char *)&i32;
            nbytes = 4;
            break;
        case 2:
            i32 = (disableReason_ == DisableReason::NONE) ? 1 : 0;
            bytes = (const char *)&i32;
            nbytes = 4;
            break;
        case 3:
            i32 = (processedFrames_ != lastStreamingFrames_) ? 1 : 0;
            lastStreamingFrames_ = processedFrames_;
            bytes = (const char *)&i32;
            nbytes = 4;
            break;
        case 4:
            i32 = (int32_t)chain_.samplingRate();
            bytes = (const char *)&i32;
            nbytes = 4;
            break;
        case 5:
            i32 = (int32_t)chain_.viperKernelID();
            bytes = (const char *)&i32;
            nbytes = 4;
            break;
        case 6:
            i32 = VJ_VERSION_CODE;
            bytes = (const char *)&i32;
            nbytes = 4;
            break;
        case 7:
            bytes = VJ_VERSION_NAME;
            nbytes = (uint32_t)strlen(VJ_VERSION_NAME) + 1;
            break;
        case 8:
            bytes = VJ_ARCH;
            nbytes = (uint32_t)strlen(VJ_ARCH) + 1;
            break;
        default:
            (void)strbuf;
            return -EINVAL;
        }
        uint32_t need = (uint32_t)sizeof(effect_param_t) + 4 + nbytes;
        if (*replySize < need) return -EINVAL;
        reply->status = 0;
        reply->psize = 4;
        reply->vsize = nbytes;
        memcpy(reply->data, &id, 4);
        memcpy(reply->data + 4, bytes, nbytes);
        *replySize = need;
        return 0;
    }

    static bool isConvPrepare(int32_t id) {
        return id == VJ_PID_CONV_PREP_CLASSIC || id == VJ_PID_CONV_PREP_NEW;
    }

    static bool isConvCommit(int32_t id) {
        return id == VJ_PID_CONV_COMMIT_CLASSIC || id == VJ_PID_CONV_COMMIT_NEW;
    }

    void kernelReset() {
        kernelBuf_.clear();
        kernelTotal_ = 0;
        kernelChannels_ = 0;
        kernelNext_ = 0;
        chain_.viperKernelPrepare(0, 0);
    }

    int32_t handleArrayParam(int32_t id, const char *v, uint32_t vsize) {
        if (id == VJ_PID_EQ_LEVELS_CLASSIC || id == VJ_PID_EQ_LEVELS_NEW) {
            uint32_t count;
            memcpy(&count, v, 4);
            if (count == 0 || count > VJ_EQ_MAX_BANDS ||
                count > (vsize - 4) / 4)
                return -EINVAL;
            for (uint32_t i = 0; i < count; i++) {
                float lvl;
                memcpy(&lvl, v + 4 + i * 4, 4);
                chain_.setViperEQBand(i, lvl);
            }
            chain_.setStageEnabled(VJ_STAGE_VIPER_IIR, true);
            return 0;
        }
        if (id == VJ_PID_DDC_CLASSIC || id == VJ_PID_DDC_NEW) {
            uint32_t per;
            memcpy(&per, v, 4);
            if (per == 0 || per > (vsize - 4) / 8) return -EINVAL;
            std::vector<float> c44(per), c48(per);
            memcpy(c44.data(), v + 4, per * 4);
            memcpy(c48.data(), v + 4 + per * 4, per * 4);
            chain_.viperSetDDC(c44.data(), per, c48.data(), per);
            chain_.setStageEnabled(VJ_STAGE_VIPER_DDC, true);
            return 0;
        }
        return -EINVAL;
    }

    int32_t handleKernelControl(int32_t id, const char *v) {
        int32_t a, b, c;
        memcpy(&a, v, 4);
        memcpy(&b, v + 4, 4);
        memcpy(&c, v + 8, 4);
        if (isConvPrepare(id)) {
            if (c != 0) {
                kernelReset();
                chain_.viperKernelPrepare(0, 0);
                return 0;
            }
            if (a <= 0 || (b != 1 && b != 2)) return -EINVAL;
            if ((uint32_t)a > VJ_KERNEL_MAX_TOTAL_FLOATS) return -EINVAL;
            kernelBuf_.clear();
            kernelBuf_.reserve((uint32_t)a);
            kernelTotal_ = (uint32_t)a;
            kernelChannels_ = (uint32_t)b;
            kernelNext_ = 0;
            chain_.viperKernelPrepare(kernelTotal_, kernelChannels_);
            return 0;
        }
        if (a <= 0 || (uint32_t)a != kernelTotal_ ||
            kernelBuf_.size() != kernelTotal_)
            return -EINVAL;
        chain_.viperKernelCommit(kernelTotal_, (uint32_t)b, (uint32_t)c);
        chain_.setStageEnabled(VJ_STAGE_VIPER_CONV, true);
        kernelReset();
        return 0;
    }

    int32_t handleKernelChunk(int32_t id, const char *v) {
        if (id != VJ_PID_CONV_CHUNK_CLASSIC && id != VJ_PID_CONV_CHUNK_NEW)
            return -EINVAL;
        if (kernelTotal_ == 0) return -EINVAL;
        int32_t idx;
        uint32_t len;
        memcpy(&idx, v, 4);
        memcpy(&len, v + 4, 4);
        if (idx < 0 || (uint32_t)idx != kernelNext_) return -EINVAL;
        if (len == 0 || len > VJ_KERNEL_MAX_FLOATS_PER_CHUNK) return -EINVAL;
        if (len > kernelTotal_ - (uint32_t)kernelBuf_.size()) return -EINVAL;
        std::vector<float> tmp(len);
        memcpy(tmp.data(), v + 8, len * 4);
        kernelBuf_.insert(kernelBuf_.end(), tmp.begin(), tmp.end());
        chain_.viperKernelAppend(kernelTotal_, tmp.data(), len);
        kernelNext_++;
        return 0;
    }

    effect_config_t config_;
    DisableReason disableReason_;
    bool enable_;
    uint64_t processedFrames_;
    uint64_t lastStreamingFrames_;
    std::vector<float> work_;
    std::vector<float> kernelBuf_;
    uint32_t kernelTotal_;
    uint32_t kernelChannels_;
    uint32_t kernelNext_;
    VipJamChain chain_;
};

static const effect_descriptor_t kVipJamDescriptor = {
    *EFFECT_UUID_NULL,
    {0x1b222930, 0xcde3, 0x5b6f, 0x81a4, {0xf6, 0x7b, 0x33, 0x34, 0xa7, 0x3e}},
    EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_OUTPUT_DIRECT | EFFECT_FLAG_INPUT_DIRECT |
        EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_TYPE_INSERT,
    8,
    1,
    "VipJam",
    "VipJam",
};

static int32_t VipJamInterfaceProcess(effect_handle_t self,
                                      audio_buffer_t *inBuffer,
                                      audio_buffer_t *outBuffer) {
    VipJamHandle *h = reinterpret_cast<VipJamHandle *>(self);
    if (h == nullptr || h->context == nullptr) return -EINVAL;
    return h->context->process(inBuffer, outBuffer);
}

static int32_t VipJamInterfaceCommand(effect_handle_t self, uint32_t cmdCode,
                                      uint32_t cmdSize, void *cmdData,
                                      uint32_t *replySize, void *replyData) {
    VipJamHandle *h = reinterpret_cast<VipJamHandle *>(self);
    if (h == nullptr || h->context == nullptr) return -EINVAL;
    return h->context->handleCommand(cmdCode, cmdSize, cmdData, replySize,
                                     replyData);
}

static int32_t VipJamInterfaceGetDescriptor(effect_handle_t self,
                                            effect_descriptor_t *descriptor) {
    if (descriptor == nullptr) return -EINVAL;
    (void)self;
    *descriptor = kVipJamDescriptor;
    return 0;
}

static int32_t VipJamInterfaceReverse(effect_handle_t self,
                                      audio_buffer_t *inBuffer,
                                      audio_buffer_t *outBuffer) {
    (void)self;
    (void)inBuffer;
    (void)outBuffer;
    return -ENODATA;
}

static constexpr effect_interface_s kVipJamInterface = {
    VipJamInterfaceProcess,
    VipJamInterfaceCommand,
    VipJamInterfaceGetDescriptor,
    VipJamInterfaceReverse,
};

static int32_t VipJamLibraryCreate(const effect_uuid_t *uuid,
                                   int32_t sessionId, int32_t ioId,
                                   effect_handle_t *handle) {
    if (uuid == nullptr || handle == nullptr) return -EINVAL;
    if (memcmp(uuid, &kVipJamDescriptor.uuid, sizeof(effect_uuid_t)) != 0)
        return -ENOENT;
    VipJamHandle *h = new VipJamHandle();
    h->interface = &kVipJamInterface;
    h->context = new VipJamContext();
    *handle = reinterpret_cast<effect_handle_t>(h);
    __android_log_print(ANDROID_LOG_INFO, "VipJam",
                        "VipJamLibraryCreate session=%d io=%d", sessionId,
                        ioId);
    return 0;
}

static int32_t VipJamLibraryRelease(effect_handle_t handle) {
    VipJamHandle *h = reinterpret_cast<VipJamHandle *>(handle);
    if (h == nullptr) return -EINVAL;
    delete h->context;
    delete h;
    return 0;
}

static int32_t VipJamLibraryGetDescriptor(const effect_uuid_t *uuid,
                                          effect_descriptor_t *descriptor) {
    if (uuid == nullptr || descriptor == nullptr) return -EINVAL;
    if (memcmp(uuid, &kVipJamDescriptor.uuid, sizeof(effect_uuid_t)) != 0)
        return -ENOENT;
    *descriptor = kVipJamDescriptor;
    return 0;
}

extern "C" {
__attribute__((visibility("default"), used)) audio_effect_library_t
    AUDIO_EFFECT_LIBRARY_INFO_SYM = {
        AUDIO_EFFECT_LIBRARY_TAG,
        EFFECT_LIBRARY_API_VERSION,
        "VipJam",
        "VipJam",
        VipJamLibraryCreate,
        VipJamLibraryRelease,
        VipJamLibraryGetDescriptor,
};
}
