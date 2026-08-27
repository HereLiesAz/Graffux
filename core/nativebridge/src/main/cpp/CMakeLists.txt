# FILE: core/nativebridge/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)

project("graffitixr_native")

# Google Ink Stroke Modeler requires C++20. Pinning it keeps prediction behaviour reproducible and
# prevents an upstream main-branch change from silently changing Graffux's stroke feel.
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

# OpenCV is imported from Maven Central (org.opencv:opencv). Since 4.9.0 that artifact ships a
# Prefab part, so with `buildFeatures { prefab = true }` in build.gradle.kts, AGP generates a CMake
# config package for it — nothing to vendor, fetch, or point OpenCV_DIR at. The package name is
# `OpenCV` and it exposes the imported target `OpenCV::opencv_java5` (headers + libopencv_java5.so).
find_package(OpenCV REQUIRED CONFIG)

# Set GLM include directory (header-only, committed under libs/glm)
set(GLM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/glm)

# Define the native library
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
    InkStrokePredictorJNI.cpp
)

# Include directories. Google Ink's public headers are rooted at the fetched repository itself
# (`ink_stroke_modeler/...`); its target does not propagate that root to consumers on Android.
# OpenCV's headers arrive through its imported target's INTERFACE_INCLUDE_DIRECTORIES.
target_include_directories(graffitixr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${GLM_DIR}
    ${ink_stroke_modeler_SOURCE_DIR}
)

# Find required Android libraries
find_library(log-lib log)
# libvulkan.so ships in the NDK's platform sysroot for API 24+ (Graffux's ABIs both carry it);
# VulkanStampEngine.cpp is compute-only (no VK_KHR_android_surface), so no Vulkan validation
# layers or loader beyond this system library are needed.
find_library(vulkan-lib vulkan)

# Link libraries - OpenGL ES 3.0 for splat rendering, jnigraphics for Bitmap manipulation,
# vulkan for VulkanStampEngine's GPU dab compositor (docs/Native Rendering Engine Design.md §9).
# OpenCV::opencv_java5 carries both the include dirs and the shared library to link against.
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
