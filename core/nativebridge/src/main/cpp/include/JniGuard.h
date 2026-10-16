#pragma once
#include <android/log.h>
#include <exception>

// C++ exceptions (std::bad_alloc, cv::Exception, std::length_error, ...) must never unwind across the
// JNI boundary: that is std::terminate for the whole process. Every JNI entry point wraps its body in
//
//     JNI_TRY
//         ...body...
//     JNI_CATCH(JNI_FALSE)        // or JNI_CATCH(), JNI_CATCH(nullptr), JNI_CATCH(0)
//
// which logs and returns the "failed" value (false / 0 / null / nothing).
#define JNI_TRY try {
#define JNI_CATCH(retval)                                                                      \
    } catch (const std::exception& e_) {                                                       \
        __android_log_print(ANDROID_LOG_ERROR, "JniGuard", "%s: native exception: %s",         \
                            __func__, e_.what());                                              \
        return retval;                                                                         \
    } catch (...) {                                                                            \
        __android_log_print(ANDROID_LOG_ERROR, "JniGuard", "%s: unknown native exception",     \
                            __func__);                                                         \
        return retval;                                                                         \
    }
