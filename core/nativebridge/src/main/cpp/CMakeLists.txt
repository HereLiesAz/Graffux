# FILE: core/nativebridge/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)

project("graffitixr_native")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
set(INK_STROKE_MODELER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(INK_STROKE_MODELER_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    ink_stroke_modeler
    GIT_REPOSITORY https://github.com/google/ink-stroke-modeler.git
    GIT_TAG f2388813b0b25bc3e33d143d369a8367ab2e30c8
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(ink_stroke_modeler)

find_package(OpenCV REQUIRED CONFIG)
set(GLM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/glm)

# GPU stamp engine: only the wgpu adapter (WgpuStampEngine.cpp, which dlopen()s
# libgraffux_wgpu.so). The Vulkan and GLES 3.1 engines, their GLSL shaders and the glslc
# shader-embedding step were retired (docs/Native Rendering Engine Design.md, "Retired backends").

add_library(graffitixr SHARED
    GraffitiJNI.cpp
    MobileGS.cpp
    StereoProcessor.cpp
    SuperPointDetector.cpp
    DistortionHead.cpp
    LowLightEnhancer.cpp
    ImageWarper.cpp
    MlasStub.cpp
    StampDynamicsJNI.cpp
    ColorSmudgeJNI.cpp
    StampEngineFactory.cpp
    WgpuStampEngine.cpp
    InkStrokePredictorJNI.cpp
)

target_include_directories(graffitixr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${GLM_DIR}
    ${ink_stroke_modeler_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(graffitixr
    ${log-lib}
    OpenCV::opencv_java5
    GLESv3
    EGL
    android
    jnigraphics
    InkStrokeModeler::stroke_modeler
    # InkStrokePredictorJNI.cpp uses the Kalman predictor directly.
    InkStrokeModeler::kalman_predictor
)

# Raw touch heatmap helper (heatmap/heatmap_helper.c): a standalone executable the app runs through
# `su`, never loaded with System.loadLibrary. It is named lib*.so so the APK packager puts it in
# lib/<abi>/ and the installer extracts it, executable, into nativeLibraryDir (app/build.gradle.kts
# sets jniLibs.useLegacyPackaging for that). Plain C, no STL, no dependencies.
add_executable(graffux_heatmap
    heatmap/heatmap_helper.c
    heatmap/sec_parse.c
)
set_target_properties(graffux_heatmap PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "graffux_heatmap"
    SUFFIX ".so"
    C_STANDARD 11
)
target_compile_options(graffux_heatmap PRIVATE -Wall -Wextra -Werror)
