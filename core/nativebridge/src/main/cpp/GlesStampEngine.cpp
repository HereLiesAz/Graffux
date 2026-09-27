// FILE: core/nativebridge/src/main/cpp/GlesStampEngine.cpp
#include "include/GlesStampEngine.h"

#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

#include "GlesShaders.h"

#ifdef __ANDROID__
#include <android/hardware_buffer.h>
#include <android/log.h>
#define GPU_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GlesStampEngine", __VA_ARGS__)
#else
#define GPU_LOGE(...) (std::fprintf(stderr, "GlesStampEngine: " __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace graffux {

// Makes the engine's context current for one public call and restores whatever the thread had
// current before (possibly another GL context, e.g. a GLSurfaceView's), so the engine never
// leaks its context into -- or steals one from -- the caller's thread.
class ScopedCurrent {
public:
    explicit ScopedCurrent(GlesStampEngine& e) : engine_(e) {
        prevDisplay_ = eglGetCurrentDisplay();
        prevContext_ = eglGetCurrentContext();
        prevDraw_ = eglGetCurrentSurface(EGL_DRAW);
        prevRead_ = eglGetCurrentSurface(EGL_READ);
        if (prevContext_ == e.context_) {
            ok_ = true;
            switched_ = false;
            return;
        }
        ok_ = eglMakeCurrent(e.display_, e.surface_, e.surface_, e.context_) == EGL_TRUE;
        switched_ = ok_;
        if (!ok_) GPU_LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
    }
    ~ScopedCurrent() {
        if (!switched_) return;
        if (prevContext_ != EGL_NO_CONTEXT && prevDisplay_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(prevDisplay_, prevDraw_, prevRead_, prevContext_);
        } else {
            eglMakeCurrent(engine_.display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
    }
    bool ok() const { return ok_; }

private:
    GlesStampEngine& engine_;
    EGLDisplay prevDisplay_;
    EGLContext prevContext_;
    EGLSurface prevDraw_;
    EGLSurface prevRead_;
    bool ok_ = false;
    bool switched_ = false;
};

namespace {

// std140 blocks of 4-byte scalars only: each member sits at the next 4-byte offset, so these C++
// structs are byte-identical to the shaders' Params blocks.
struct StampParams {
    uint32_t dabCount;
    float hardness;
    float colorR;
    float colorG;
    float colorB;
    float baseAlpha;
    int32_t originX;
    int32_t originY;
    float buildUp;
    float hasSubstrate;
    float hasPaintHeight;
    float substrateBaseHeight;
    float substrateHeightScale;
    float substrateTextureScale;
    float substrateOffsetX;
    float substrateOffsetY;
    float strokeMax;
    int32_t layerWidth;
    int32_t layerHeight;
};

struct MaskedParams {
    uint32_t dabCount;
    float hardness;
    float colorR;
    float colorG;
    float colorB;
    float baseAlpha;
    int32_t originX;
    int32_t originY;
    float grainCanvasLocked;
    float grainScale;
    float grainPhaseX;
    float grainPhaseY;
    float hasSecondary;
    float hasSubstrate;
    float hasPaintHeight;
    float substrateBaseHeight;
    float substrateHeightScale;
    float substrateTextureScale;
    float substrateOffsetX;
    float substrateOffsetY;
    int32_t layerWidth;
    int32_t layerHeight;
};

struct SmudgeParams {
    int32_t phase;
    int32_t centerX;
    int32_t centerY;
    int32_t radius;
    int32_t sampleRadius;
    int32_t smearAlpha;
    int32_t originX;
    int32_t originY;
    float soft;
    float smudgeRate;
    float colorRate;
    float opacity;
    float paintR;
    float paintG;
    float paintB;
    float paintA;
    float dilution;
    // Packed feature flags: +1 = Sample Merged, +2 = material-aware pigment mixing.
    float hasSampleMerged;
    float reservoirEnabled;
    float baseColorRate;
    float chargeDecayRate;
    float pickupRate;
    float colorRateMultiplier;
    float distanceDeltaPx;
    int32_t layerWidth;
    int32_t layerHeight;
};

// Stamp shader bindings (see shaders/*.comp).
constexpr GLuint kParamsBinding = 0;       // UBO
constexpr GLuint kDabBinding = 0;          // SSBO
constexpr GLuint kStampLayerBinding = 1;   // SSBO
constexpr GLuint kStrokeStateBinding = 4;  // SSBO
constexpr GLuint kSecondaryDabBinding = 5; // SSBO
constexpr GLuint kSmudgeCarrierBinding = 1;
constexpr GLuint kSmudgeLayerBinding = 3;
constexpr GLuint kFillBinding = 0;

const char* kFillSrc = R"GLSL(#version 310 es
layout(local_size_x = 256) in;
layout(std430, binding = 0) buffer Words { uint words[]; };
layout(location = 0) uniform uint count;
void main() {
    uint i = gl_GlobalInvocationID.x + gl_GlobalInvocationID.y * gl_NumWorkGroups.x * 256u;
    if (i < count) words[i] = 0u;
}
)GLSL";

uint64_t fnv1a(const void* data, size_t bytes) {
    const auto* p = static_cast<const uint8_t*>(data);
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < bytes; ++i) {
        h ^= p[i];
        h *= 1099511628211ULL;
    }
    return h;
}

bool glOk(const char* what) {
    GLenum err = glGetError();
    if (err == GL_NO_ERROR) return true;
    GPU_LOGE("%s: GL error 0x%x", what, err);
    return false;
}

GLuint compileProgram(const char* source, uint32_t tileSize) {
    // Every shader starts "#version 310 es\n"; TILE_SIZE goes right after it.
    std::string src(source);
    size_t eol = src.find('\n');
    if (tileSize > 0 && eol != std::string::npos) {
        src.insert(eol + 1, "#define TILE_SIZE " + std::to_string(tileSize) + "\n");
    }
    const char* text = src.c_str();
    GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
    glShaderSource(shader, 1, &text, nullptr);
    glCompileShader(shader);
    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        char log[2048] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        GPU_LOGE("compute shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, shader);
    glLinkProgram(program);
    glDeleteShader(shader);
    glGetProgramiv(program, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        char log[2048] = {};
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        GPU_LOGE("compute program link failed: %s", log);
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

void dispatch2d(uint32_t groupsX, uint32_t groupsY) {
    if (groupsX > 0 && groupsY > 0) glDispatchCompute(groupsX, groupsY, 1);
}

void bindTexture(GLuint unit, GLuint tex) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, tex);
}

std::mutex gBenchmarkMutex;
std::unordered_map<uint64_t, ColorSmudgeBenchmarkInfo> gBenchmarkCache;

// Padded bounding box of every dab, clipped to the layer -- the only pixels a dispatch can touch.
// Floors radius at 0.5 to match the shaders' own `max(radius, 0.5)`.
struct Region {
    int32_t x, y, w, h;
};
Region dabRegion(const std::vector<GpuDab>& dabs, int width, int height) {
    float r0 = std::max(dabs[0].radius, 0.5f);
    float minX = dabs[0].x - r0, maxX = dabs[0].x + r0;
    float minY = dabs[0].y - r0, maxY = dabs[0].y + r0;
    for (const GpuDab& d : dabs) {
        float r = std::max(d.radius, 0.5f);
        minX = std::min(minX, d.x - r); maxX = std::max(maxX, d.x + r);
        minY = std::min(minY, d.y - r); maxY = std::max(maxY, d.y + r);
    }
    int32_t originX = std::max(0, static_cast<int32_t>(std::floor(minX)));
    int32_t originY = std::max(0, static_cast<int32_t>(std::floor(minY)));
    int32_t endX = std::min(width, static_cast<int32_t>(std::ceil(maxX)) + 1);
    int32_t endY = std::min(height, static_cast<int32_t>(std::ceil(maxY)) + 1);
    return Region{originX, originY, std::max(0, endX - originX), std::max(0, endY - originY)};
}

}  // namespace

GlesStampEngine::~GlesStampEngine() { destroy(); }

bool GlesStampEngine::createContext() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) return false;
    if (eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) {
        GPU_LOGE("eglInitialize failed: 0x%x", eglGetError());
        return false;
    }
    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) return false;

    const char* exts = eglQueryString(display_, EGL_EXTENSIONS);
    const bool surfaceless = exts && std::strstr(exts, "EGL_KHR_surfaceless_context");
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
        EGL_SURFACE_TYPE, surfaceless ? 0 : EGL_PBUFFER_BIT,
        EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint numConfigs = 0;
    if (eglChooseConfig(display_, configAttribs, &config, 1, &numConfigs) != EGL_TRUE ||
        numConfigs < 1) {
        GPU_LOGE("no ES3 EGL config");
        return false;
    }
    // Ask for 3.1 explicitly (EGL_KHR_create_context); fall back to plain "3" and check below.
    const EGLint ctx31[] = {EGL_CONTEXT_CLIENT_VERSION, 3, 0x30FB /* MINOR_VERSION_KHR */, 1, EGL_NONE};
    const EGLint ctx3[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, ctx31);
    if (context_ == EGL_NO_CONTEXT) context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, ctx3);
    if (context_ == EGL_NO_CONTEXT) {
        GPU_LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }
    if (!surfaceless) {
        const EGLint pbuffer[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface_ = eglCreatePbufferSurface(display_, config, pbuffer);
        if (surface_ == EGL_NO_SURFACE) return false;
    }
    return true;
}

bool GlesStampEngine::createPrograms() {
    GLint major = 0, minor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    glGetIntegerv(GL_MINOR_VERSION, &minor);
    if (major < 3 || (major == 3 && minor < 1)) {
        GPU_LOGE("OpenGL ES %d.%d has no compute shaders", major, minor);
        return false;
    }
    GLint invocations = 0;
    glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, &invocations);
    stampTileSize_ = invocations >= 256 ? 16 : 8;

    stampProgram_ = compileProgram(kStampCompSrc, stampTileSize_);
    fillProgram_ = compileProgram(kFillSrc, 0);
    if (!stampProgram_ || !fillProgram_) return false;
    glGenBuffers(1, &paramsBuffer_);
    glBindBuffer(GL_UNIFORM_BUFFER, paramsBuffer_);
    glBufferData(GL_UNIFORM_BUFFER, 256, nullptr, GL_DYNAMIC_DRAW);

    // 1x1 stand-ins so every sampler a shader declares is always bound to a complete texture;
    // the shaders read them only when the matching feature flag is set.
    const uint8_t zero8[4] = {0, 0, 0, 0};
    const uint8_t white = 255;
    const float zeroF = 0.0f;
    return ensureTexture(substrateTex_, substrateW_, substrateH_, substrateHash_, 1, 1, GL_R8,
                         GL_RED, GL_UNSIGNED_BYTE, zero8, 1, GL_NEAREST, GL_REPEAT) &&
           ensureTexture(paintHeightTex_, paintHeightW_, paintHeightH_, paintHeightHash_, 1, 1,
                         GL_R32F, GL_RED, GL_FLOAT, &zeroF, 4, GL_NEAREST, GL_CLAMP_TO_EDGE) &&
           ensureTexture(maskTex_, maskW_, maskH_, maskHash_, 1, 1, GL_R8, GL_RED,
                         GL_UNSIGNED_BYTE, &white, 1, GL_LINEAR, GL_CLAMP_TO_EDGE) &&
           ensureTexture(grainTex_, grainW_, grainH_, grainHash_, 1, 1, GL_R8, GL_RED,
                         GL_UNSIGNED_BYTE, &white, 1, GL_NEAREST, GL_REPEAT) &&
           ensureTexture(secondaryMaskTex_, secondaryW_, secondaryH_, secondaryHash_, 1, 1, GL_R8,
                         GL_RED, GL_UNSIGNED_BYTE, &white, 1, GL_LINEAR, GL_CLAMP_TO_EDGE) &&
           ensureTexture(sampleSourceTex_, sampleSourceW_, sampleSourceH_, sampleSourceHash_, 1, 1,
                         GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, zero8, 4, GL_NEAREST,
                         GL_CLAMP_TO_EDGE) &&
           glOk("createPrograms");
}

bool GlesStampEngine::fillZero(GLuint buffer, size_t words) {
    if (words == 0) return true;
    glUseProgram(fillProgram_);
    glUniform1ui(0, static_cast<GLuint>(words));
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kFillBinding, buffer);
    size_t groups = (words + 255) / 256;
    uint32_t gx = static_cast<uint32_t>(std::min<size_t>(groups, 65535));
    uint32_t gy = static_cast<uint32_t>((groups + gx - 1) / gx);
    dispatch2d(gx, gy);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT |
                    GL_PIXEL_BUFFER_BARRIER_BIT);
    return glOk("fillZero");
}

bool GlesStampEngine::createLayer(int width, int height) {
    width_ = width;
    height_ = height;
    glGenBuffers(1, &layerBuffer_);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, layerBuffer_);
    glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(width) * height * 4, nullptr,
                 GL_DYNAMIC_COPY);
    if (!glOk("layer buffer")) return false;
    return fillZero(layerBuffer_, static_cast<size_t>(width) * height);
}

bool GlesStampEngine::init(int width, int height) {
    destroy();
    if (width <= 0 || height <= 0) return false;
    if (!createContext()) {
        destroy();
        return false;
    }
    bool ok;
    {
        ScopedCurrent current(*this);
        ok = current.ok() && createPrograms() && createLayer(width, height);
    }
    if (!ok) {
        destroy();
        return false;
    }
    markLayerFullyDirty();
    strokeStateDirty_ = true;
    return true;
}

bool GlesStampEngine::initWithHardwareBuffer(int width, int height) {
#ifdef __ANDROID__
    if (!init(width, height)) return false;
    ScopedCurrent current(*this);
    if (!current.ok()) return false;

    auto getClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto createImage =
        reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    auto imageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!getClientBuffer || !createImage || !imageTarget) return false;

    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&desc, &hardwareBuffer_) != 0) {
        hardwareBuffer_ = nullptr;
        return false;
    }
    const EGLint imageAttribs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image = createImage(display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                    getClientBuffer(hardwareBuffer_), imageAttribs);
    if (image == EGL_NO_IMAGE_KHR) {
        AHardwareBuffer_release(hardwareBuffer_);
        hardwareBuffer_ = nullptr;
        return false;
    }
    hardwareBufferImage_ = image;
    glGenTextures(1, &hardwareBufferTex_);
    glBindTexture(GL_TEXTURE_2D, hardwareBufferTex_);
    imageTarget(GL_TEXTURE_2D, static_cast<GLeglImageOES>(image));
    if (!glOk("EGLImage texture")) return false;
    return publishRegion(0, 0, width_, height_);
#else
    (void)width;
    (void)height;
    return false;
#endif
}

bool GlesStampEngine::publishRegion(int32_t x, int32_t y, int32_t w, int32_t h) {
    if (hardwareBufferTex_ == 0 || w <= 0 || h <= 0) return true;
    // Compute writes -> pixel-unpack read of the same buffer.
    glMemoryBarrier(GL_PIXEL_BUFFER_BARRIER_BIT);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, layerBuffer_);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, width_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glBindTexture(GL_TEXTURE_2D, hardwareBufferTex_);
    const auto offset = (static_cast<uintptr_t>(y) * width_ + x) * 4;
    glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE,
                    reinterpret_cast<const void*>(offset));
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    // The display side (HWUI) samples the buffer independently of this context: the pixels must
    // be complete before the call returns, as the fence wait guaranteed on the Vulkan engine.
    glFinish();
    return glOk("publishRegion");
}

void GlesStampEngine::expandDirtyRect(int32_t originX, int32_t originY, int32_t w, int32_t h) {
    if (w <= 0 || h <= 0) return;
    if (dirtyWidth_ <= 0 || dirtyHeight_ <= 0) {
        dirtyOriginX_ = originX;
        dirtyOriginY_ = originY;
        dirtyWidth_ = w;
        dirtyHeight_ = h;
        return;
    }
    int32_t x0 = std::min(dirtyOriginX_, originX);
    int32_t y0 = std::min(dirtyOriginY_, originY);
    int32_t x1 = std::max(dirtyOriginX_ + dirtyWidth_, originX + w);
    int32_t y1 = std::max(dirtyOriginY_ + dirtyHeight_, originY + h);
    dirtyOriginX_ = x0;
    dirtyOriginY_ = y0;
    dirtyWidth_ = x1 - x0;
    dirtyHeight_ = y1 - y0;
}

void GlesStampEngine::markLayerFullyDirty() {
    dirtyOriginX_ = 0;
    dirtyOriginY_ = 0;
    dirtyWidth_ = width_;
    dirtyHeight_ = height_;
}

bool GlesStampEngine::clear() {
    if (!isInitialized()) return false;
    ScopedCurrent current(*this);
    if (!current.ok() || !fillZero(layerBuffer_, static_cast<size_t>(width_) * height_)) return false;
    markLayerFullyDirty();
    strokeStateDirty_ = true;  // A cleared layer seeds a new stroke.
    return publishRegion(0, 0, width_, height_);
}

bool GlesStampEngine::upload(const uint8_t* inRgba8, size_t inSizeBytes) {
    if (!isInitialized() || inRgba8 == nullptr) return false;
    const size_t bytes = static_cast<size_t>(width_) * height_ * 4;
    if (inSizeBytes < bytes) return false;
    ScopedCurrent current(*this);
    if (!current.ok()) return false;
    glBindBuffer(GL_COPY_WRITE_BUFFER, layerBuffer_);
    glBufferSubData(GL_COPY_WRITE_BUFFER, 0, static_cast<GLsizeiptr>(bytes), inRgba8);
    if (!glOk("upload")) return false;
    markLayerFullyDirty();
    strokeStateDirty_ = true;  // Uploading seeds a new stroke.
    return publishRegion(0, 0, width_, height_);
}

bool GlesStampEngine::ensureTexture(GLuint& tex, int& texW, int& texH, uint64_t& hash, int w, int h,
                                   GLenum internalFormat, GLenum format, GLenum type,
                                   const void* data, size_t bytes, GLint filter, GLint wrap) {
    const uint64_t newHash = fnv1a(data, bytes);
    if (tex != 0 && texW == w && texH == h && hash == newHash) return true;
    glActiveTexture(GL_TEXTURE0);
    if (tex == 0 || texW != w || texH != h) {
        if (tex != 0) glDeleteTextures(1, &tex);
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, w, h);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        texW = w;
        texH = h;
    } else {
        glBindTexture(GL_TEXTURE_2D, tex);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, format, type, data);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    hash = newHash;
    return glOk("ensureTexture");
}

bool GlesStampEngine::uploadSubstrateHeight(const uint8_t* heightR8, int width, int height) {
    if (!isInitialized() || heightR8 == nullptr || width <= 0 || height <= 0) return false;
    ScopedCurrent current(*this);
    return current.ok() &&
           ensureTexture(substrateTex_, substrateW_, substrateH_, substrateHash_, width, height,
                         GL_R8, GL_RED, GL_UNSIGNED_BYTE, heightR8,
                         static_cast<size_t>(width) * height, GL_NEAREST, GL_REPEAT);
}

bool GlesStampEngine::uploadPaintHeight(const float* heightMap, int width, int height) {
    if (!isInitialized() || heightMap == nullptr || width != width_ || height != height_) return false;
    ScopedCurrent current(*this);
    return current.ok() &&
           ensureTexture(paintHeightTex_, paintHeightW_, paintHeightH_, paintHeightHash_, width,
                         height, GL_R32F, GL_RED, GL_FLOAT, heightMap,
                         static_cast<size_t>(width) * height * sizeof(float), GL_NEAREST,
                         GL_CLAMP_TO_EDGE);
}

void GlesStampEngine::uploadDabs(GLuint& buffer, size_t& capacityBytes, const void* data, size_t bytes) {
    if (buffer == 0) glGenBuffers(1, &buffer);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
    if (bytes > capacityBytes) {
        capacityBytes = std::max(bytes, capacityBytes * 2);
        glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(capacityBytes), nullptr,
                     GL_DYNAMIC_DRAW);
    }
    glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(bytes), data);
}

bool GlesStampEngine::ensureStrokeState() {
    if (strokeStateBuffer_ == 0) {
        glGenBuffers(1, &strokeStateBuffer_);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, strokeStateBuffer_);
        glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(width_) * height_ * 8,
                     nullptr, GL_DYNAMIC_COPY);
        if (!glOk("stroke state")) {
            glDeleteBuffers(1, &strokeStateBuffer_);
            strokeStateBuffer_ = 0;
            return false;
        }
        strokeStateDirty_ = true;
    }
    if (strokeStateDirty_) {
        // Zero = "untouched this stroke"; the next write captures the pre-stroke base.
        if (!fillZero(strokeStateBuffer_, static_cast<size_t>(width_) * height_ * 2)) return false;
        strokeStateDirty_ = false;
    }
    return true;
}

bool GlesStampEngine::stampDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb, float hardness,
                               bool buildUp, SubstrateStampParams substrate, bool strokeMax) {
    if (!isInitialized() || dabs.empty()) return false;
    ScopedCurrent current(*this);
    if (!current.ok()) return false;
    const bool useStrokeMax = strokeMax && !buildUp;
    if (useStrokeMax && !ensureStrokeState()) return false;

    uploadDabs(dabBuffer_, dabBufferBytes_, dabs.data(), dabs.size() * sizeof(GpuDab));
    const Region r = dabRegion(dabs, width_, height_);
    if (r.w > 0 && r.h > 0) {
        StampParams pc{};
        pc.dabCount = static_cast<uint32_t>(dabs.size());
        pc.hardness = hardness;
        // ARGB -> normalized RGB; alpha rides separately as baseAlpha (StampBrushRenderer's
        // `baseAlpha * d.alpha`).
        pc.colorR = static_cast<float>((colorArgb >> 16) & 0xFF) / 255.0f;
        pc.colorG = static_cast<float>((colorArgb >> 8) & 0xFF) / 255.0f;
        pc.colorB = static_cast<float>(colorArgb & 0xFF) / 255.0f;
        pc.baseAlpha = static_cast<float>((colorArgb >> 24) & 0xFF) / 255.0f;
        pc.originX = r.x;
        pc.originY = r.y;
        pc.buildUp = buildUp ? 1.0f : 0.0f;
        pc.hasSubstrate = substrate.enabled ? 1.0f : 0.0f;
        pc.hasPaintHeight = substrate.hasPaintHeight ? 1.0f : 0.0f;
        pc.substrateBaseHeight = std::clamp(substrate.baseHeight, 0.0f, 1.0f);
        pc.substrateHeightScale = std::clamp(substrate.heightScale, 0.0f, 1.0f);
        pc.substrateTextureScale = std::max(substrate.textureScale, 0.05f);
        pc.substrateOffsetX = substrate.textureOffsetX;
        pc.substrateOffsetY = substrate.textureOffsetY;
        pc.strokeMax = useStrokeMax ? 1.0f : 0.0f;
        pc.layerWidth = width_;
        pc.layerHeight = height_;

        glUseProgram(stampProgram_);
        glBindBuffer(GL_UNIFORM_BUFFER, paramsBuffer_);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, sizeof(pc), &pc);
        glBindBufferBase(GL_UNIFORM_BUFFER, kParamsBinding, paramsBuffer_);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kDabBinding, dabBuffer_);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kStampLayerBinding, layerBuffer_);
        // Unread unless strokeMax; any buffer satisfies the binding otherwise.
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kStrokeStateBinding,
                         strokeStateBuffer_ != 0 ? strokeStateBuffer_ : dabBuffer_);
        bindTexture(2, substrateTex_);
        bindTexture(3, paintHeightTex_);
        dispatch2d((static_cast<uint32_t>(r.w) + stampTileSize_ - 1) / stampTileSize_,
                   (static_cast<uint32_t>(r.h) + stampTileSize_ - 1) / stampTileSize_);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT |
                        GL_PIXEL_BUFFER_BARRIER_BIT);
        if (!glOk("stampDabs")) return false;
    }
    expandDirtyRect(r.x, r.y, r.w, r.h);
    return publishRegion(r.x, r.y, r.w, r.h);
}

bool GlesStampEngine::stampMaskedDabs(const std::vector<GpuDab>& dabs, uint32_t colorArgb,
                                     float hardness, const uint8_t* maskAlpha8, int maskWidth,
                                     int maskHeight, const uint8_t* grainAlpha8, int grainWidth,
                                     int grainHeight, bool grainCanvasLocked, float grainScale,
                                     float grainPhaseX, float grainPhaseY,
                                     const std::vector<GpuSecondaryDab>& secondaryDabs,
                                     const uint8_t* secondaryMaskAlpha8, int secondaryMaskWidth,
                                     int secondaryMaskHeight, SubstrateStampParams substrate) {
    if (!isInitialized() || dabs.empty() || maskAlpha8 == nullptr) return false;
    if (maskWidth <= 0 || maskHeight <= 0) return false;
    // Dual-brush is per-stroke: a non-empty secondary list must line up 1:1 with the dabs.
    if (!secondaryDabs.empty() && secondaryDabs.size() != dabs.size()) return false;
    ScopedCurrent current(*this);
    if (!current.ok()) return false;

    if (maskedProgram_ == 0) {
        maskedProgram_ = compileProgram(kStampMaskedCompSrc, stampTileSize_);
        if (maskedProgram_ == 0) return false;
    }
    if (!ensureTexture(maskTex_, maskW_, maskH_, maskHash_, maskWidth, maskHeight, GL_R8, GL_RED,
                       GL_UNSIGNED_BYTE, maskAlpha8, static_cast<size_t>(maskWidth) * maskHeight,
                       GL_LINEAR, GL_CLAMP_TO_EDGE)) {
        return false;
    }
    // Grain: the real tile if supplied, else a 1x1 white tile (the shader's multiply is a no-op).
    const bool haveGrain = grainAlpha8 != nullptr && grainWidth > 0 && grainHeight > 0;
    const uint8_t white = 255;
    if (!ensureTexture(grainTex_, grainW_, grainH_, grainHash_, haveGrain ? grainWidth : 1,
                       haveGrain ? grainHeight : 1, GL_R8, GL_RED, GL_UNSIGNED_BYTE,
                       haveGrain ? grainAlpha8 : &white,
                       haveGrain ? static_cast<size_t>(grainWidth) * grainHeight : 1, GL_NEAREST,
                       GL_REPEAT)) {
        return false;
    }
    const bool haveSecondary = !secondaryDabs.empty() && secondaryMaskAlpha8 != nullptr &&
                               secondaryMaskWidth > 0 && secondaryMaskHeight > 0;
    if (!ensureTexture(secondaryMaskTex_, secondaryW_, secondaryH_, secondaryHash_,
                       haveSecondary ? secondaryMaskWidth : 1, haveSecondary ? secondaryMaskHeight : 1,
                       GL_R8, GL_RED, GL_UNSIGNED_BYTE, haveSecondary ? secondaryMaskAlpha8 : &white,
                       haveSecondary ? static_cast<size_t>(secondaryMaskWidth) * secondaryMaskHeight : 1,
                       GL_LINEAR, GL_CLAMP_TO_EDGE)) {
        return false;
    }
    uploadDabs(dabBuffer_, dabBufferBytes_, dabs.data(), dabs.size() * sizeof(GpuDab));
    if (haveSecondary) {
        uploadDabs(secondaryDabBuffer_, secondaryDabBufferBytes_, secondaryDabs.data(),
                   secondaryDabs.size() * sizeof(GpuSecondaryDab));
    } else {
        const GpuSecondaryDab dummy{};
        uploadDabs(secondaryDabBuffer_, secondaryDabBufferBytes_, &dummy, sizeof(dummy));
    }

    const Region r = dabRegion(dabs, width_, height_);
    if (r.w > 0 && r.h > 0) {
        MaskedParams pc{};
        pc.dabCount = static_cast<uint32_t>(dabs.size());
        pc.hardness = hardness;
        pc.colorR = static_cast<float>((colorArgb >> 16) & 0xFF) / 255.0f;
        pc.colorG = static_cast<float>((colorArgb >> 8) & 0xFF) / 255.0f;
        pc.colorB = static_cast<float>(colorArgb & 0xFF) / 255.0f;
        pc.baseAlpha = static_cast<float>((colorArgb >> 24) & 0xFF) / 255.0f;
        pc.originX = r.x;
        pc.originY = r.y;
        pc.grainCanvasLocked = grainCanvasLocked ? 1.0f : 0.0f;
        pc.grainScale = haveGrain ? grainScale : 1.0f;
        pc.grainPhaseX = haveGrain ? grainPhaseX : 0.0f;
        pc.grainPhaseY = haveGrain ? grainPhaseY : 0.0f;
        pc.hasSecondary = haveSecondary ? 1.0f : 0.0f;
        pc.hasSubstrate = substrate.enabled ? 1.0f : 0.0f;
        pc.hasPaintHeight = substrate.hasPaintHeight ? 1.0f : 0.0f;
        pc.substrateBaseHeight = std::clamp(substrate.baseHeight, 0.0f, 1.0f);
        pc.substrateHeightScale = std::clamp(substrate.heightScale, 0.0f, 1.0f);
        pc.substrateTextureScale = std::max(substrate.textureScale, 0.05f);
        pc.substrateOffsetX = substrate.textureOffsetX;
        pc.substrateOffsetY = substrate.textureOffsetY;
        pc.layerWidth = width_;
        pc.layerHeight = height_;

        glUseProgram(maskedProgram_);
        glBindBuffer(GL_UNIFORM_BUFFER, paramsBuffer_);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, sizeof(pc), &pc);
        glBindBufferBase(GL_UNIFORM_BUFFER, kParamsBinding, paramsBuffer_);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kDabBinding, dabBuffer_);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kStampLayerBinding, layerBuffer_);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kSecondaryDabBinding, secondaryDabBuffer_);
        bindTexture(2, maskTex_);
        bindTexture(3, grainTex_);
        bindTexture(4, secondaryMaskTex_);
        bindTexture(6, substrateTex_);
        bindTexture(7, paintHeightTex_);
        dispatch2d((static_cast<uint32_t>(r.w) + stampTileSize_ - 1) / stampTileSize_,
                   (static_cast<uint32_t>(r.h) + stampTileSize_ - 1) / stampTileSize_);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT |
                        GL_PIXEL_BUFFER_BARRIER_BIT);
        if (!glOk("stampMaskedDabs")) return false;
    }
    expandDirtyRect(r.x, r.y, r.w, r.h);
    return publishRegion(r.x, r.y, r.w, r.h);
}

bool GlesStampEngine::ensureSmudgePrograms() {
    if (smudgeProgram8_ == 0) smudgeProgram8_ = compileProgram(kColorSmudgeCompSrc, 8);
    if (smudgeProgram16_ == 0 && stampTileSize_ >= 16) {
        smudgeProgram16_ = compileProgram(kColorSmudgeCompSrc, 16);
    }
    return smudgeProgram8_ != 0;
}

bool GlesStampEngine::runColorSmudgePlan(const std::vector<ColorSmudgeDab>& dabs, int mode,
                                        float radiusPx, float feathering, bool smearAlpha,
                                        uint32_t paintColorArgb, GLuint program, uint32_t tileSize,
                                        float dilution, bool hasSampleMerged) {
    if (dabs.size() < 2 || program == 0) return true;
    const int baseMode = mode & 1;         // 0=Smear, 1=Dulling
    const bool pigmentMixing = mode >= 2;  // 2/3 are the pigment variants of 0/1.
    const bool reservoirEnabled = dabs.front().pickupRate > 0.0f;
    const int radius = std::max(1, static_cast<int>(radiusPx));
    const int diameter = radius * 2 + 1;

    // Spatial carrier plus a tail for the reservoir's three vec4 slots.
    const size_t carrierBytes =
        (static_cast<size_t>(diameter) * diameter * 3 / 2 + 64) * sizeof(float) * 4;
    if (smudgeCarrier_ == 0 || carrierBytes > smudgeCarrierBytes_) {
        if (smudgeCarrier_ == 0) glGenBuffers(1, &smudgeCarrier_);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, smudgeCarrier_);
        glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(carrierBytes), nullptr,
                     GL_DYNAMIC_COPY);
        smudgeCarrierBytes_ = carrierBytes;
    }

    glUseProgram(program);
    glBindBufferBase(GL_UNIFORM_BUFFER, kParamsBinding, paramsBuffer_);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kSmudgeCarrierBinding, smudgeCarrier_);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, kSmudgeLayerBinding, layerBuffer_);
    bindTexture(2, sampleSourceTex_);
    glBindBuffer(GL_UNIFORM_BUFFER, paramsBuffer_);

    const float soft = 0.25f + std::clamp(feathering, 0.0f, 1.0f) * 0.7f;
    const float paintR = static_cast<float>((paintColorArgb >> 16) & 0xFF) / 255.0f;
    const float paintG = static_cast<float>((paintColorArgb >> 8) & 0xFF) / 255.0f;
    const float paintB = static_cast<float>(paintColorArgb & 0xFF) / 255.0f;
    const float paintA = static_cast<float>((paintColorArgb >> 24) & 0xFF) / 255.0f;
    const uint32_t brushGroups = (static_cast<uint32_t>(diameter) + tileSize - 1) / tileSize;

    // Every stage reads what the previous one wrote: order is the whole point of this pass.
    auto run = [&](int phase, const ColorSmudgeDab& d, uint32_t groups) {
        SmudgeParams pc{};
        pc.phase = phase;
        pc.centerX = static_cast<int32_t>(d.x);
        pc.centerY = static_cast<int32_t>(d.y);
        pc.radius = radius;
        pc.sampleRadius = std::max(1, static_cast<int>(std::lround(radiusPx * d.smudgeRadius)));
        pc.smearAlpha = smearAlpha ? 1 : 0;
        pc.soft = soft;
        pc.smudgeRate = std::clamp(d.smudgeRate, 0.0f, 1.0f);
        pc.colorRate = std::clamp(d.colorRate, 0.0f, 1.0f);
        pc.opacity = std::clamp(d.opacity, 0.0f, 1.0f);
        pc.paintR = paintR;
        pc.paintG = paintG;
        pc.paintB = paintB;
        pc.paintA = paintA;
        pc.dilution = std::clamp(dilution, 0.0f, 1.0f);
        pc.hasSampleMerged = (hasSampleMerged ? 1.0f : 0.0f) + (pigmentMixing ? 2.0f : 0.0f);
        pc.reservoirEnabled = reservoirEnabled ? 1.0f : 0.0f;
        pc.baseColorRate = d.baseColorRate;
        pc.chargeDecayRate = std::max(d.chargeDecayRate, 0.0f);
        pc.pickupRate = std::clamp(d.pickupRate, 0.0f, 1.0f);
        pc.colorRateMultiplier = std::max(d.colorRateMultiplier, 0.0f);
        pc.distanceDeltaPx = std::max(d.distanceDeltaPx, 0.0f);
        pc.layerWidth = width_;
        pc.layerHeight = height_;
        glBufferSubData(GL_UNIFORM_BUFFER, 0, sizeof(pc), &pc);
        dispatch2d(groups, groups);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    };

    // Stroke-local reservoir state once; pickup=0 skips every reservoir phase.
    if (reservoirEnabled) run(4, dabs.front(), 1);
    if (baseMode == 0) {
        run(0, dabs.front(), brushGroups);
        for (size_t i = 1; i < dabs.size(); ++i) {
            if (reservoirEnabled) {
                // CPU order: decay -> sample pre-write contact -> paint -> pickup, so pickup only
                // contaminates later dabs, never the one that discovered the material.
                run(5, dabs[i], 1);
                run(7, dabs[i], 1);
            }
            run(1, dabs[i], brushGroups);
            if (reservoirEnabled) run(6, dabs[i], 1);
        }
    } else {
        for (size_t i = 1; i < dabs.size(); ++i) {
            if (reservoirEnabled) run(5, dabs[i], 1);
            run(2, dabs[i], 1);
            run(3, dabs[i], brushGroups);
            if (reservoirEnabled) run(6, dabs[i], 1);
        }
    }
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);
    return glOk("colorSmudge");
}

bool GlesStampEngine::benchmarkColorSmudge(float radiusPx) {
    if (smudgeBenchmark_.selectedTileSize != 0) return true;
    if (!ensureSmudgePrograms()) return false;
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const uint64_t key = renderer ? fnv1a(renderer, std::strlen(renderer)) : 0;
    {
        std::lock_guard<std::mutex> lock(gBenchmarkMutex);
        auto it = gBenchmarkCache.find(key);
        if (it != gBenchmarkCache.end()) {
            smudgeBenchmark_ = it->second;
            return true;
        }
    }
    if (smudgeProgram16_ == 0) {
        smudgeBenchmark_ = ColorSmudgeBenchmarkInfo{0, 0, 8u, 0, 0};
        return true;
    }

    // Opacity-0 synthetic smear at the canvas centre: exercises the whole ordered pass without
    // changing any pixel.
    const int cx = std::max(0, width_ / 2);
    const int cy = std::max(0, height_ / 2);
    std::vector<ColorSmudgeDab> synthetic;
    synthetic.reserve(13);
    for (int i = 0; i < 13; ++i) {
        synthetic.push_back(ColorSmudgeDab{
            static_cast<float>(cx + i - 6), static_cast<float>(cy), 0.65f, 0.0f, 0.0f, 1.0f,
        });
    }
    const float benchmarkRadius = std::clamp(radiusPx, 4.0f, 24.0f);
    auto timed = [&](GLuint program, uint32_t tile) -> uint64_t {
        const auto start = std::chrono::steady_clock::now();
        if (!runColorSmudgePlan(synthetic, 0, benchmarkRadius, 0.0f, true, 0xFFFFFFFFu, program,
                                tile, 0.0f, false)) {
            return UINT64_MAX;
        }
        glFinish();
        const auto end = std::chrono::steady_clock::now();
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count());
    };
    // Warm both programs first so first-use costs stay out of the choice.
    if (timed(smudgeProgram8_, 8) == UINT64_MAX || timed(smudgeProgram16_, 16) == UINT64_MAX) {
        return false;
    }
    const uint64_t ns8 = std::max<uint64_t>(timed(smudgeProgram8_, 8), 1);
    const uint64_t ns16 = std::max<uint64_t>(timed(smudgeProgram16_, 16), 1);
    smudgeBenchmark_ = ColorSmudgeBenchmarkInfo{0, 0, ns8 <= ns16 ? 8u : 16u, ns8, ns16};
    {
        std::lock_guard<std::mutex> lock(gBenchmarkMutex);
        gBenchmarkCache[key] = smudgeBenchmark_;
    }
    return true;
}

bool GlesStampEngine::colorSmudge(const std::vector<ColorSmudgeDab>& dabs, int mode, float radiusPx,
                                 float feathering, bool smearAlpha, uint32_t paintColorArgb,
                                 float dilution, const uint8_t* sampleSourceRgba8,
                                 int sampleSourceWidth, int sampleSourceHeight) {
    if (!isInitialized() || dabs.size() < 2) return false;
    ScopedCurrent current(*this);
    if (!current.ok() || !ensureSmudgePrograms() || !benchmarkColorSmudge(radiusPx)) return false;

    // Sample Merged: a fresh composite of the other layers each stroke (content-hashed, so an
    // unchanged composite isn't re-sent).
    const bool hasSampleMerged =
        sampleSourceRgba8 != nullptr && sampleSourceWidth > 0 && sampleSourceHeight > 0;
    if (hasSampleMerged &&
        !ensureTexture(sampleSourceTex_, sampleSourceW_, sampleSourceH_, sampleSourceHash_,
                       sampleSourceWidth, sampleSourceHeight, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE,
                       sampleSourceRgba8,
                       static_cast<size_t>(sampleSourceWidth) * sampleSourceHeight * 4, GL_NEAREST,
                       GL_CLAMP_TO_EDGE)) {
        return false;
    }
    const uint32_t tile = smudgeBenchmark_.selectedTileSize == 16 ? 16 : 8;
    const GLuint program = tile == 16 ? smudgeProgram16_ : smudgeProgram8_;
    if (!runColorSmudgePlan(dabs, mode, radiusPx, feathering, smearAlpha, paintColorArgb, program,
                            tile, dilution, hasSampleMerged)) {
        return false;
    }
    // Color Smudge doesn't track its own footprint yet: the whole layer is conservatively dirty.
    markLayerFullyDirty();
    return publishRegion(0, 0, width_, height_);
}

bool GlesStampEngine::readback(uint8_t* outRgba8, size_t outCapacityBytes) {
    if (!isInitialized() || outRgba8 == nullptr) return false;
    if (outCapacityBytes < static_cast<size_t>(width_) * height_ * 4) return false;
    if (dirtyWidth_ <= 0 || dirtyHeight_ <= 0) return true;
    ScopedCurrent current(*this);
    if (!current.ok()) return false;

    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    const size_t rowBytes = static_cast<size_t>(width_) * 4;
    const size_t offset = static_cast<size_t>(dirtyOriginY_) * rowBytes;
    const size_t length = static_cast<size_t>(dirtyHeight_) * rowBytes;
    glBindBuffer(GL_COPY_READ_BUFFER, layerBuffer_);
    const auto* mapped = static_cast<const uint8_t*>(glMapBufferRange(
        GL_COPY_READ_BUFFER, static_cast<GLintptr>(offset), static_cast<GLsizeiptr>(length),
        GL_MAP_READ_BIT));
    if (mapped == nullptr) {
        glOk("glMapBufferRange(readback)");
        return false;
    }
    const size_t colOffset = static_cast<size_t>(dirtyOriginX_) * 4;
    const size_t copyBytes = static_cast<size_t>(dirtyWidth_) * 4;
    for (int32_t row = 0; row < dirtyHeight_; ++row) {
        std::memcpy(outRgba8 + offset + row * rowBytes + colOffset,
                    mapped + row * rowBytes + colOffset, copyBytes);
    }
    glUnmapBuffer(GL_COPY_READ_BUFFER);
    dirtyWidth_ = 0;
    dirtyHeight_ = 0;
    return glOk("readback");
}

void GlesStampEngine::destroy() {
    if (context_ != EGL_NO_CONTEXT) {
        {
            ScopedCurrent current(*this);
            if (current.ok()) {
                GLuint buffers[] = {layerBuffer_, strokeStateBuffer_, paramsBuffer_, dabBuffer_,
                                    secondaryDabBuffer_, smudgeCarrier_};
                glDeleteBuffers(sizeof(buffers) / sizeof(buffers[0]), buffers);
                GLuint textures[] = {maskTex_, grainTex_, secondaryMaskTex_, substrateTex_,
                                     paintHeightTex_, sampleSourceTex_, hardwareBufferTex_};
                glDeleteTextures(sizeof(textures) / sizeof(textures[0]), textures);
                for (GLuint p : {stampProgram_, maskedProgram_, fillProgram_, smudgeProgram8_,
                                 smudgeProgram16_}) {
                    if (p != 0) glDeleteProgram(p);
                }
#ifdef __ANDROID__
                if (hardwareBufferImage_ != nullptr) {
                    auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                        eglGetProcAddress("eglDestroyImageKHR"));
                    if (destroyImage) destroyImage(display_, hardwareBufferImage_);
                }
#endif
            }
        }
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        eglDestroyContext(display_, context_);
        // Never eglTerminate: the display is process-wide and other contexts (MobileGS) share it.
    }
#ifdef __ANDROID__
    if (hardwareBuffer_ != nullptr) AHardwareBuffer_release(hardwareBuffer_);
#endif
    hardwareBuffer_ = nullptr;
    hardwareBufferImage_ = nullptr;
    context_ = EGL_NO_CONTEXT;
    surface_ = EGL_NO_SURFACE;
    display_ = EGL_NO_DISPLAY;
    width_ = height_ = 0;
    layerBuffer_ = strokeStateBuffer_ = paramsBuffer_ = dabBuffer_ = secondaryDabBuffer_ = 0;
    smudgeCarrier_ = 0;
    dabBufferBytes_ = secondaryDabBufferBytes_ = smudgeCarrierBytes_ = 0;
    stampProgram_ = maskedProgram_ = fillProgram_ = smudgeProgram8_ = smudgeProgram16_ = 0;
    maskTex_ = grainTex_ = secondaryMaskTex_ = substrateTex_ = paintHeightTex_ = 0;
    sampleSourceTex_ = hardwareBufferTex_ = 0;
    maskW_ = maskH_ = grainW_ = grainH_ = secondaryW_ = secondaryH_ = 0;
    substrateW_ = substrateH_ = paintHeightW_ = paintHeightH_ = sampleSourceW_ = sampleSourceH_ = 0;
    maskHash_ = grainHash_ = secondaryHash_ = substrateHash_ = paintHeightHash_ = sampleSourceHash_ = 0;
    smudgeBenchmark_ = {};
    strokeStateDirty_ = true;
    dirtyOriginX_ = dirtyOriginY_ = dirtyWidth_ = dirtyHeight_ = 0;
}

}  // namespace graffux
