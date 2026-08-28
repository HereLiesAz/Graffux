#include <jni.h>
#include <vector>
#include <algorithm>
#include "include/VulkanStampEngine.h"

namespace {
using graffux::GpuDab;
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
        jbyteArray maskAlpha8, jint maskWidth, jint maskHeight) {
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
    bool ok = engine->stampMaskedDabs(
        dabs, 0xFFFFFFFFu, std::clamp(hardness, 0.0f, 1.0f),
        reinterpret_cast<const uint8_t*>(maskData), maskWidth, maskHeight);
    env->ReleaseByteArrayElements(maskAlpha8, maskData, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
