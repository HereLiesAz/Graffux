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
    params.wobble_smoother_params.speed_floor = 1.31f;
    params.wobble_smoother_params.speed_ceiling = 1.44f;

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
    kalman.confidence_params.min_travel_speed = 3.0f;
    kalman.confidence_params.max_travel_speed = 15.0f;
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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativePredict(
        JNIEnv* env, jobject, jlong handle) {
    auto* engine = FromHandle(handle);
    if (!engine) return nullptr;

    engine->scratch.clear();
    if (!engine->modeler.Predict(engine->scratch).ok() || engine->scratch.empty()) {
        return nullptr;
    }

    const Result& result = engine->scratch.back();
    const jfloat values[4] = {
        result.position.x * kPixelsPerModelUnit,
        result.position.y * kPixelsPerModelUnit,
        static_cast<jfloat>(result.time.Value() * 1000.0),
        result.pressure,
    };
    jfloatArray output = env->NewFloatArray(4);
    if (!output) return nullptr;
    env->SetFloatArrayRegion(output, 0, 4, values);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_InkStrokePredictor_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    delete FromHandle(handle);
}
