#include <stdio.h>
#include <string.h>
#include <math.h>
#include <vector>
#include "VipJamParams.h"
#include "VipJamChain.h"
#include "audio_effect_stub.h"
#include "VipJamContext.cpp"

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

static void test_shim_new_ids(void) {
    CHECK(vipjam_shim_to_fused(0x10101) == VIPJAM_MASTER_ENABLE, "shim reset");
    CHECK(vipjam_shim_to_fused(0x10110) == VIPJAM_LIMITER, "shim limiter lo");
    CHECK(vipjam_shim_to_fused(0x10112) == VIPJAM_LIMITER, "shim limiter hi");
    CHECK(vipjam_shim_to_fused(0x10120) == VIPJAM_AGC, "shim pgc");
    CHECK(vipjam_shim_to_fused(0x10133) == VIPJAM_AGC, "shim lufs hi");
    CHECK(vipjam_shim_to_fused(0x10140) == VIPJAM_FET_MBC, "shim fet lo");
    CHECK(vipjam_shim_to_fused(0x10150) == VIPJAM_FET_MBC, "shim fet hi");
    CHECK(vipjam_shim_to_fused(0x10293) == VIPJAM_FET_MBC, "shim mbc hi");
    CHECK(vipjam_shim_to_fused(0x10160) == VIPJAM_BASS, "shim bass");
    CHECK(vipjam_shim_to_fused(0x10184) == VIPJAM_BASS, "shim psycho hi");
    CHECK(vipjam_shim_to_fused(0x101A0) == VIPJAM_EQ, "shim eq");
    CHECK(vipjam_shim_to_fused(0x102A8) == VIPJAM_EQ, "shim dyneq hi");
    CHECK(vipjam_shim_to_fused(0x101C0) == VIPJAM_DDC, "shim ddc");
    CHECK(vipjam_shim_to_fused(0x101B0) == VIPJAM_CONVOLVER, "shim conv");
    CHECK(vipjam_shim_to_fused(0x101D0) == VIPJAM_SPACE, "shim field");
    CHECK(vipjam_shim_to_fused(0x101E4) == VIPJAM_SPACE, "shim diff hi");
    CHECK(vipjam_shim_to_fused(0x101F0) == VIPJAM_SPACE, "shim stereo");
    CHECK(vipjam_shim_to_fused(0x10201) == VIPJAM_SPACE, "shim hsurr hi");
    CHECK(vipjam_shim_to_fused(0x10210) == VIPJAM_REVERB, "shim reverb");
    CHECK(vipjam_shim_to_fused(0x10220) == VIPJAM_DYN_SYS, "shim dynsys");
    CHECK(vipjam_shim_to_fused(0x10230) == VIPJAM_CLARITY_SPECEX, "shim clarity");
    CHECK(vipjam_shim_to_fused(0x10192) == VIPJAM_CLARITY_SPECEX, "shim specex");
    CHECK(vipjam_shim_to_fused(0x10240) == VIPJAM_XFEED, "shim cure");
    CHECK(vipjam_shim_to_fused(0x10250) == VIPJAM_TUBE, "shim tube");
    CHECK(vipjam_shim_to_fused(0x10261) == VIPJAM_TUBE, "shim analogx hi");
    CHECK(vipjam_shim_to_fused(0x10270) == VIPJAM_SPEAKER_CORR, "shim spk");
}

static void test_shim_classic_james(void) {
    CHECK(vipjam_shim_to_fused(65538) == VIPJAM_CONVOLVER, "classic conv");
    CHECK(vipjam_shim_to_fused(65546) == VIPJAM_DDC, "classic ddc");
    CHECK(vipjam_shim_to_fused(65551) == VIPJAM_EQ, "classic eq");
    CHECK(vipjam_shim_to_fused(65559) == VIPJAM_REVERB, "classic reverb");
    CHECK(vipjam_shim_to_fused(65565) == VIPJAM_AGC, "classic agc");
    CHECK(vipjam_shim_to_fused(65574) == VIPJAM_BASS, "classic bass");
    CHECK(vipjam_shim_to_fused(65578) == VIPJAM_CLARITY_SPECEX, "classic clarity");
    CHECK(vipjam_shim_to_fused(65581) == VIPJAM_XFEED, "classic cure");
    CHECK(vipjam_shim_to_fused(65583) == VIPJAM_TUBE, "classic tube");
    CHECK(vipjam_shim_to_fused(65584) == VIPJAM_TUBE, "classic analogx");
    CHECK(vipjam_shim_to_fused(65586) == VIPJAM_OUT_VOL_PAN, "classic gate");
    CHECK(vipjam_shim_to_fused(65603) == VIPJAM_SPEAKER_CORR, "classic spk");
    CHECK(vipjam_shim_to_fused(65610) == VIPJAM_FET_MBC, "classic fet lo");
    CHECK(vipjam_shim_to_fused(65626) == VIPJAM_FET_MBC, "classic fet hi");
    CHECK(vipjam_shim_to_fused(1500) == VIPJAM_LIMITER, "james limiter");
    CHECK(vipjam_shim_to_fused(115) == VIPJAM_AGC, "james comp");
    CHECK(vipjam_shim_to_fused(112) == VIPJAM_BASS, "james bass");
    CHECK(vipjam_shim_to_fused(116) == VIPJAM_EQ, "james eq");
    CHECK(vipjam_shim_to_fused(1212) == VIPJAM_DDC, "james ddc en");
    CHECK(vipjam_shim_to_fused(1205) == VIPJAM_CONVOLVER, "james conv en");
    CHECK(vipjam_shim_to_fused(12000) == VIPJAM_CONVOLVER, "james conv chunk");
    CHECK(vipjam_shim_to_fused(12001) == VIPJAM_EQ, "james str chunk");
    CHECK(vipjam_shim_to_fused(128) == VIPJAM_REVERB, "james reverb");
    CHECK(vipjam_shim_to_fused(188) == VIPJAM_XFEED, "james xfeed");
    CHECK(vipjam_shim_to_fused(150) == VIPJAM_TUBE, "james tube");
    CHECK(vipjam_shim_to_fused(137) == VIPJAM_SPACE, "james widen");
    CHECK(vipjam_shim_to_fused(1213) == VIPJAM_SPACE, "james liveprog");
    CHECK(vipjam_shim_to_fused(99999) == 0, "unknown id -> 0");
}

static void test_interleave(void) {
    float in[6] = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f};
    float l[3], r[3], out[6];
    VipJamChain::deinterleave(in, l, r, 3);
    CHECK(l[0] == 0.1f && l[2] == 0.5f && r[0] == 0.2f && r[2] == 0.6f,
          "deinterleave");
    VipJamChain::interleave(l, r, out, 3);
    CHECK(memcmp(in, out, sizeof(in)) == 0, "interleave round-trip");
}

static void test_chain_passthrough(void) {
    VipJamChain chain;
    chain.setSamplingRate(48000);
    std::vector<float> buf;
    for (int i = 0; i < 2048; i++) buf.push_back((i % 200 - 100) / 100.0f);
    std::vector<float> ref = buf;
    chain.process(buf);
    CHECK(buf.size() == ref.size(), "chain size kept");
    bool same = true;
    for (size_t i = 0; i < buf.size(); i++)
        if (buf[i] != ref[i]) same = false;
    CHECK(same, "chain passthrough bit-exact (all stages off)");
    chain.setStageEnabled(VJ_STAGE_LIMITER, true);
    for (size_t i = 0; i < buf.size(); i++) buf[i] = 5.0f;
    chain.process(buf);
    CHECK(buf[0] == 0.999999f, "limiter clamps to gate");
}

static void test_context_commands(void) {
    vipjam_context_t *ctx = vipjam_context_create(44100);
    uint32_t rsize;
    int status;
    rsize = sizeof(status);
    CHECK(vipjam_context_command(ctx, EFFECT_CMD_INIT, 0, NULL, &rsize,
                                 &status) == VIPJAM_OK,
          "ctx init");
    char setBuf[sizeof(effect_param_t) + 8];
    effect_param_t *sp = (effect_param_t *)setBuf;
    sp->psize = 4;
    sp->vsize = 4;
    int32_t id = VIPJAM_MASTER_ENABLE, on = 1;
    memcpy(sp->data, &id, 4);
    memcpy(sp->data + 4, &on, 4);
    CHECK(vipjam_context_command(ctx, EFFECT_CMD_SET_PARAM, sizeof(setBuf),
                                 setBuf, &rsize, &status) == VIPJAM_OK,
          "ctx set master on");
    char getBuf[sizeof(effect_param_t) + 4];
    effect_param_t *gp = (effect_param_t *)getBuf;
    gp->psize = 4;
    gp->vsize = 0;
    int32_t gid = VJ_GET_ENABLED;
    memcpy(gp->data, &gid, 4);
    char repBuf[sizeof(effect_param_t) + 8];
    rsize = sizeof(repBuf);
    CHECK(vipjam_context_command(ctx, EFFECT_CMD_GET_PARAM, sizeof(getBuf),
                                 getBuf, &rsize, repBuf) == VIPJAM_OK,
          "ctx get enabled");
    effect_param_t *rp = (effect_param_t *)repBuf;
    int32_t got = 0;
    memcpy(&got, rp->data + 4, 4);
    CHECK(got == 1, "ctx enabled round-trip");
    CHECK(vipjam_context_command(ctx, EFFECT_CMD_DISABLE, 0, NULL, &rsize,
                                 &status) == VIPJAM_OK,
          "ctx disable");
    vipjam_context_release(ctx);
}

static void test_engines_passthrough(void) {
    VipJamChain chain;
    chain.setSamplingRate(44100);
    std::vector<float> buf(2048 * 2);
    for (size_t i = 0; i < buf.size(); i++)
        buf[i] = static_cast<float>((static_cast<int>(i) % 200 - 100)) / 100.0f;
    std::vector<float> ref = buf;
    chain.process(buf);
    float maxDiff = 0.0f;
    for (size_t i = 0; i < buf.size(); i++) {
        float d = buf[i] - ref[i];
        if (d < 0) d = -d;
        if (d > maxDiff) maxDiff = d;
    }
    CHECK(maxDiff < 1e-5f, "engines passthrough (all off)");
}

static void test_engines_james_comp(void) {
    VipJamChain chain;
    chain.setSamplingRate(48000);
    chain.setStageEnabled(VJ_STAGE_JAMES_COMP, true);
    CHECK(chain.isStageEnabled(VJ_STAGE_JAMES_COMP), "comp stage on");
    std::vector<float> buf(1024 * 2, 0.5f);
    chain.process(buf);
    bool finite = true;
    for (size_t i = 0; i < buf.size(); i++)
        if (buf[i] != buf[i] || buf[i] > 1.0f || buf[i] < -1.0f) finite = false;
    CHECK(finite, "james comp runs finite + clamped");
    chain.setStageEnabled(VJ_STAGE_JAMES_COMP, false);
    CHECK(!chain.isStageEnabled(VJ_STAGE_JAMES_COMP), "comp stage off");
}

static void test_engines_viper_bass(void) {
    VipJamChain chain;
    chain.setSamplingRate(44100);
    chain.setStageEnabled(VJ_STAGE_VIPER_BASS, true);
    std::vector<float> buf(1024 * 2, 0.25f);
    chain.process(buf);
    bool finite = true;
    for (size_t i = 0; i < buf.size(); i++)
        if (buf[i] != buf[i] || buf[i] > 1.0f || buf[i] < -1.0f) finite = false;
    CHECK(finite, "viper bass runs finite + clamped");
}

static std::string readFile(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return "";
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::string s(n, '\0');
    if (n > 0) fread(&s[0], 1, (size_t)n, f);
    fclose(f);
    return s;
}

static bool parseVdc(const std::string &text, std::vector<float> &c44,
                     std::vector<float> &c48) {
    const char *p44 = strstr(text.c_str(), "SR_44100:");
    const char *p48 = strstr(text.c_str(), "SR_48000:");
    if (!p44 || !p48) return false;
    const char *e44 = strchr(p44, '\n');
    std::string s44(p44 + 9, e44 ? e44 : p44 + strlen(p44));
    std::string s48(p48 + 9);
    for (int k = 0; k < 2; k++) {
        const std::string &s = k == 0 ? s44 : s48;
        std::vector<float> &out = k == 0 ? c44 : c48;
        const char *p = s.c_str();
        char *end = NULL;
        while (*p) {
            while (*p == ' ' || *p == ',' || *p == ';' || *p == '\r' ||
                   *p == '\n')
                p++;
            if (!*p) break;
            out.push_back(strtof(p, &end));
            if (end == p) return false;
            p = end;
        }
    }
    return !c44.empty() && c44.size() == c48.size() && c44.size() % 5 == 0;
}

static bool allFiniteClamped(const std::vector<float> &buf) {
    for (size_t i = 0; i < buf.size(); i++)
        if (buf[i] != buf[i] || buf[i] > 1.0f || buf[i] < -1.0f) return false;
    return true;
}

static void test_ddc_james(void) {
    std::string vdc = readFile("tests/vectors/Beyerdynamic DT770-80-4.vdc");
    CHECK(!vdc.empty(), "ddc vector file loads");
    VipJamChain chain;
    chain.setSamplingRate(44100);
    CHECK(chain.loadDDC(vdc.c_str()) > 0, "james ddc parses DT770");
    CHECK(chain.loadDDC("") < 0, "james ddc rejects empty");
    chain.setStageEnabled(VJ_STAGE_JAMES_DDC, true);
    std::vector<float> buf(1024 * 2);
    for (size_t i = 0; i < buf.size(); i += 2) {
        float s = sinf((float)i * 0.05f) * 0.5f;
        buf[i] = s;
        buf[i + 1] = s;
    }
    std::vector<float> dry = buf;
    chain.process(buf);
    CHECK(allFiniteClamped(buf), "james ddc finite + clamped");
    float diff = 0;
    for (size_t i = 256; i < buf.size(); i++) {
        float d = buf[i] - dry[i];
        diff += d * d;
    }
    CHECK(diff > 1e-6f, "james ddc audibly changes signal");
}

static void test_ddc_viper(void) {
    std::string vdc = readFile("tests/vectors/Beyerdynamic DT770-80-4.vdc");
    std::vector<float> c44, c48;
    CHECK(parseVdc(vdc, c44, c48), "vdc parses to 44/48 coeff vectors");
    CHECK(c44.size() % 5 == 0, "vdc coeff count is SOS x5");
    VipJamChain chain;
    chain.setSamplingRate(44100);
    chain.viperSetDDC(c44.data(), (unsigned)c44.size(), c48.data(),
                      (unsigned)c48.size());
    chain.setStageEnabled(VJ_STAGE_VIPER_DDC, true);
    std::vector<float> buf(1024 * 2, 0.3f);
    chain.process(buf);
    CHECK(allFiniteClamped(buf), "viper ddc finite + clamped");
}

static void test_ir_james(void) {
    VipJamChain chain;
    chain.setSamplingRate(44100);
    std::vector<float> ir(256 * 2);
    unsigned seed = 12345;
    for (size_t i = 0; i < ir.size(); i++) {
        seed = seed * 1103515245 + 12345;
        float n = (float)((seed >> 16) & 0x7fff) / 32768.0f - 0.5f;
        ir[i] = n * expf((float)-((int)(i / 2)) / 64.0f);
    }
    CHECK(chain.loadIR(ir.data(), 2, 256) > 0, "james IR loads stereo");
    CHECK(chain.loadIR(ir.data(), 3, 256) < 0, "james IR rejects 3ch");
    chain.setStageEnabled(VJ_STAGE_JAMES_CONV, true);
    std::vector<float> buf(1024 * 2, 0.4f);
    chain.process(buf);
    CHECK(allFiniteClamped(buf), "james convolver finite + clamped");
}

static unsigned crc32_le(const unsigned char *data, size_t len) {
    static unsigned table[256];
    static int ready = 0;
    if (!ready) {
        for (unsigned i = 0; i < 256; i++) {
            unsigned c = i;
            for (int k = 0; k < 8; k++)
                c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            table[i] = c;
        }
        ready = 1;
    }
    unsigned crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; i++)
        crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return crc ^ 0xFFFFFFFFu;
}

static void test_ir_viper(void) {
    VipJamChain chain;
    chain.setSamplingRate(44100);
    std::vector<float> dirac(64, 0.0f);
    dirac[0] = 1.0f;
    chain.viperKernelPrepare(64, 1);
    chain.viperKernelAppend(64, dirac.data(), 32);
    chain.viperKernelAppend(64, dirac.data() + 32, 32);
    unsigned crc = crc32_le((const unsigned char *)dirac.data(),
                            dirac.size() * 4);
    chain.viperKernelCommit(64, crc, 1234);
    CHECK(chain.viperKernelID() == 1234, "viper kernel id set via commit");
    chain.setStageEnabled(VJ_STAGE_VIPER_CONV, true);
    std::vector<float> buf(1024 * 2, 0.4f);
    chain.process(buf);
    CHECK(allFiniteClamped(buf), "viper convolver finite + clamped");
}

static void test_liveprog_single(void) {
    std::string eel = readFile("tests/vectors/hadamVerb.eel");
    CHECK(!eel.empty(), "eel vector file loads");
    VipJamChain chain;
    chain.setSamplingRate(44100);
    CHECK(chain.loadLiveProg(eel.c_str()) == 1, "hadamVerb compiles");
    CHECK(chain.loadLiveProg("@init\nclamp(") < 0, "broken eel rejected");
    chain.setStageEnabled(VJ_STAGE_JAMES_LIVEPROG, true);
    std::vector<float> buf(1024 * 2, 0.3f);
    chain.process(buf);
    CHECK(allFiniteClamped(buf), "liveprog runs finite + clamped");
}

static void test_liveprog_multi(void) {
    std::string verb = readFile("tests/vectors/hadamVerb.eel");
    std::string hp = readFile("tests/vectors/hpfloat.eel");
    VipJamChain chain;
    chain.setSamplingRate(44100);
    const char *scripts[2] = {verb.c_str(), hp.c_str()};
    int rc = chain.loadLiveProgMulti(scripts, 2);
    CHECK(rc == 1, "merged 2-script eel compiles");
    CHECK(chain.loadLiveProgMulti(scripts, 0) < 0, "empty multi rejected");
    CHECK(chain.loadLiveProgMulti(scripts, 5) < 0, "over-limit multi rejected");
    chain.setStageEnabled(VJ_STAGE_JAMES_LIVEPROG, true);
    std::vector<float> buf(1024 * 2, 0.3f);
    chain.process(buf);
    CHECK(allFiniteClamped(buf), "chained liveprog finite + clamped");
}

static void test_parametric_james(void) {
    VipJamChain chain;
    chain.setSamplingRate(44100);
    static const double freq[15] = {25, 40, 63, 100, 160, 250, 400, 630, 1000,
                                    1600, 2500, 4000, 6300, 10000, 16000};
    double flat[15] = {0};
    double boost[15] = {0};
    boost[7] = 6.0;
    chain.setJamesEQ(freq, flat, 0);
    chain.setJamesBass(5.0f);
    chain.setJamesComp(0.22f, 2, 0);
    chain.setJamesReverb(10);
    chain.setJamesTube(2.0);
    chain.setJamesStereo(0.5f);
    chain.setJamesXfeed(1);
    chain.setStageEnabled(VJ_STAGE_JAMES_EQ, true);
    chain.setStageEnabled(VJ_STAGE_JAMES_BASS, true);
    chain.setStageEnabled(VJ_STAGE_JAMES_REVERB, true);
    chain.setStageEnabled(VJ_STAGE_JAMES_TUBE, true);
    std::vector<float> buf(2048 * 2);
    for (size_t i = 0; i < buf.size(); i += 2) {
        float s = sinf((float)i * 0.03f) * 0.4f;
        buf[i] = s;
        buf[i + 1] = s;
    }
    chain.process(buf);
    CHECK(allFiniteClamped(buf), "james params finite + clamped");
    chain.setJamesEQ(freq, boost, 0);
    std::vector<float> buf2(2048 * 2);
    for (size_t i = 0; i < buf2.size(); i += 2) {
        float s = sinf((float)i * 0.03f) * 0.4f;
        buf2[i] = s;
        buf2[i + 1] = s;
    }
    chain.process(buf2);
    float diff = 0;
    for (size_t i = 512; i < buf.size(); i++) {
        float d = buf2[i] - buf[i];
        diff += d * d;
    }
    CHECK(diff > 1e-6f, "james EQ change audible");
}

static void test_parametric_viper(void) {
    VipJamChain chain;
    chain.setSamplingRate(44100);
    chain.setViperEQBand(3, 6.0f);
    chain.setViperEQBand(99, 6.0f);
    chain.setViperBass(1, 4.0f);
    chain.setViperReverb(50, 50, 50, 30, 70);
    chain.setViperClarity(1, 50.0f);
    chain.setViperFET(1, -20.0f);
    chain.setViperAnalogX(1);
    chain.setViperFET(99, 1.0f);
    chain.setStageEnabled(VJ_STAGE_VIPER_IIR, true);
    chain.setStageEnabled(VJ_STAGE_VIPER_BASS, true);
    chain.setStageEnabled(VJ_STAGE_VIPER_REVERB, true);
    chain.setStageEnabled(VJ_STAGE_VIPER_CLARITY, true);
    chain.setStageEnabled(VJ_STAGE_VIPER_FET, true);
    chain.setStageEnabled(VJ_STAGE_VIPER_ANALOGX, true);
    float peak = 0;
    bool clamped = true;
    for (int b = 0; b < 4; b++) {
        std::vector<float> buf(4096 * 2);
        for (size_t i = 0; i < buf.size(); i += 2) {
            float s = sinf((float)(i + b) * 0.03f) * 0.4f;
            buf[i] = s;
            buf[i + 1] = s;
        }
        chain.process(buf);
        for (size_t i = 0; i < buf.size(); i++)
            if (buf[i] != buf[i] || buf[i] > 1.0f || buf[i] < -1.0f)
                clamped = false;
        if (b == 3)
            for (size_t i = 0; i < buf.size(); i++) {
                float a = buf[i] < 0 ? -buf[i] : buf[i];
                if (a > peak) peak = a;
            }
    }
    CHECK(clamped, "viper params finite + clamped");
    CHECK(peak > 0.01f, "viper chain outputs signal");
}

static void test_golden_sine(void) {    VipJamChain chain;
    chain.setSamplingRate(44100);
    chain.setStageEnabled(VJ_STAGE_JAMES_COMP, true);
    chain.setStageEnabled(VJ_STAGE_VIPER_BASS, true);
    chain.setStageEnabled(VJ_STAGE_LIMITER, true);
    const int frames = 44100;
    std::vector<float> buf(frames * 2);
    for (int i = 0; i < frames; i++) {
        float s = sinf(2.0f * 3.14159265f * 440.0f * i / 44100.0f) * 0.5f;
        buf[i * 2] = s;
        buf[i * 2 + 1] = s;
    }
    chain.process(buf);
    CHECK(allFiniteClamped(buf), "golden sine finite + clamped");
    double energy = 0;
    for (size_t i = 44100; i < buf.size(); i++)
        energy += (double)buf[i] * buf[i];
    double rms = sqrt(energy / (buf.size() - 44100));
    char msg[128];
    snprintf(msg, sizeof(msg), "golden sine rms sane (%f)", rms);
    CHECK(rms > 0.05 && rms < 0.9, msg);
}

int main(void) {
    setbuf(stdout, NULL);
    test_shim_new_ids();
    test_shim_classic_james();
    test_interleave();
    test_chain_passthrough();
    test_context_commands();
    test_engines_passthrough();
    test_engines_james_comp();
    test_engines_viper_bass();
    test_ddc_james();
    test_ddc_viper();
    test_ir_james();
    test_ir_viper();
    test_liveprog_single();
    test_liveprog_multi();
    test_parametric_james();
    test_parametric_viper();
    test_golden_sine();
    if (failures == 0) printf("ALL GREEN\n");
    else printf("%d FAILURES\n", failures);
    return failures;
}
