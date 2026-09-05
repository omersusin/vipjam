#include <jni.h>
#include <vector>
#include "VipJamChain.h"

static VipJamChain *handle(jlong h) {
    return reinterpret_cast<VipJamChain *>(h);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vipjam_dsp_VipJamNative_create(JNIEnv *env, jobject thiz,
                                        jint sampleRate) {
    (void)env;
    (void)thiz;
    VipJamChain *chain = new VipJamChain();
    chain->setSamplingRate((uint32_t)sampleRate);
    return reinterpret_cast<jlong>(chain);
}

JNIEXPORT void JNICALL
Java_com_vipjam_dsp_VipJamNative_free(JNIEnv *env, jobject thiz, jlong h) {
    (void)env;
    (void)thiz;
    delete handle(h);
}

JNIEXPORT void JNICALL
Java_com_vipjam_dsp_VipJamNative_setRate(JNIEnv *env, jobject thiz, jlong h,
                                         jint sampleRate) {
    (void)env;
    (void)thiz;
    handle(h)->setSamplingRate((uint32_t)sampleRate);
}

JNIEXPORT void JNICALL
Java_com_vipjam_dsp_VipJamNative_setMaster(JNIEnv *env, jobject thiz, jlong h,
                                           jboolean on) {
    (void)env;
    (void)thiz;
    handle(h)->setMasterEnabled(on != 0);
}

JNIEXPORT jint JNICALL
Java_com_vipjam_dsp_VipJamNative_setParam(JNIEnv *env, jobject thiz, jlong h,
                                          jint id, jfloat v0, jfloat v1,
                                          jfloat v2) {
    (void)env;
    (void)thiz;
    return handle(h)->setFusedParam(id, v0, v1, v2);
}

JNIEXPORT jint JNICALL
Java_com_vipjam_dsp_VipJamNative_process(JNIEnv *env, jobject thiz, jlong h,
                                         jfloatArray inArray,
                                         jfloatArray outArray, jint frames) {
    (void)thiz;
    if (inArray == nullptr || outArray == nullptr || frames <= 0) return -1;
    jsize need = frames * 2;
    if (env->GetArrayLength(inArray) < need ||
        env->GetArrayLength(outArray) < need)
        return -1;
    std::vector<float> buf((size_t)need);
    env->GetFloatArrayRegion(inArray, 0, need, buf.data());
    handle(h)->process(buf);
    env->SetFloatArrayRegion(outArray, 0, need, buf.data());
    return frames;
}

JNIEXPORT void JNICALL
Java_com_vipjam_dsp_VipJamNative_reset(JNIEnv *env, jobject thiz, jlong h) {
    (void)env;
    (void)thiz;
    handle(h)->reset();
}
}
