LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := test_flir_usb_fixed
LOCAL_SRC_FILES := test_flir_usb_fixed.c
LOCAL_LDLIBS    := -llog
LOCAL_CFLAGS    := -O2 -Wall

include $(BUILD_EXECUTABLE)
