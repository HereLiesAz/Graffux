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

# GLSL source is authoritative. NDK glslc compiles the stamp shader plus two semantically identical
# Color Smudge workgroup strategies. The generated C++ headers live in the build tree, not source,
# so configuring a build never dirties the checkout. `-mfmt=c` already emits `{ ... }`.
find_program(GRAFFUX_GLSLC glslc
    HINTS
        "${ANDROID_NDK}/shader-tools/linux-x86_64"
        "${ANDROID_NDK}/shader-tools/windows-x86_64"
        "${ANDROID_NDK}/shader-tools/darwin-x86_64"
    NO_DEFAULT_PATH
)
if(NOT GRAFFUX_GLSLC)
    message(FATAL_ERROR "NDK glslc not found")
endif()
set(GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated-shaders")
file(MAKE_DIRECTORY "${GENERATED_SHADER_DIR}")

function(graffux_embed_shader SOURCE OUTPUT_HEADER SYMBOL)
    set(BODY "${GENERATED_SHADER_DIR}/${SYMBOL}.inc")
    execute_process(
        COMMAND "${GRAFFUX_GLSLC}" -O -mfmt=c --target-env=vulkan1.1 ${ARGN}
                -o "${BODY}" "${SOURCE}"
        RESULT_VARIABLE GLSLC_RESULT
    )
    if(NOT GLSLC_RESULT EQUAL 0)
        message(FATAL_ERROR "glslc failed for ${SOURCE}: ${GLSLC_RESULT}")
    endif()
    file(READ "${BODY}" WORDS)
    file(WRITE "${GENERATED_SHADER_DIR}/${OUTPUT_HEADER}"
"#pragma once\n#include <cstdint>\n#include <cstddef>\nnamespace graffux {\ninline constexpr uint32_t ${SYMBOL}[] = ${WORDS};\ninline constexpr size_t ${SYMBOL}Words = sizeof(${SYMBOL}) / sizeof(${SYMBOL}[0]);\n}  // namespace graffux\n")
endfunction()

graffux_embed_shader(
    "${CMAKE_CURRENT_SOURCE_DIR}/shaders/stamp.comp"
    "StampSpv.h"
    "kStampCompSpv"
)
graffux_embed_shader(
    "${CMAKE_CURRENT_SOURCE_DIR}/shaders/color_smudge.comp"
    "ColorSmudge8Spv.h"
    "kColorSmudge8CompSpv"
    -DTILE_SIZE=8
)
graffux_embed_shader(
    "${CMAKE_CURRENT_SOURCE_DIR}/shaders/color_smudge.comp"
    "ColorSmudge16Spv.h"
    "kColorSmudge16CompSpv"
    -DTILE_SIZE=16
)

add_library(graffitixr SHARED
    GraffitiJNI.cpp
    MobileGS.cpp
    StereoProcessor.cpp
    SuperPointDetector.cpp
    DistortionHead.cpp
    LowLightEnhancer.cpp
    ImageWarper.cpp
    MlasStub.cpp
    VulkanStampEngine.cpp
    VulkanStampEngineReuse.cpp
    VulkanStampDynamicsJNI.cpp
    VulkanColorSmudge.cpp
    VulkanColorSmudgeJNI.cpp
    InkStrokePredictorJNI.cpp
)

target_include_directories(graffitixr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${GENERATED_SHADER_DIR}
    ${GLM_DIR}
    ${ink_stroke_modeler_SOURCE_DIR}
)

find_library(log-lib log)
find_library(vulkan-lib vulkan)

target_link_libraries(graffitixr
    ${log-lib}
    OpenCV::opencv_java5
    GLESv3
    EGL
    android
    jnigraphics
    ${vulkan-lib}
    InkStrokeModeler::stroke_modeler
)
