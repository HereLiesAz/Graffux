#include <jni.h>
#include <vector>
#include <algorithm>
#include "include/StampEngine.h"

namespace {
using graffux::GpuDab;
using graffux::GpuSecondaryDab;
using graffux::SubstrateStampParams;

SubstrateStampParams substrateParams(jboolean enabled, jboolean hasPaintHeight,
                                     jfloat baseHeight, jfloat heightScale,
                                     jfloat textureScale, jfloat offsetX, jfloat offsetY) {
    SubstrateStampParams out{};
    out.enabled = enabled == JNI_TRUE;
    out.hasPaintHeight = out.enabled && hasPaintHeight == JNI_TRUE;
    out.baseHeight = std::clamp(baseHeight, 0.0f, 1.0f);
    out.heightScale = std::clamp(heightScale, 0.0f, 1.0f);
    out.textureScale = std::max(textureScale, 0.05f);
    out.textureOffsetX = offsetX;
    out.textureOffsetY = offsetY;
    return out;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeUploadSubstrateHeight(
        JNIEnv* env, jobject, jlong handle, jbyteArray heightR8, jint width, jint height) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !heightR8 || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize count = env->GetArrayLength(heightR8);
    if (static_cast<jlong>(count) < static_cast<jlong>(width) * height) return JNI_FALSE;
    jbyte* data = env->GetByteArrayElements(heightR8, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->uploadSubstrateHeight(reinterpret_cast<const uint8_t*>(data), width, height);
    env->ReleaseByteArrayElements(heightR8, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeUploadPaintHeight(
        JNIEnv* env, jobject, jlong handle, jfloatArray heightMap, jint width, jint height) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !heightMap || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize count = env->GetArrayLength(heightMap);
    if (static_cast<jlong>(count) < static_cast<jlong>(width) * height) return JNI_FALSE;
    jfloat* data = env->GetFloatArrayElements(heightMap, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->uploadPaintHeight(data, width, height);
    env->ReleaseFloatArrayElements(heightMap, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeStampResolvedDabs(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jboolean buildUp,
        jboolean hasSubstrate, jboolean hasPaintHeight, jfloat substrateBaseHeight, jfloat substrateHeightScale,
        jfloat substrateTextureScale, jfloat substrateOffsetX, jfloat substrateOffsetY,
        jboolean strokeMax) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !dabData) return JNI_FALSE;
    const jsize count = env->GetArrayLength(dabData);
    constexpr int kStride = 15;  // old 11 fields + contactDepth,load,depositionRate,substrateResponse
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
        // Repurposed trailing float -- see GpuDab's doc comment: stamp.comp reads this as this
        // dab's own resolved hardness (not tipRatio) whenever `resolved` is set, as it always is
        // here.
        d.tipRatio = std::clamp(data[i + 10], 0.0f, 1.0f);
        d.contactDepth = std::clamp(data[i + 11], 0.0f, 1.0f);
        d.reservoirLoad = std::clamp(data[i + 12], 0.0f, 1.0f);
        d.depositionRate = std::clamp(data[i + 13], 0.0f, 1.0f);
        d.substrateResponse = std::clamp(data[i + 14], 0.0f, 1.0f);
        dabs.push_back(d);
    }
    env->ReleaseFloatArrayElements(dabData, data, JNI_ABORT);
    // pc.hardness is unused by the shader for every dab in this call (all resolved=1, so each
    // reads its own d.tipRatio-as-hardness instead) -- the value passed here is a placeholder.
    const auto substrate = substrateParams(
        hasSubstrate, hasPaintHeight, substrateBaseHeight, substrateHeightScale, substrateTextureScale,
        substrateOffsetX, substrateOffsetY);
    return engine->stampDabs(dabs, 0xFFFFFFFFu, 1.0f, buildUp == JNI_TRUE, substrate,
                             strokeMax == JNI_TRUE)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeStampMaskedDabs(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jfloat hardness,
        jbyteArray maskAlpha8, jint maskWidth, jint maskHeight,
        jbyteArray grainAlpha8, jint grainWidth, jint grainHeight, jboolean grainCanvasLocked,
        jfloat grainScale, jfloat grainPhaseX, jfloat grainPhaseY,
        jfloatArray secondaryDabData, jbyteArray secondaryMaskAlpha8, jint secondaryMaskWidth,
        jint secondaryMaskHeight, jboolean hasSubstrate, jboolean hasPaintHeight, jfloat substrateBaseHeight,
        jfloat substrateHeightScale, jfloat substrateTextureScale, jfloat substrateOffsetX,
        jfloat substrateOffsetY) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !dabData || !maskAlpha8) return JNI_FALSE;
    if (maskWidth <= 0 || maskHeight <= 0) return JNI_FALSE;
    const jsize maskLen = env->GetArrayLength(maskAlpha8);
    if (static_cast<jlong>(maskLen) < static_cast<jlong>(maskWidth) * maskHeight) return JNI_FALSE;

    const jsize count = env->GetArrayLength(dabData);
    constexpr int kStride = 15;  // old 11 fields + contactDepth,load,depositionRate,substrateResponse
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
        d.contactDepth = std::clamp(data[i + 11], 0.0f, 1.0f);
        d.reservoirLoad = std::clamp(data[i + 12], 0.0f, 1.0f);
        d.depositionRate = std::clamp(data[i + 13], 0.0f, 1.0f);
        d.substrateResponse = std::clamp(data[i + 14], 0.0f, 1.0f);
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
        hasSecondaryArrays ? secondaryMaskWidth : 0, hasSecondaryArrays ? secondaryMaskHeight : 0,
        substrateParams(hasSubstrate, hasPaintHeight, substrateBaseHeight, substrateHeightScale,
                        substrateTextureScale, substrateOffsetX, substrateOffsetY));

    if (secondaryMaskData) env->ReleaseByteArrayElements(secondaryMaskAlpha8, secondaryMaskData, JNI_ABORT);
    if (secondaryData) env->ReleaseFloatArrayElements(secondaryDabData, secondaryData, JNI_ABORT);
    if (grainData) env->ReleaseByteArrayElements(grainAlpha8, grainData, JNI_ABORT);
    env->ReleaseByteArrayElements(maskAlpha8, maskData, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Impasto v1 (StampEngine's optional methods; JNI_FALSE when the engine lacks them).

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeUploadImpastoHeight(
        JNIEnv* env, jobject, jlong handle, jfloatArray heights) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !heights) return JNI_FALSE;
    const jsize count = env->GetArrayLength(heights);
    jfloat* data = env->GetFloatArrayElements(heights, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->uploadImpastoHeight(data, static_cast<size_t>(count));
    env->ReleaseFloatArrayElements(heights, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeReadImpastoHeight(
        JNIEnv* env, jobject, jlong handle, jfloatArray out) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !out) return JNI_FALSE;
    const jsize count = env->GetArrayLength(out);
    jfloat* data = env->GetFloatArrayElements(out, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->readImpastoHeight(data, static_cast<size_t>(count));
    // Copy back only on success; abandon the buffer otherwise.
    env->ReleaseFloatArrayElements(out, data, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDepositImpastoHeight(
        JNIEnv* env, jobject, jlong handle, jfloatArray dabs, jfloat hardness, jfloat thickness) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !dabs) return JNI_FALSE;
    const jsize count = env->GetArrayLength(dabs);
    if (count % 4 != 0) return JNI_FALSE;
    jfloat* data = env->GetFloatArrayElements(dabs, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->depositImpastoHeight(data, static_cast<size_t>(count / 4), hardness, thickness);
    env->ReleaseFloatArrayElements(dabs, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeShadeImpastoRelief(
        JNIEnv*, jobject, jlong handle, jint x, jint y, jint w, jint h, jfloat azimuthDeg,
        jfloat elevationDeg, jfloat strength) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->shadeImpastoRelief(x, y, w, h, azimuthDeg, elevationDeg, strength) ? JNI_TRUE : JNI_FALSE;
}

// Impasto v2, the wet material simulation (StampEngine's optional wet* methods; JNI_FALSE when the
// engine lacks them). Kotlin's Int arrays carry the straight ARGB words and flags.

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuWetSimulation_nativeWetBegin(
        JNIEnv* env, jobject, jlong handle, jintArray raw, jfloatArray height, jfloatArray wetness,
        jintArray active, jint tileSize, jfloatArray media) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !raw || !height || !wetness || !active || !media) return JNI_FALSE;
    const jsize count = env->GetArrayLength(raw);
    if (env->GetArrayLength(height) < count || env->GetArrayLength(wetness) < count) return JNI_FALSE;
    const jsize tiles = env->GetArrayLength(active);
    const jsize mediaCount = env->GetArrayLength(media);
    jint* rawData = env->GetIntArrayElements(raw, nullptr);
    jfloat* heightData = env->GetFloatArrayElements(height, nullptr);
    jfloat* wetData = env->GetFloatArrayElements(wetness, nullptr);
    jint* activeData = env->GetIntArrayElements(active, nullptr);
    jfloat* mediaData = env->GetFloatArrayElements(media, nullptr);
    const bool ok = rawData && heightData && wetData && activeData && mediaData &&
                    engine->wetBegin(reinterpret_cast<const uint32_t*>(rawData), heightData, wetData,
                                     static_cast<size_t>(count),
                                     reinterpret_cast<const uint32_t*>(activeData),
                                     static_cast<size_t>(tiles), tileSize, mediaData,
                                     static_cast<size_t>(mediaCount));
    if (mediaData) env->ReleaseFloatArrayElements(media, mediaData, JNI_ABORT);
    if (activeData) env->ReleaseIntArrayElements(active, activeData, JNI_ABORT);
    if (wetData) env->ReleaseFloatArrayElements(wetness, wetData, JNI_ABORT);
    if (heightData) env->ReleaseFloatArrayElements(height, heightData, JNI_ABORT);
    if (rawData) env->ReleaseIntArrayElements(raw, rawData, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuWetSimulation_nativeWetBuffer(
        JNIEnv* env, jobject, jlong handle, jint which, jfloatArray data) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine) return JNI_FALSE;
    if (!data) return engine->wetBuffer(which, nullptr, 0) ? JNI_TRUE : JNI_FALSE;
    const jsize count = env->GetArrayLength(data);
    jfloat* values = env->GetFloatArrayElements(data, nullptr);
    if (!values) return JNI_FALSE;
    const bool ok = engine->wetBuffer(which, values, static_cast<size_t>(count));
    env->ReleaseFloatArrayElements(data, values, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuWetSimulation_nativeWetOp(
        JNIEnv* env, jobject, jlong handle, jint op, jfloatArray args) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine) return JNI_FALSE;
    if (!args) return engine->wetOp(op, nullptr, 0) ? JNI_TRUE : JNI_FALSE;
    const jsize count = env->GetArrayLength(args);
    jfloat* values = env->GetFloatArrayElements(args, nullptr);
    if (!values) return JNI_FALSE;
    const bool ok = engine->wetOp(op, values, static_cast<size_t>(count));
    env->ReleaseFloatArrayElements(args, values, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuWetSimulation_nativeWetRead(
        JNIEnv* env, jobject, jlong handle, jintArray raw, jfloatArray height, jfloatArray wetness) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !raw || !height || !wetness) return JNI_FALSE;
    const jsize count = env->GetArrayLength(raw);
    if (env->GetArrayLength(height) < count || env->GetArrayLength(wetness) < count) return JNI_FALSE;
    jint* rawData = env->GetIntArrayElements(raw, nullptr);
    jfloat* heightData = env->GetFloatArrayElements(height, nullptr);
    jfloat* wetData = env->GetFloatArrayElements(wetness, nullptr);
    const bool ok = rawData && heightData && wetData &&
                    engine->wetRead(reinterpret_cast<uint32_t*>(rawData), heightData, wetData,
                                    static_cast<size_t>(count));
    // Copy back only on success; abandon the buffers otherwise.
    const jint mode = ok ? 0 : JNI_ABORT;
    if (wetData) env->ReleaseFloatArrayElements(wetness, wetData, mode);
    if (heightData) env->ReleaseFloatArrayElements(height, heightData, mode);
    if (rawData) env->ReleaseIntArrayElements(raw, rawData, mode);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuWetSimulation_nativeWetReadFlags(
        JNIEnv* env, jobject, jlong handle, jintArray out) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !out) return JNI_FALSE;
    const jsize count = env->GetArrayLength(out);
    jint* data = env->GetIntArrayElements(out, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->wetReadFlags(reinterpret_cast<uint32_t*>(data), static_cast<size_t>(count));
    env->ReleaseIntArrayElements(out, data, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeBeginStroke(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    return engine && engine->beginStroke() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeSetStampMode(JNIEnv*, jobject, jlong handle,
                                                                             jint mode) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    return engine && engine->setStampMode(mode) ? JNI_TRUE : JNI_FALSE;
}

// Blur / Sharpen / Clone on the GPU. `params` is {factor, amount, offsetX, offsetY, wrap 0/1};
// `dabs` holds [x, y, radius, hardness] per dab.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuRetouch_nativeRetouch(JNIEnv* env, jobject, jlong handle, jint kind,
                                                                     jfloatArray params, jfloatArray dabs) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !params || !dabs || env->GetArrayLength(params) < 5) return JNI_FALSE;
    jfloat p[5];
    env->GetFloatArrayRegion(params, 0, 5, p);
    const jsize n = env->GetArrayLength(dabs);
    std::vector<float> flat(static_cast<size_t>(n));
    env->GetFloatArrayRegion(dabs, 0, n, flat.data());
    return engine->retouch(kind, static_cast<int>(p[0]), p[1], p[2], p[3], p[4] != 0.0f, flat.data(), n / 4)
               ? JNI_TRUE
               : JNI_FALSE;
}

// Selection at commit (StampEngine's optional selection methods; JNI_FALSE when unsupported).

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuSelection_nativeSetSelection(
        JNIEnv* env, jclass, jlong handle, jbyteArray coverage) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine) return JNI_FALSE;
    if (!coverage) return engine->setSelection(nullptr, 0) ? JNI_TRUE : JNI_FALSE;
    const jsize len = env->GetArrayLength(coverage);
    jbyte* data = env->GetByteArrayElements(coverage, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->setSelection(reinterpret_cast<const uint8_t*>(data), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(coverage, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuSelection_nativeSelectionOp(JNIEnv*, jclass, jlong handle, jint op) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    return engine && engine->selectionOp(op) ? JNI_TRUE : JNI_FALSE;
}

// The current stroke's painted rectangle into `out` (int[4] {x, y, w, h}).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuSelection_nativeStrokeRect(
        JNIEnv* env, jclass, jlong handle, jintArray out) {
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !out || env->GetArrayLength(out) < 4) return JNI_FALSE;
    int r[4] = {0, 0, 0, 0};
    if (!engine->strokeRect(r)) return JNI_FALSE;
    const jint values[4] = {r[0], r[1], r[2], r[3]};
    env->SetIntArrayRegion(out, 0, 4, values);
    return JNI_TRUE;
}
