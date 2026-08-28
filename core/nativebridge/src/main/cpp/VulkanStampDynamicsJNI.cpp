#include <jni.h>
#include <vector>
#include <algorithm>
#include "include/VulkanStampEngine.h"

namespace {
using graffux::GpuDab;
using graffux::GpuSecondaryDab;
using graffux::VulkanStampEngine;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeStampResolvedDabs(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jfloat hardness) {
    auto* engine = reinterpret_cast<VulkanStampEngine*>(handle);
    if (!engine || !dabData) return JNI_FALSE;
    const jsize count = env->GetArrayLength(dabData);
    constexpr int kStride = 10;  // x,y,radius,alpha,angle,r,g,b,a,flow
    if (count <= 0 || count % kStride != 0) return JNI_FALSE;

    jfloat* data = env->GetFloatArrayElements(dabData, nullptr);
    if (!data) return JNI_FALSE;
    std::vector<GpuDab> dabs;
    dabs.reserve(static_cast<size_t>(count / kStride));
    for (jsize i = 0; i < count; i += kStride) {
        GpuDab d{};
        d.x = data[i];
        d.y = data[i + 1];
        d.radius = data[i + 2];
        d.alpha = data[i + 3];
        d.angleDeg = data[i + 4];
        d.colorR = std::clamp(data[i + 5], 0.0f, 1.0f);
        d.colorG = std::clamp(data[i + 6], 0.0f, 1.0f);
        d.colorB = std::clamp(data[i + 7], 0.0f, 1.0f);
        d.colorA = std::clamp(data[i + 8], 0.0f, 1.0f);
        d.flow = std::max(data[i + 9], 0.0f);
        d.resolved = 1.0f;
        dabs.push_back(d);
    }
    env->ReleaseFloatArrayElements(dabData, data, JNI_ABORT);
    return engine->stampDabs(dabs, 0xFFFFFFFFu, std::clamp(hardness, 0.0f, 1.0f))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_VulkanStampEngine_nativeStampMaskedDabs(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jfloat hardness,
        jbyteArray maskAlpha8, jint maskWidth, jint maskHeight,
        jbyteArray grainAlpha8, jint grainWidth, jint grainHeight, jboolean grainCanvasLocked,
        jfloat grainScale, jfloat grainPhaseX, jfloat grainPhaseY,
        jfloatArray secondaryDabData, jbyteArray secondaryMaskAlpha8, jint secondaryMaskWidth,
        jint secondaryMaskHeight) {
    auto* engine = reinterpret_cast<VulkanStampEngine*>(handle);
    if (!engine || !dabData || !maskAlpha8) return JNI_FALSE;
    if (maskWidth <= 0 || maskHeight <= 0) return JNI_FALSE;
    const jsize maskLen = env->GetArrayLength(maskAlpha8);
    if (static_cast<jlong>(maskLen) < static_cast<jlong>(maskWidth) * maskHeight) return JNI_FALSE;

    const jsize count = env->GetArrayLength(dabData);
    constexpr int kStride = 11;  // x,y,radius,alpha,angle,r,g,b,a,flow,tipRatio
    if (count <= 0 || count % kStride != 0) return JNI_FALSE;

    jfloat* data = env->GetFloatArrayElements(dabData, nullptr);
    if (!data) return JNI_FALSE;
    std::vector<GpuDab> dabs;
    dabs.reserve(static_cast<size_t>(count / kStride));
    for (jsize i = 0; i < count; i += kStride) {
        GpuDab d{};
        d.x = data[i];
        d.y = data[i + 1];
        d.radius = data[i + 2];
        d.alpha = data[i + 3];
        d.angleDeg = data[i + 4];
        d.colorR = std::clamp(data[i + 5], 0.0f, 1.0f);
        d.colorG = std::clamp(data[i + 6], 0.0f, 1.0f);
        d.colorB = std::clamp(data[i + 7], 0.0f, 1.0f);
        d.colorA = std::clamp(data[i + 8], 0.0f, 1.0f);
        d.flow = std::max(data[i + 9], 0.0f);
        d.resolved = 1.0f;
        d.tipRatio = std::clamp(data[i + 10], 0.05f, 1.0f);
        dabs.push_back(d);
    }
    env->ReleaseFloatArrayElements(dabData, data, JNI_ABORT);

    jbyte* maskData = env->GetByteArrayElements(maskAlpha8, nullptr);
    if (!maskData) return JNI_FALSE;

    // Grain (item 15 follow-up) is optional -- a null grainAlpha8 (or non-positive dims) disables
    // it for this call, same "null disables" contract stampMaskedDabs() already documents.
    jbyte* grainData = nullptr;
    bool hasGrainArray = grainAlpha8 != nullptr && grainWidth > 0 && grainHeight > 0;
    if (hasGrainArray) {
        const jsize grainLen = env->GetArrayLength(grainAlpha8);
        if (static_cast<jlong>(grainLen) < static_cast<jlong>(grainWidth) * grainHeight) {
            env->ReleaseByteArrayElements(maskAlpha8, maskData, JNI_ABORT);
            return JNI_FALSE;
        }
        grainData = env->GetByteArrayElements(grainAlpha8, nullptr);
        if (!grainData) {
            env->ReleaseByteArrayElements(maskAlpha8, maskData, JNI_ABORT);
            return JNI_FALSE;
        }
    }

    // Masked/dual-brush (item 15 follow-up), same optionality pattern as grain above: a null
    // secondaryDabData or secondaryMaskAlpha8 (or non-positive secondary mask dims) disables it
    // for this call. Per stampMaskedDabs()'s contract, a non-empty secondaryDabs must be exactly
    // dabs.size() long -- checked here (stride-and-count) before native code ever sees a mismatch.
    jfloat* secondaryData = nullptr;
    jbyte* secondaryMaskData = nullptr;
    std::vector<GpuSecondaryDab> secondaryDabs;
    bool hasSecondaryArrays = secondaryDabData != nullptr && secondaryMaskAlpha8 != nullptr &&
                               secondaryMaskWidth > 0 && secondaryMaskHeight > 0;
    if (hasSecondaryArrays) {
        constexpr int kSecondaryStride = 8;  // x,y,radius,tipRatio,alpha,angle,flowMultiplier,keepInside
        const jsize secondaryCount = env->GetArrayLength(secondaryDabData);
        const jsize expectedCount = static_cast<jsize>(dabs.size()) * kSecondaryStride;
        const jsize secondaryMaskLen = env->GetArrayLength(secondaryMaskAlpha8);
        bool secondarySizesOk = secondaryCount == expectedCount &&
            static_cast<jlong>(secondaryMaskLen) >= static_cast<jlong>(secondaryMaskWidth) * secondaryMaskHeight;
        if (!secondarySizesOk) {
            hasSecondaryArrays = false;
        } else {
            secondaryData = env->GetFloatArrayElements(secondaryDabData, nullptr);
            secondaryMaskData = secondaryData ? env->GetByteArrayElements(secondaryMaskAlpha8, nullptr) : nullptr;
            if (!secondaryData || !secondaryMaskData) {
                if (secondaryData) env->ReleaseFloatArrayElements(secondaryDabData, secondaryData, JNI_ABORT);
                hasSecondaryArrays = false;
                secondaryData = nullptr;
                secondaryMaskData = nullptr;
            } else {
                secondaryDabs.reserve(dabs.size());
                for (jsize i = 0; i < secondaryCount; i += kSecondaryStride) {
                    GpuSecondaryDab sd{};
                    sd.x = secondaryData[i];
                    sd.y = secondaryData[i + 1];
                    sd.radius = secondaryData[i + 2];
                    sd.tipRatio = std::clamp(secondaryData[i + 3], 0.05f, 1.0f);
                    sd.alpha = secondaryData[i + 4];
                    sd.angleDeg = secondaryData[i + 5];
                    sd.flowMultiplier = std::max(secondaryData[i + 6], 0.0f);
                    sd.keepInside = secondaryData[i + 7];
                    secondaryDabs.push_back(sd);
                }
            }
        }
    }

    bool ok = engine->stampMaskedDabs(
        dabs, 0xFFFFFFFFu, std::clamp(hardness, 0.0f, 1.0f),
        reinterpret_cast<const uint8_t*>(maskData), maskWidth, maskHeight,
        hasGrainArray ? reinterpret_cast<const uint8_t*>(grainData) : nullptr,
        hasGrainArray ? grainWidth : 0, hasGrainArray ? grainHeight : 0,
        grainCanvasLocked == JNI_TRUE, grainScale, grainPhaseX, grainPhaseY,
        secondaryDabs,
        hasSecondaryArrays ? reinterpret_cast<const uint8_t*>(secondaryMaskData) : nullptr,
        hasSecondaryArrays ? secondaryMaskWidth : 0, hasSecondaryArrays ? secondaryMaskHeight : 0);

    if (secondaryMaskData) env->ReleaseByteArrayElements(secondaryMaskAlpha8, secondaryMaskData, JNI_ABORT);
    if (secondaryData) env->ReleaseFloatArrayElements(secondaryDabData, secondaryData, JNI_ABORT);
    if (grainData) env->ReleaseByteArrayElements(grainAlpha8, grainData, JNI_ABORT);
    env->ReleaseByteArrayElements(maskAlpha8, maskData, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
