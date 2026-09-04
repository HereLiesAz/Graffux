#include <jni.h>

#include <memory>
#include <vector>

#include "ink_stroke_modeler/params.h"
#include "ink_stroke_modeler/stroke_modeler.h"
#include "ink_stroke_modeler/types.h"

namespace {

using ink::stroke_model::Duration;
using ink::stroke_model::Input;
using ink::stroke_model::KalmanPredictorParams;
using ink::stroke_model::Result;
using ink::stroke_model::StrokeModelParams;
using ink::stroke_model::StrokeModeler;
using ink::stroke_model::Time;

// Keep the model in roughly the unit range Google's own Kalman tests tune for: 100 screen pixels
// become one model-space unit. Typical drawing motion of ~1000 px/s therefore arrives as ~10 u/s.
constexpr float kPixelsPerModelUnit = 100.0f;

StrokeModelParams MakeParams() {
    StrokeModelParams params;
    params.wobble_smoother_params.is_enabled = true;
    params.wobble_smoother_params.timeout = Duration(0.04);
    // ink-stroke-modeler's own reference tuning derives these as 2%/3% of the expected drawing
    // speed -- 0.2/0.3 u/s against this file's documented ~10 u/s baseline above. This used to read
    // 1.31/1.44 (~6.5x too high) with no comment justifying the deviation and no other tuning
    // constant in this function following that same multiple, which pulled any deliberate, careful
    // stroke under ~144 px/s into jitter-only wobble smoothing meant for near-stationary input.
    params.wobble_smoother_params.speed_floor = 0.2f;
    params.wobble_smoother_params.speed_ceiling = 0.3f;

    params.sampling_params.min_output_rate = 180.0;
    params.sampling_params.end_of_stroke_stopping_distance = 0.001f;
    params.sampling_params.end_of_stroke_max_iterations = 20;

    KalmanPredictorParams kalman;
    kalman.process_noise = 0.00026458;
    kalman.measurement_noise = 0.026458;
    kalman.min_stable_iteration = 4;
    kalman.max_time_samples = 20;
    kalman.min_catchup_velocity = 0.01f;
    kalman.acceleration_weight = 0.5f;
    kalman.jerk_weight = 0.1f;
    kalman.prediction_interval = Duration(1.0 / 60.0);
    kalman.confidence_params.desired_number_of_samples = 20;
    kalman.confidence_params.max_estimation_distance = 0.04f;
    // Reference tuning: 5%/25% of the expected drawing speed -- 0.5/2.5 u/s against the ~10 u/s
    // baseline above. Used to read 3.0/15.0 (~6x too high, same unexplained multiple as the
    // wobble-smoother constants above), excluding normal careful/slow sketching speeds from full
    // prediction confidence entirely.
    kalman.confidence_params.min_travel_speed = 0.5f;
    kalman.confidence_params.max_travel_speed = 2.5f;
    kalman.confidence_params.max_linear_deviation = 0.2f;
    kalman.confidence_params.baseline_linearity_confidence = 0.4f;
    params.prediction_params = kalman;
    return params;
}

struct Engine {
    StrokeModeler modeler;
    std::vector<Result> scratch;

    Engine() {
        modeler.Reset(MakeParams());
    }
};

Engine* FromHandle(jlong handle) {
    return reinterpret_cast<Engine*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeCreate(
        JNIEnv*, jobject) {
    auto engine = std::make_unique<Engine>();
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeReset(
        JNIEnv*, jobject, jlong handle) {
    auto* engine = FromHandle(handle);
    if (!engine) return JNI_FALSE;
    engine->scratch.clear();
    return engine->modeler.Reset().ok() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeRecord(
        JNIEnv*, jobject, jlong handle, jfloat x, jfloat y, jlong uptimeMillis,
        jfloat pressure, jboolean isDown) {
    auto* engine = FromHandle(handle);
    if (!engine) return JNI_FALSE;

    Input input;
    input.event_type = isDown ? Input::EventType::kDown : Input::EventType::kMove;
    input.position = {x / kPixelsPerModelUnit, y / kPixelsPerModelUnit};
    input.time = Time(static_cast<double>(uptimeMillis) / 1000.0);
    input.pressure = pressure;

    engine->scratch.clear();
    return engine->modeler.Update(input, engine->scratch).ok() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativePredict(
        JNIEnv* env, jobject, jlong handle) {
    auto* engine = FromHandle(handle);
    if (!engine) return nullptr;

    engine->scratch.clear();
    if (!engine->modeler.Predict(engine->scratch).ok() || engine->scratch.empty()) {
        return nullptr;
    }

    const Result& result = engine->scratch.back();
    // A jfloat (32-bit) can only represent integers exactly up to 2^24 (~4.66 hours of
    // uptimeMillis) -- past that, the predicted timestamp silently rounds to the nearest ~tens of
    // ms, larger than the whole ~16ms prediction horizon this models. jdouble (53-bit mantissa)
    // holds any real uptimeMillis value exactly; x/y/pressure lose nothing widening float->double.
    const jdouble values[4] = {
        static_cast<jdouble>(result.position.x * kPixelsPerModelUnit),
        static_cast<jdouble>(result.position.y * kPixelsPerModelUnit),
        result.time.Value() * 1000.0,
        static_cast<jdouble>(result.pressure),
    };
    jdoubleArray output = env->NewDoubleArray(4);
    if (!output) return nullptr;
    env->SetDoubleArrayRegion(output, 0, 4, values);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    delete FromHandle(handle);
}
