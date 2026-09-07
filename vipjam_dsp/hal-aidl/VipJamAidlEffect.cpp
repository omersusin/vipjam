// VipJam AIDL system-effect skeleton — UNCOMPILED.
// There is no Soong/AOSP-tree build for this file yet; it CANNOT be built
// with the NDK (unstable C++ Binder/FMQ ABI — see hal-aidl/README.md §6).
// All IEffect/IFactory signatures, Descriptor/Capability/Parameter field
// names, and the createEffect/queryEffect/destroyEffect export shapes are
// BEST-EFFORT placeholders to be closed against
// hardware/interfaces/audio/aidl of the target AOSP version.
// DSP + SHM constants below ARE real: they reuse VipJamShm.h/VipJamParams.h.

#include <cstdint>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

// Real VipJam headers (present in vipjam_dsp/include/).
#include "VipJamChain.h"
#include "VipJamParams.h"
#include "VipJamShm.h"

// ---- Identity (approved plan, docs/VIPJAM_ROADMAP.md §§ 2.2/16.11) ----
// Type impersonated: DynamicsProcessing 7261676f-6d75-7369-6364-28e2fd3ac39e
// Our impl UUID:      90380da3-8536-4744-a6a3-5731970e640f
static constexpr const char* kVipJamAidlTypeUuid =
    "7261676f-6d75-7369-6364-28e2fd3ac39e";
static constexpr const char* kVipJamAidlImplUuid =
    "90380da3-8536-4744-a6a3-5731970e640f";
static constexpr const char* kVipJamAidlEffectName = "v4a_standard_aidl";
static constexpr const char* kVipJamAidlLibName = "v4a_aidl";

// SHM layout is NOT redefined here: reuse VipJamShm.h / VipJamParams.h.
//   dir    VIPJAM_SHM_DIR ("/data/local/tmp/vipjam")
//   files  VIPJAM_SHM_{PARAMS,BULK,STATUS}_PATH, sizes VIPJAM_SHM_*_SIZE
//   params VIPJAM_SHM_MAGIC (0x534D3456), VIPJAM_SHM_VERSION (5),
//          hdr16, VIPJAM_SHM_SLOT_{A,B/SIZE}, VIPJAM_SHM_EXT_{BASE,SIZE,...}
//   bulk   VIPJAM_SHM_BULK_{REGION,HDR,MAX}, VIPJAM_BULK_* cmds 1..7
static_assert(VIPJAM_SHM_MAGIC == 0x534D3456u, "SHM magic drift");
static_assert(VIPJAM_SHM_VERSION == 5, "SHM version drift");
static_assert(VIPJAM_SHM_PARAMS_SIZE == 4096, "params size drift");
static_assert(VIPJAM_SHM_BULK_SIZE == 4096, "bulk size drift");
static_assert(VIPJAM_SHM_STATUS_SIZE == 256, "status size drift");

// ---- Minimal stand-ins for AOSP AIDL types (DELETE on Soong bring-up) ----
// These exist ONLY so the shape below reads correctly without an AOSP tree.
// Replace with real includes:
//   android/hardware/audio/effect/IEffect.h (+ IFactory, Descriptor,
//   Capability, Parameter, State, CommandId, DynamicsProcessing.h)
//   system/memory + android/hardware/common/fmq (DataMQDesc, EventFlagGroup)
namespace aidl_stub {
enum class State { INIT, IDLE, PROCESSING };
enum class CommandId { START, STOP, RESET, SET_VOLUME_STEREO };
enum class PcmType { FLOAT_32_BIT };
struct ChannelConfig {
    float attackMs = 0, releaseMs = 0, thresholdDb = 0, ratio = 1, kneeDb = 0;
    float preGainDb = 0, postGainDb = 0;
};
struct DynamicsProcessing {
    bool enabled = false;
    bool limiterEnabled = false;
    std::vector<ChannelConfig> channels;
};
struct Common {
    int32_t session = 0;
    int32_t ioHandle = 0;
    int32_t sampleRate = 48000;
    int32_t frameCount = 256;
    PcmType pcmType = PcmType::FLOAT_32_BIT;
    int32_t channelCount = 2;
};
struct Specific {
    DynamicsProcessing dp;
};
struct Parameter {
    Common common;
    Specific specific;
};
}  // namespace aidl_stub

// Poll SHM (app mmap-plans, driver polls): compare params updateCount
// (hdr@12) + bulk seq, then vipjam_shm_read_viper/james -> VipJamChain.
class VipJamAidlShmPoller {
public:
    VipJamAidlShmPoller() = default;

    // Mapped bases owned by caller (mmap'd shm_{params,bulk,status}.bin).
    void attach(void* params, size_t paramsLen, const void* bulk,
                size_t bulkLen, void* status, size_t statusLen) {
        params_ = params;
        paramsLen_ = paramsLen;
        bulk_ = bulk;
        bulkLen_ = bulkLen;
        status_ = status;
        statusLen_ = statusLen;
    }

    // Returns true when new params were applied.
    // processedFrames is the effect's real counter (caller-owned); the
    // poller keeps no DSP counter of its own.
    bool poll(VipJamChain& chain, uint32_t sampleRate,
              uint64_t processedFrames) {
        if (!params_) return false;
        uint32_t count = vipjam_shm_update_count(params_, paramsLen_);
        uint32_t seq0 = bulkSeq(bulk_, bulkLen_, 0);
        uint32_t seq1 = bulkSeq(bulk_, bulkLen_, VIPJAM_SHM_BULK_REGION);
        if (count == lastCount_ && seq0 == lastBulk0_ &&
            seq1 == lastBulk1_)
            return false;
        lastCount_ = count;
        lastBulk0_ = seq0;
        lastBulk1_ = seq1;
        applyAll(chain);
        writeHeartbeat(chain, sampleRate, processedFrames);
        return true;
    }

private:
    void applyAll(VipJamChain& chain) {
        // Fused ViPER block (slot A/B selected inside the helper).
        uint8_t block[VIPJAM_SHM_SLOT_SIZE];
        if (vipjam_shm_read_viper(params_, paramsLen_, block) == 0) {
            // Wire format: u32 count + fused (id, v0, v1, v2) records.
            // Keep in sync with hal/VipJamEffect.cpp handleSetParam.
            const uint8_t* p = block;
            uint32_t n = 0;
            memcpy(&n, p, 4);
            p += 4;
            if (n > (VIPJAM_SHM_SLOT_SIZE - 4) / 16) n = (VIPJAM_SHM_SLOT_SIZE - 4) / 16;
            for (uint32_t i = 0; i < n; ++i) {
                int32_t id = 0;
                float v0 = 0, v1 = 0, v2 = 0;
                memcpy(&id, p, 4);
                memcpy(&v0, p + 4, 4);
                memcpy(&v1, p + 8, 4);
                memcpy(&v2, p + 12, 4);
                p += 16;
                chain.setFusedParam(id, v0, v1, v2);
            }
        }
        VipJamJamesBlock jb;
        if (vipjam_shm_read_james(params_, paramsLen_, &jb) == 0) {
            chain.setJamesBass((float)jb.bassMaxGain);
            chain.setJamesReverb(jb.reverbPreset);
            chain.setJamesXfeed(jb.xfeedMode);
            chain.setJamesTube((double)jb.tubeDrive);
            if (jb.kernelPath[0]) {
                // Convolver kernel path arrives via BULK CONV_PATH; the
                // params copy is a hint only — bulk read below wins.
            }
        }
        if (bulk_) {
            static const uint32_t kRegions[] = {0, VIPJAM_SHM_BULK_REGION};
            for (uint32_t r = 0; r < 2; ++r) {
                uint32_t region = kRegions[r];
                uint32_t cmd = 0, size = 0;
                const void* data = nullptr;
                if (vipjam_shm_bulk_read(bulk_, bulkLen_, region, &cmd, &data,
                                         &size) != 0)
                    continue;
                switch (cmd) {
                    case VIPJAM_BULK_DDC:
                        if (data && size > 8 && size <= 1048576) {
                            std::string s((const char*)data, size);
                            s.push_back('\0');
                            chain.loadDDC(s.c_str());
                        }
                        break;
                    case VIPJAM_BULK_CONV_PATH:
                        break;  // app-side file load; effect re-mmaps kernel
                    case VIPJAM_BULK_STREQ_TEXT:
                    case VIPJAM_BULK_LIVEPROG_SCRIPT:
                        if (data && size > 0 && size <= 1048576) {
                            std::string s((const char*)data, size);
                            s.push_back('\0');
                            if (chain.loadLiveProg(s.c_str()) > 0)
                                chain.setStageEnabled(VJ_STAGE_JAMES_LIVEPROG,
                                                      true);
                        }
                        break;
                    case VIPJAM_BULK_DDC_RESET:
                        ddcReset(chain);
                        break;
                    case VIPJAM_BULK_CONV_RESET:
                        kernelReset(chain);
                        break;
                    case VIPJAM_BULK_VIPJAM_FULL:
                        break;  // full-state blob; fused path above covers it
                    default:
                        break;
                }
            }
        }
    }

    // Selective resets: never chain.reset() here — that would wipe the
    // limiter gate + loudness state. Mirrors legacy VipJamContext helpers:
    // kernelReset() == drop staged kernel + viperKernelPrepare(0,0),
    // scriptReset() == drop staged script (poller holds no staging, so the
    // observable part is disabling the affected stage only).
    static void ddcReset(VipJamChain& chain) {
        chain.setStageEnabled(VJ_STAGE_VIPER_DDC, false);
        chain.setStageEnabled(VJ_STAGE_JAMES_DDC, false);
    }
    static void kernelReset(VipJamChain& chain) {
        chain.viperKernelPrepare(0, 0);
        chain.setStageEnabled(VJ_STAGE_VIPER_CONV, false);
    }
    // Real bulk seq from the region header (hdr+8), not cmd^size which
    // collides. Returns 0 when unmapped/uninit (magic/ver mismatch).
    static uint32_t bulkSeq(const void* base, size_t len, uint32_t region) {
        if (!base) return 0;
        if (region != 0 && region != VIPJAM_SHM_BULK_REGION) return 0;
        if (len < VIPJAM_SHM_BULK_SIZE) return 0;
        const uint8_t* hdr = static_cast<const uint8_t*>(base) + region;
        uint32_t magic = 0, ver = 0, seq = 0;
        memcpy(&magic, hdr, 4);
        memcpy(&ver, hdr + 4, 4);
        memcpy(&seq, hdr + 8, 4);
        if (magic != VIPJAM_SHM_MAGIC || ver != VIPJAM_SHM_VERSION) return 0;
        return seq;
    }

    void writeHeartbeat(VipJamChain& chain, uint32_t sampleRate,
                        uint64_t processedFrames) {
        if (!status_) return;
        VipJamStatus st;
        memset(&st, 0, sizeof(st));
        st.enabled = chain.isMasterEnabled() ? 1 : 0;
        st.configured = 1;
        st.processedFrames = processedFrames;
        st.sampleRate = (int32_t)sampleRate;
        st.versionCode = 1;
        strncpy(st.versionName, "0.1.0-aidl", sizeof(st.versionName) - 1);
#if defined(__aarch64__)
        strncpy(st.arch, "ARM64", sizeof(st.arch) - 1);
#elif defined(__arm__)
        strncpy(st.arch, "ARM", sizeof(st.arch) - 1);
#else
        strncpy(st.arch, "host", sizeof(st.arch) - 1);
#endif
        vipjam_shm_status_write(status_, statusLen_, &st);
    }

    void* params_ = nullptr;
    size_t paramsLen_ = 0;
    const void* bulk_ = nullptr;
    size_t bulkLen_ = 0;
    void* status_ = nullptr;
    size_t statusLen_ = 0;
    // Sentinel init: vipjam_shm_update_count()/bulkSeq() return 0 on
    // uninit/error, so 0 must NOT mean "already seen" — first poll applies.
    uint32_t lastCount_ = 0xFFFFFFFFu;
    uint32_t lastBulk0_ = 0xFFFFFFFFu;
    uint32_t lastBulk1_ = 0xFFFFFFFFu;
};

// ---- IEffect-shaped skeleton (stub types; see README §1) ----
class VipJamAidlEffect {
public:
    VipJamAidlEffect() : state_(aidl_stub::State::INIT) {}
    ~VipJamAidlEffect() { close(); }

    // IEffect::open — accept FLOAT_32 stereo only; arm SHM poll + FMQ worker.
    int open(const aidl_stub::Common& common,
             const aidl_stub::Specific& specific) {
        if (common.pcmType != aidl_stub::PcmType::FLOAT_32_BIT) return -22;
        if (common.channelCount != 2) return -22;
        if (common.sampleRate != 44100 && common.sampleRate != 48000)
            return -22;
        chain_.setSamplingRate((uint32_t)common.sampleRate);
        chain_.reset();
        common_ = common;
        dp_ = specific.dp;
        applyDp(dp_);
        // TODO(Soong): mmap shm_{params,bulk,status}.bin here (O_RDWR, validate
        // VIPJAM_SHM_MAGIC/VIPJAM_SHM_VERSION), open FMQ read/write
        // blocking queues + EventFlagGroup, spawn worker thread.
        state_ = aidl_stub::State::IDLE;
        return 0;
    }

    int close() {
        // TODO(Soong): stop FMQ worker, join, unmap SHM.
        chain_.setMasterEnabled(false);
        state_ = aidl_stub::State::INIT;
        return 0;
    }

    int command(aidl_stub::CommandId id) {
        switch (id) {
            case aidl_stub::CommandId::START:
                chain_.reset();
                chain_.setMasterEnabled(true);
                state_ = aidl_stub::State::PROCESSING;
                return 0;
            case aidl_stub::CommandId::STOP:
                chain_.setMasterEnabled(false);
                state_ = aidl_stub::State::IDLE;
                return 0;
            case aidl_stub::CommandId::RESET:
                chain_.reset();
                return 0;
            case aidl_stub::CommandId::SET_VOLUME_STEREO:
                return -95;  // stub has no volume args; refuse, don't fake success
            default:
                return -95;  // EX_UNSUPPORTED_OPERATION placeholder
        }
    }

    aidl_stub::State getState() const { return state_; }

    int setParameter(const aidl_stub::Parameter& p) {
        dp_ = p.specific.dp;
        applyDp(dp_);
        return 0;
    }

    int getParameter(aidl_stub::Parameter& out) const {
        out.common = common_;
        out.specific.dp = dp_;
        return 0;
    }

    // FMQ worker body (called per block once FMQ exists): poll SHM then DSP.
    // Interleaved stereo float32 in-place.
    void processBlock(std::vector<float>& stereo) {
        poller_.poll(chain_, chain_.samplingRate(), processedFrames_);
        if (!chain_.isMasterEnabled() || (stereo.size() & 1u)) return;
        chain_.process(stereo);
        processedFrames_ += (uint64_t)(stereo.size() / 2);
    }

    static const char* typeUuid() { return kVipJamAidlTypeUuid; }
    static const char* implUuid() { return kVipJamAidlImplUuid; }

private:
    void applyDp(const aidl_stub::DynamicsProcessing& dp) {
        chain_.setMasterEnabled(dp.enabled);
        if (!dp.channels.empty()) {
            const auto& c = dp.channels[0];
            // thresholdDb is dB (<=0); setLimiter takes linear 0..1 gate.
            float gate = powf(10.0f, c.thresholdDb / 20.0f);
            if (!(gate >= 0.01f && gate <= 1.0f)) gate = 1.0f;
            chain_.setLimiter(gate);
            (void)c;
        }
    }

    aidl_stub::State state_;
    aidl_stub::Common common_;
    aidl_stub::DynamicsProcessing dp_;
    VipJamChain chain_;
    VipJamAidlShmPoller poller_;
    uint64_t processedFrames_ = 0;
};

// ---- Required C exports (PLACEHOLDER signatures — VERIFY vs AOSP tree) ----
// Real loader wants versioned createEffect/queryEffect/destroyEffect over the
// AIDL Descriptor parcel; these keep the link shape visible until Soong.
extern "C" {

// uuid == kVipJamAidlImplUuid ("90380da3-…") -> new VipJamAidlEffect*.
// Returns 0 on success, -22 (EINVAL) on unknown uuid.
int32_t createEffect(const char* uuid, void** out) {
    if (!uuid || !out) return -22;
    if (strcmp(uuid, kVipJamAidlImplUuid) != 0) return -22;
    *out = new VipJamAidlEffect();
    return 0;
}

// idx 0 -> write impl/type uuid + names into descOut (real type: Descriptor*).
// Returns 0 / -22 when idx out of range.
int32_t queryEffect(uint32_t idx, void* descOut) {
    if (idx != 0 || !descOut) return -22;
    struct MiniDesc {
        char type[64];
        char uuid[64];
        char name[64];
        char lib[64];
    };
    auto* d = static_cast<MiniDesc*>(descOut);
    strncpy(d->type, kVipJamAidlTypeUuid, sizeof(d->type) - 1);
    strncpy(d->uuid, kVipJamAidlImplUuid, sizeof(d->uuid) - 1);
    strncpy(d->name, kVipJamAidlEffectName, sizeof(d->name) - 1);
    strncpy(d->lib, kVipJamAidlLibName, sizeof(d->lib) - 1);
    return 0;
}

int32_t destroyEffect(void* handle) {
    delete static_cast<VipJamAidlEffect*>(handle);
    return 0;
}

}  // extern "C"
