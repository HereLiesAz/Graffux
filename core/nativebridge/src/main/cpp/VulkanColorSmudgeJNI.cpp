#include <jni.h>
#include <vector>

#include "include/VulkanStampEngine.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeColorSmudge(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jint mode, jfloat radiusPx,
        jfloat feathering, jboolean smearAlpha, jint paintColorArgb, jfloat dilution,
        jbyteArray sampleSourceRgba8, jint sampleSourceWidth, jint sampleSourceHeight) {
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

    // Item 11 (Sample Merged): a null sampleSourceRgba8 (or non-positive dims, or too few bytes)
    // disables it for this call, same "null disables" optionality every other optional-array JNI
    // entry point in this codebase follows -- see VulkanStampDynamicsJNI.cpp's grain/secondary-tip
    // handling for the fuller precedent this mirrors.
    jbyte* sampleSourceData = nullptr;
    bool hasSampleSource =
        sampleSourceRgba8 != nullptr && sampleSourceWidth > 0 && sampleSourceHeight > 0;
    if (hasSampleSource) {
        const jsize sampleSourceLen = env->GetArrayLength(sampleSourceRgba8);
        const jlong required = static_cast<jlong>(sampleSourceWidth) * sampleSourceHeight * 4;
        if (static_cast<jlong>(sampleSourceLen) < required) {
            hasSampleSource = false;
        } else {
            sampleSourceData = env->GetByteArrayElements(sampleSourceRgba8, nullptr);
            if (!sampleSourceData) hasSampleSource = false;
        }
    }

    bool ok = engine->colorSmudge(
        dabs, mode, radiusPx, feathering, smearAlpha == JNI_TRUE,
        static_cast<uint32_t>(paintColorArgb), dilution,
        hasSampleSource ? reinterpret_cast<const uint8_t*>(sampleSourceData) : nullptr,
        hasSampleSource ? sampleSourceWidth : 0, hasSampleSource ? sampleSourceHeight : 0);

    if (sampleSourceData) env->ReleaseByteArrayElements(sampleSourceRgba8, sampleSourceData, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
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
