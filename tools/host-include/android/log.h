// Host stand-in for <android/log.h>: the native sources' log calls print nothing in the host checks (tools/check_*.cpp).
#pragma once
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
static inline int __android_log_print(int, const char *, const char *, ...) { return 0; }
