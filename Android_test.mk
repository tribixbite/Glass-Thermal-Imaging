LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := test_flir_usb
LOCAL_SRC_FILES := test_flir_usb.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libuvccamera/src/main/jni/libusb/libusb
LOCAL_LDLIBS := -llog
LOCAL_STATIC_LIBRARIES := usb1.0

include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE := usb1.0
LOCAL_SRC_FILES := libuvccamera/src/main/obj/local/armeabi-v7a/libusb100.a
include $(PREBUILT_STATIC_LIBRARY)