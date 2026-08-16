LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := piperos-pty
LOCAL_SRC_FILES := piperos_pty.c
LOCAL_CFLAGS := -Wall -Wextra -Werror -D_GNU_SOURCE
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
