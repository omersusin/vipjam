LOCAL_PATH := $(call my-dir)
DSP_ROOT := $(LOCAL_PATH)/../..

include $(CLEAR_VARS)

LOCAL_MODULE := vipjam
LOCAL_SRC_FILES := \
    $(subst $(LOCAL_PATH)/,,$(wildcard $(DSP_ROOT)/hal/VipJamEffect.cpp)) \
    $(subst $(LOCAL_PATH)/,,$(wildcard $(DSP_ROOT)/src/VipJamChain.cpp)) \
    $(subst $(LOCAL_PATH)/,,$(wildcard $(DSP_ROOT)/src/viper_bridge.cpp)) \
    $(subst $(LOCAL_PATH)/,,$(wildcard $(DSP_ROOT)/src/VipJamShm.cpp)) \
    $(subst $(LOCAL_PATH)/,,$(wildcard $(DSP_ROOT)/src/VipJamLoudness.cpp)) \
    $(subst $(LOCAL_PATH)/,,$(wildcard $(DSP_ROOT)/src/james_bridge.c)) \
    $(addprefix ../../,$(shell cd $(DSP_ROOT) && find third_party/jamesdsp -name "*.c" | grep -v "jamesdsp/jamesdsp.c")) \
    $(addprefix ../../,$(shell cd $(DSP_ROOT) && find third_party/viper -name "*.cpp"))

LOCAL_C_INCLUDES := \
    $(DSP_ROOT)/include \
    $(DSP_ROOT)/src \
    $(DSP_ROOT)/hal/include \
    $(DSP_ROOT)/third_party \
    $(DSP_ROOT)/third_party/viper \
    $(DSP_ROOT)/third_party/jamesdsp \
    $(DSP_ROOT)/third_party/jamesdsp/jdsp

LOCAL_CFLAGS := -O3 -ffunction-sections -fdata-sections -fvisibility=hidden \
    -DVERSION_NAME='"0.1.0-fused"' -DVERSION_CODE=1
LOCAL_CONLYFLAGS := -include unistd.h -Wno-implicit-int -Wno-unused
LOCAL_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti -fvisibility-inlines-hidden \
    -Wall -Wno-unused-parameter -Wno-unused-variable -Wno-unused-function
LOCAL_LDLIBS := -llog -lm
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,--exclude-libs,ALL

include $(BUILD_SHARED_LIBRARY)
