#include <jni.h>

#include <memory>

#include "ink_stroke_modeler/internal/prediction/kalman_predictor.h"
#include "ink_stroke_modeler/params.h"
#include "ink_stroke_modeler/types.h"

// Google Ink Stroke Modeler's Kalman predictor, used directly.
//
// This used to drive the whole StrokeModeler and read its Predict() output. That output is built
// for drawing a smoothed stroke, not for predicting the pen: it starts at the position modeler's
// spring-mass state, which deliberately trails the pen, and joins it to the Kalman estimate with a
// cubic "connector" before the real prediction begins. The next few frames fell on that connector,
// so Ink measured ~74 px behind the pen even one frame ahead (issue #425).
//
// KalmanPredictor alone is Ink's actual predictor: per-axis Kalman filters over the raw input that
// estimate position, velocity, acceleration and jerk at the latest sample (GetEstimatedState).
// nativeEstimate hands that state to Kotlin, which evaluates Ink's own cubic
// (p + v t + a t^2/2 + j t^3/6, kalman_predictor.cc EvaluateCubic) at any frame time.

namespace {

using ink::stroke_model::KalmanPredictor;
using ink::stroke_model::KalmanPredictorParams;
using ink::stroke_model::SamplingParams;
using ink::stroke_model::Time;
using ink::stroke_model::Vec2;

// Keep the model in roughly the unit range Google's own Kalman tests tune for: 100 screen pixels
// become one model-space unit. Typical drawing motion of ~1000 px/s therefore arrives as ~10 u/s.
constexpr float kPixelsPerModelUnit = 100.0f;

KalmanPredictorParams MakeKalmanParams() {
    KalmanPredictorParams kalman;
    kalman.process_noise = 0.00026458;
    kalman.measurement_noise = 0.026458;
    kalman.min_stable_iteration = 4;
    kalman.max_time_samples = 20;
    kalman.min_catchup_velocity = 0.01f;
    // Ink's own damping toward linear motion, applied inside GetEstimatedState().
    kalman.acceleration_weight = 0.5f;
    kalman.jerk_weight = 0.1f;
    return kalman;
}

struct Engine {
    KalmanPredictor predictor{MakeKalmanParams(), SamplingParams{}};
    double lastTimeSeconds = 0.0;
    float lastPressure = 1.0f;
};

Engine* FromHandle(jlong handle) {
    return reinterpret_cast<Engine*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeCreate(JNIEnv*, jobject) {
    auto engine = std::make_unique<Engine>();
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeReset(
        JNIEnv*, jobject, jlong handle) {
    auto* engine = FromHandle(handle);
    if (!engine) return JNI_FALSE;
    engine->predictor.Reset();
    engine->lastTimeSeconds = 0.0;
    engine->lastPressure = 1.0f;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeRecord(
        JNIEnv*, jobject, jlong handle, jfloat x, jfloat y, jlong uptimeMillis, jfloat pressure) {
    auto* engine = FromHandle(handle);
    if (!engine) return JNI_FALSE;
    const double seconds = static_cast<double>(uptimeMillis) / 1000.0;
    engine->predictor.Update(Vec2{x / kPixelsPerModelUnit, y / kPixelsPerModelUnit}, Time(seconds));
    engine->lastTimeSeconds = seconds;
    engine->lastPressure = pressure;
    return JNI_TRUE;
}

// [x, y, vx, vy, ax, ay, jx, jy, timeMs, pressure] in pixels and seconds (velocity px/s, etc.),
// estimated at the latest recorded sample; null until the Kalman filters are stable. Doubles so
// uptime milliseconds survive exactly past ~4.6 h (a float would round them).
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeEstimate(
        JNIEnv* env, jobject, jlong handle) {
    auto* engine = FromHandle(handle);
    if (!engine) return nullptr;
    const auto state = engine->predictor.GetEstimatedState();
    if (!state) return nullptr;

    constexpr jsize kCount = 10;
    const jdouble values[kCount] = {
        state->position.x * kPixelsPerModelUnit,
        state->position.y * kPixelsPerModelUnit,
        state->velocity.x * kPixelsPerModelUnit,
        state->velocity.y * kPixelsPerModelUnit,
        state->acceleration.x * kPixelsPerModelUnit,
        state->acceleration.y * kPixelsPerModelUnit,
        state->jerk.x * kPixelsPerModelUnit,
        state->jerk.y * kPixelsPerModelUnit,
        engine->lastTimeSeconds * 1000.0,
        engine->lastPressure,
    };
    jdoubleArray output = env->NewDoubleArray(kCount);
    if (!output) return nullptr;
    env->SetDoubleArrayRegion(output, 0, kCount, values);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    delete FromHandle(handle);
}
