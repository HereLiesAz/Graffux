#include <jni.h>
#include <vector>

#include "include/VulkanStampEngine.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeColorSmudge(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jint mode, jfloat radiusPx,
        jfloat feathering, jboolean smearAlpha, jint paintColorArgb, jfloat dilution) {
    auto* engine = reinterpret_cast<graffux::VulkanStampEngine*>(handle);
    if (!engine || !engine->isInitialized() || !dabData) return JNI_FALSE;
    const jsize length = env->GetArrayLength(dabData);
    constexpr int kStride = 6; // x,y,smudgeRate,colorRate,opacity,smudgeRadius
    if (length < kStride * 2 || length % kStride != 0) return JNI_FALSE;
    jfloat* ptr = env->GetFloatArrayElements(dabData, nullptr);
    if (!ptr) return JNI_FALSE;
    std::vector<graffux::ColorSmudgeDab> dabs;
    dabs.reserve(static_cast<size_t>(length / kStride));
    for (jsize i = 0; i < length; i += kStride) {
        dabs.push_back(graffux::ColorSmudgeDab{
            ptr[i], ptr[i + 1], ptr[i + 2], ptr[i + 3], ptr[i + 4], ptr[i + 5],
        });
    }
    env->ReleaseFloatArrayElements(dabData, ptr, JNI_ABORT);
    return engine->colorSmudge(
        dabs, mode, radiusPx, feathering, smearAlpha == JNI_TRUE,
        static_cast<uint32_t>(paintColorArgb), dilution) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeColorSmudgeBenchmarkInfo(
        JNIEnv* env, jobject, jlong handle) {
    auto* engine = reinterpret_cast<graffux::VulkanStampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return nullptr;
    const auto info = engine->colorSmudgeBenchmarkInfo();
    jlong values[5] = {
        static_cast<jlong>(info.vendorId),
        static_cast<jlong>(info.deviceId),
        static_cast<jlong>(info.selectedTileSize),
        static_cast<jlong>(info.nanos8),
        static_cast<jlong>(info.nanos16),
    };
    jlongArray out = env->NewLongArray(5);
    if (!out) return nullptr;
    env->SetLongArrayRegion(out, 0, 5, values);
    return out;
}
