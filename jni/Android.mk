LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := test_flir_usb
LOCAL_SRC_FILES := ../test_flir_usb.c
LOCAL_LDFLAGS   := -static

include $(BUILD_EXECUTABLE)