#include <jni.h>
#include <vector>

#include "include/JniGuard.h"
#include "include/StampEngine.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeColorSmudge(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jint mode, jfloat radiusPx,
        jfloat feathering, jboolean smearAlpha, jint paintColorArgb, jfloat dilution,
        jfloat baseColorRate, jfloat chargeDecayRate, jfloat pickupRate,
        jbyteArray sampleSourceRgba8, jint sampleSourceWidth, jint sampleSourceHeight) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized() || !dabData) return JNI_FALSE;
    const jsize length = env->GetArrayLength(dabData);
    constexpr int kStride = 8; // x,y,smudgeRate,colorRate,opacity,smudgeRadius,colorRateMultiplier,distanceDeltaPx
    if (length < kStride * 2 || length % kStride != 0) return JNI_FALSE;
    jfloat* ptr = env->GetFloatArrayElements(dabData, nullptr);
    if (!ptr) return JNI_FALSE;
    std::vector<graffux::ColorSmudgeDab> dabs;
    dabs.reserve(static_cast<size_t>(length / kStride));
    for (jsize i = 0; i < length; i += kStride) {
        graffux::ColorSmudgeDab dab{};
        dab.x = ptr[i];
        dab.y = ptr[i + 1];
        dab.smudgeRate = ptr[i + 2];
        dab.colorRate = ptr[i + 3];
        dab.opacity = ptr[i + 4];
        dab.smudgeRadius = ptr[i + 5];
        dab.colorRateMultiplier = ptr[i + 6];
        dab.distanceDeltaPx = ptr[i + 7];
        dab.baseColorRate = baseColorRate;
        dab.chargeDecayRate = chargeDecayRate;
        dab.pickupRate = pickupRate;
        dabs.push_back(dab);
    }
    env->ReleaseFloatArrayElements(dabData, ptr, JNI_ABORT);

    // Item 11 (Sample Merged): a null sampleSourceRgba8 means "not requested" (smudge from the
    // active layer). A supplied but unusable source (non-positive dims, too few bytes, failed pin)
    // FAILS the call: silently smudging from the layer instead would paint the wrong colours while
    // reporting success.
    jbyte* sampleSourceData = nullptr;
    bool hasSampleSource = false;
    if (sampleSourceRgba8 != nullptr) {
        if (sampleSourceWidth <= 0 || sampleSourceHeight <= 0) return JNI_FALSE;
        const jsize sampleSourceLen = env->GetArrayLength(sampleSourceRgba8);
        const jlong required = static_cast<jlong>(sampleSourceWidth) * sampleSourceHeight * 4;
        if (static_cast<jlong>(sampleSourceLen) < required) return JNI_FALSE;
        sampleSourceData = env->GetByteArrayElements(sampleSourceRgba8, nullptr);
        if (!sampleSourceData) return JNI_FALSE;
        hasSampleSource = true;
    }

    bool ok = engine->colorSmudge(
        dabs, mode, radiusPx, feathering, smearAlpha == JNI_TRUE,
        static_cast<uint32_t>(paintColorArgb), dilution,
        hasSampleSource ? reinterpret_cast<const uint8_t*>(sampleSourceData) : nullptr,
        hasSampleSource ? sampleSourceWidth : 0, hasSampleSource ? sampleSourceHeight : 0);

    if (sampleSourceData) env->ReleaseByteArrayElements(sampleSourceRgba8, sampleSourceData, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeColorSmudgeBenchmarkInfo(
        JNIEnv* env, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
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
    JNI_CATCH(jlongArray())
}
