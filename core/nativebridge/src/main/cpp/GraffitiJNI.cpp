#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <GLES3/gl3.h>
#include <signal.h>
#include <unwind.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <algorithm>
#include <exception>
#include <mutex>
#include <vector>
#include <shared_mutex>
#include "include/ImageWarper.h"
#include "include/StampEngine.h"
#include "include/JniGuard.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "GraffitiJNI", __VA_ARGS__)

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

// Liquify's warper. gEngineMutex guards its *lifetime* (create/destroy): readers take a
// shared_lock, nativeInitImageWarper/nativeDestroy take a unique_lock, so a destroy() racing a
// concurrent stroke cannot free it mid-call.
ImageWarper* gImageWarper = nullptr;
static std::shared_mutex gEngineMutex;
JavaVM* gJvm = nullptr;

// ── Native crash capture ─────────────────────────────────────────────────────
// A SIGSEGV/SIGABRT in native code kills the process before the JVM
// UncaughtExceptionHandler can run, so the Kotlin CrashReporter never sees it
// ("crashed hard, nothing on screen"). This installs a signal handler that writes
// the native backtrace to a file, then chains to the previous handler so the
// normal tombstone still happens. MainActivity surfaces the file on next launch.
namespace {
struct BtState { void** cur; void** end; };
_Unwind_Reason_Code btCb(struct _Unwind_Context* ctx, void* arg) {
    BtState* s = static_cast<BtState*>(arg);
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc) {
        if (s->cur == s->end) return _URC_END_OF_STACK;
        *s->cur++ = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}
char gCrashPath[1024] = {0};
char gAltStack[64 * 1024];
struct sigaction gOldSegv{}, gOldAbort{}, gOldBus{}, gOldIll{}, gOldFpe{};
void chainOld(int sig, siginfo_t* info, void* uc) {
    struct sigaction* old = nullptr;
    switch (sig) {
        case SIGSEGV: old = &gOldSegv; break;
        case SIGABRT: old = &gOldAbort; break;
        case SIGBUS:  old = &gOldBus;  break;
        case SIGILL:  old = &gOldIll;  break;
        case SIGFPE:  old = &gOldFpe;  break;
        default: break;
    }
    if (old && (old->sa_flags & SA_SIGINFO) && old->sa_sigaction) {
        old->sa_sigaction(sig, info, uc);
    } else if (old && old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) {
        old->sa_handler(sig);
    } else {
        signal(sig, SIG_DFL);
        raise(sig);
    }
}
// ── async-signal-safe formatting helpers ─────────────────────────────────────
// The handler runs inside SIGSEGV: no stdio, no heap, no locale (fopen/fprintf/strsignal all
// allocate or lock — a crash inside malloc would deadlock the handler). Everything below
// appends into a caller-provided buffer using only stack arithmetic.
size_t appendStr(char* buf, size_t pos, size_t cap, const char* s) {
    while (s && *s && pos < cap - 1) buf[pos++] = *s++;
    return pos;
}
size_t appendDec(char* buf, size_t pos, size_t cap, long v) {
    char tmp[24]; size_t n = 0;
    bool neg = v < 0; unsigned long u = neg ? static_cast<unsigned long>(-v) : static_cast<unsigned long>(v);
    do { tmp[n++] = static_cast<char>('0' + (u % 10)); u /= 10; } while (u && n < sizeof(tmp));
    if (neg && pos < cap - 1) buf[pos++] = '-';
    while (n && pos < cap - 1) buf[pos++] = tmp[--n];
    return pos;
}
size_t appendHex(char* buf, size_t pos, size_t cap, uintptr_t v) {
    pos = appendStr(buf, pos, cap, "0x");
    char tmp[2 * sizeof(uintptr_t)]; size_t n = 0;
    do { unsigned d = v & 0xF; tmp[n++] = static_cast<char>(d < 10 ? '0' + d : 'a' + d - 10); v >>= 4; } while (v && n < sizeof(tmp));
    while (n && pos < cap - 1) buf[pos++] = tmp[--n];
    return pos;
}
const char* sigName(int sig) {
    switch (sig) {
        case SIGSEGV: return "Segmentation fault";
        case SIGABRT: return "Abort";
        case SIGBUS:  return "Bus error";
        case SIGILL:  return "Illegal instruction";
        case SIGFPE:  return "Floating point exception";
        default:      return "Signal";
    }
}
void nativeCrashHandler(int sig, siginfo_t* info, void* uc) {
    void* frames[64];
    BtState st{frames, frames + 64};
    // _Unwind_Backtrace and dladdr are not formally async-signal-safe (loader lock), but they
    // are the entire diagnostic value of this handler and in practice safe unless the crash
    // happened inside the dynamic loader. Everything else below is strictly safe: open/write/
    // close/prctl/read only, one static line buffer, zero allocation, zero stdio.
    _Unwind_Backtrace(btCb, &st);
    size_t n = static_cast<size_t>(st.cur - frames);
    if (gCrashPath[0]) {
        int fd = open(gCrashPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            char line[1024]; size_t p;

            p = 0;
            p = appendStr(line, p, sizeof(line), "NATIVE CRASH sig=");
            p = appendDec(line, p, sizeof(line), sig);
            p = appendStr(line, p, sizeof(line), " (");
            p = appendStr(line, p, sizeof(line), sigName(sig));
            p = appendStr(line, p, sizeof(line), ") addr=");
            p = appendHex(line, p, sizeof(line), info ? reinterpret_cast<uintptr_t>(info->si_addr) : 0);
            p = appendStr(line, p, sizeof(line), "\n");
            write(fd, line, p);

            // Attribute the crash to a process + thread. The hardware-stereo/depth probe runs in the
            // isolated ":probe" process; without this header a probe-process crash is indistinguishable
            // from a main-app crash (both share cacheDir). open/read/prctl are async-signal-safe.
            char pname[256] = {0};
            int cfd = open("/proc/self/cmdline", O_RDONLY);
            if (cfd >= 0) {
                ssize_t r = read(cfd, pname, sizeof(pname) - 1);
                if (r > 0) pname[r] = '\0';
                close(cfd);
            }
            char tname[32] = {0};
            prctl(PR_GET_NAME, tname, 0, 0, 0);
            p = 0;
            p = appendStr(line, p, sizeof(line), "process=");
            p = appendStr(line, p, sizeof(line), pname[0] ? pname : "?");
            p = appendStr(line, p, sizeof(line), " pid=");
            p = appendDec(line, p, sizeof(line), getpid());
            p = appendStr(line, p, sizeof(line), " tid=");
            p = appendDec(line, p, sizeof(line), gettid());
            p = appendStr(line, p, sizeof(line), " thread=");
            p = appendStr(line, p, sizeof(line), tname[0] ? tname : "?");
            p = appendStr(line, p, sizeof(line), "\n");
            write(fd, line, p);

            for (size_t i = 0; i < n; ++i) {
                Dl_info di;
                const char* sym = "?";
                const char* lib = "?";
                uintptr_t off = 0;
                if (dladdr(frames[i], &di)) {
                    if (di.dli_sname) sym = di.dli_sname;
                    if (di.dli_fname) lib = di.dli_fname;
                    if (di.dli_saddr) off = reinterpret_cast<uintptr_t>(frames[i]) -
                                            reinterpret_cast<uintptr_t>(di.dli_saddr);
                }
                p = 0;
                p = appendStr(line, p, sizeof(line), "#");
                if (i < 10) p = appendStr(line, p, sizeof(line), "0");
                p = appendDec(line, p, sizeof(line), static_cast<long>(i));
                p = appendStr(line, p, sizeof(line), " ");
                p = appendHex(line, p, sizeof(line), reinterpret_cast<uintptr_t>(frames[i]));
                p = appendStr(line, p, sizeof(line), " ");
                p = appendStr(line, p, sizeof(line), sym);
                p = appendStr(line, p, sizeof(line), "+");
                p = appendDec(line, p, sizeof(line), static_cast<long>(off));
                p = appendStr(line, p, sizeof(line), " (");
                p = appendStr(line, p, sizeof(line), lib);
                p = appendStr(line, p, sizeof(line), ")\n");
                write(fd, line, p);
            }
            close(fd);
        }
    }
    chainOld(sig, info, uc);
    // If the chained handler returned instead of killing the process, the app would keep running
    // with a dead internal thread — e.g. a render thread gone while the app keeps
    // running. A half-alive process is strictly worse than a dead one;
    // guarantee the default disposition.
    signal(sig, SIG_DFL);
    raise(sig);
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject, jstring jpath) {
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    if (p) {
        strncpy(gCrashPath, p, sizeof(gCrashPath) - 1);
        env->ReleaseStringUTFChars(jpath, p);
    }
    stack_t ss;
    ss.ss_sp = gAltStack;
    ss.ss_size = sizeof(gAltStack);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = nativeCrashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &gOldSegv);
    sigaction(SIGABRT, &sa, &gOldAbort);
    sigaction(SIGBUS,  &sa, &gOldBus);
    sigaction(SIGILL,  &sa, &gOldIll);
    sigaction(SIGFPE,  &sa, &gOldFpe);
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

// Constructs the Liquify warper. Idempotent. Called on the Liquify GL thread (LiquifyGlContext).
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiquifyEngine_nativeInitImageWarper(JNIEnv* env, jobject thiz) {
    JNI_TRY
    std::unique_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gImageWarper) {
        gImageWarper = new ImageWarper();
    }
    JNI_CATCH()
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiquifyEngine_nativeInitGl(JNIEnv* env, jobject thiz) {
    JNI_TRY
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gImageWarper) gImageWarper->init();
    JNI_CATCH()
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiquifyEngine_nativeDestroy(JNIEnv* env, jobject thiz) {
    JNI_TRY
    std::unique_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gImageWarper) { delete gImageWarper; gImageWarper = nullptr; }
    JNI_CATCH()
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiquifyEngine_nativePrepareLiquify(JNIEnv* env, jobject, jobject bitmap) {
    JNI_TRY
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex); // keeps gImageWarper alive for the duration of this call
    if (!gImageWarper) return;
    AndroidBitmapInfo info;
    void* pixels = 0;
    // A recycled/immutable/hardware-backed bitmap makes getInfo/lockPixels fail; using `info` or
    // `pixels` anyway (uninitialized width/height, null pixels) is a guaranteed SIGSEGV/glitch —
    // mirrors the checks bitmapToMat already does above.
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    // setSourceImage takes no stride parameter, so it can only handle a tightly-packed RGBA_8888
    // buffer -- same requirement (and same checks) as the GPU stamp-engine upload/readback path
    // below. A padded-stride or non-RGBA bitmap reaching here would otherwise read rows shifted
    // from where they actually are, or read past the buffer entirely.
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    if (info.stride != info.width * 4) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return;
    gImageWarper->setSourceImage(static_cast<uint8_t*>(pixels), info.width, info.height);
    AndroidBitmap_unlockPixels(env, bitmap);
    JNI_CATCH()
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiquifyEngine_nativeApplyLiquify(JNIEnv* env, jobject, jfloatArray strokeArr, jfloat brushSize, jfloat intensity) {
    JNI_TRY
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex); // keeps gImageWarper alive for the duration of this call
    if (!gImageWarper) return;
    jsize len = env->GetArrayLength(strokeArr);
    if (len < 2) return;
    jfloat* ptr = env->GetFloatArrayElements(strokeArr, nullptr);
    if (!ptr) return;
    std::vector<glm::vec2> stroke;
    // len & ~1: an odd length would otherwise read ptr[len], one element past the JNI-owned buffer.
    jsize pairCount = len & ~1;
    for (jsize i = 0; i < pairCount; i += 2) {
        stroke.push_back({ptr[i], ptr[i+1]});
    }
    gImageWarper->applyLiquify(stroke, brushSize, intensity);
    env->ReleaseFloatArrayElements(strokeArr, ptr, JNI_ABORT);
    JNI_CATCH()
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_LiquifyEngine_nativeBakeLiquify(JNIEnv* env, jobject, jobject outBitmap) {
    JNI_TRY
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex); // keeps gImageWarper alive for the duration of this call
    if (!gImageWarper) return;
    AndroidBitmapInfo info;
    void* pixels = 0;
    if (AndroidBitmap_getInfo(env, outBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    // Same tight-stride/RGBA_8888 requirement as nativePrepareLiquify -- bakeToBitmap's glReadPixels
    // also assumes a tightly-packed buffer.
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    if (info.stride != info.width * 4) return;
    if (AndroidBitmap_lockPixels(env, outBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return;
    if (!gImageWarper->bakeToBitmap(static_cast<uint8_t*>(pixels), (int)info.width, (int)info.height)) {
        LOGE("nativeBakeLiquify: bakeToBitmap declined (buffer/source size mismatch or no source set)");
    }
    AndroidBitmap_unlockPixels(env, outBitmap);
    JNI_CATCH()
}

} // extern "C"

// ---------------------------------------------------------------------------------------------
// GPU stamp engine bridge (docs/Native Rendering Engine Design.md §2/§9). Drives a
// graffux::StampEngine -- the WgpuStampEngine adapter over core/wgpu-engine, the only GPU backend
// (the Vulkan and GLES engines were retired).
//
// Independent lifecycle from gImageWarper above -- the engine owns
// its own headless device -- created/destroyed per drawing session by the Kotlin-side
// GpuStampEngine wrapper.
//
// Per-instance, NOT a single global: nativeInit allocates its own graffux::StampEngine and
// returns it to Kotlin as an opaque jlong handle, which every other nativeXxx call takes and
// nativeDestroy frees. A single shared engine (the original design) meant two GpuStampEngine
// Kotlin objects silently fought over one native instance — initializing the second destroyed the
// first's layer out from under it, and destroying either invalidated the other while its Kotlin
// `initialized` flag stayed true. The Kotlin/C++ classes both already document themselves as not
// thread-safe (the caller owns serializing its own use of one instance), so no mutex is needed
// here either — that contract, not a lock, is what makes per-instance handles safe.
extern "C" {

// 0 = the engine could not start (no wgpu library, no adapter): nothing draws (no CPU renderer).
JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeInit(
    JNIEnv*, jobject, jint width, jint height) {
    JNI_TRY
    auto* engine = graffux::createStampEngine();
    if (!engine->init(width, height)) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
    JNI_CATCH(jlong())
}

// Seeds the layer with `inBitmap`'s current pixels — the reverse of nativeReadbackRect below.
// Requires an ARGB_8888, non-hardware bitmap sized exactly width x height with a tight stride.
// Android's ARGB_8888 native byte layout is R,G,B,A per pixel, bit-for-bit the engine's rgba8
// layer, so no channel reordering is needed.
JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeUpload(
    JNIEnv* env, jobject, jlong handle, jobject inBitmap) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;

    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, inBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (info.stride != info.width * 4) return JNI_FALSE;
    // The bitmap must match the layer's own dimensions exactly, not just have enough bytes — a
    // capacity-only check would silently accept e.g. a 200x100 bitmap against a 100x100 layer and
    // upload it row-major-contiguous, landing every row after the first at the wrong offset.
    if (static_cast<int32_t>(info.width) != engine->width() ||
        static_cast<int32_t>(info.height) != engine->height()) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, inBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return JNI_FALSE;
    }
    size_t capacity = static_cast<size_t>(info.stride) * info.height;
    bool ok = engine->upload(static_cast<const uint8_t*>(pixels), capacity);
    AndroidBitmap_unlockPixels(env, inBitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

// `dabData` is a flat float array, 5 floats per dab (x, y, radius, alpha, angleDeg) — the same
// fields BrushStamps.Dab exposes on the Kotlin side, packed by the GpuStampEngine.kt wrapper
// so no per-dab JNI round trip is needed for a whole stroke's dab list.
JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeStampDabs(
    JNIEnv* env, jobject, jlong handle, jfloatArray dabData, jint colorArgb, jfloat hardness) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;

    if (!dabData) return JNI_FALSE;
    jsize len = env->GetArrayLength(dabData);
    if (len <= 0 || len % 5 != 0) return JNI_FALSE;
    jsize dabCount = len / 5;
    jfloat* ptr = env->GetFloatArrayElements(dabData, nullptr);
    if (!ptr) return JNI_FALSE;

    std::vector<graffux::GpuDab> dabs;
    dabs.reserve(static_cast<size_t>(dabCount));
    for (jsize i = 0; i < dabCount; i++) {
        const jfloat* d = ptr + i * 5;
        dabs.push_back(graffux::GpuDab{d[0], d[1], d[2], d[3], d[4], 0.0f, 0.0f, 0.0f});
    }
    env->ReleaseFloatArrayElements(dabData, ptr, JNI_ABORT);

    bool ok = engine->stampDabs(dabs, static_cast<uint32_t>(colorArgb), hardness);
    return ok ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

// ---- Resident layers and rectangle readback (StampEngine's optional methods; wgpu only) ------
// Same bitmap requirements as nativeUpload above. Locks `bitmap` and returns its
// pixels, or nullptr (nothing locked) when it does not match the engine's layer exactly.
static uint8_t* gfxLockLayerBitmap(JNIEnv* env, graffux::StampEngine* engine, jobject bitmap,
                                   size_t* capacity) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.stride != info.width * 4) return nullptr;
    if (static_cast<int32_t>(info.width) != engine->width() ||
        static_cast<int32_t>(info.height) != engine->height()) {
        return nullptr;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return nullptr;
    }
    *capacity = static_cast<size_t>(info.stride) * info.height;
    return static_cast<uint8_t*>(pixels);
}

// A caller rectangle must lie inside the layer before it is used to index the locked bitmap
// (64-bit math: x + w must not wrap).
static bool gfxRectInside(const graffux::StampEngine* engine, const int32_t r[4]) {
    if (r[0] < 0 || r[1] < 0 || r[2] <= 0 || r[3] <= 0) return false;
    return (int64_t)r[0] + r[2] <= engine->width() && (int64_t)r[1] + r[3] <= engine->height();
}

// ---- Live impasto display (StampEngine's optional methods; wgpu only) ----------------------

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuImpastoDisplay_nativeShade(
        JNIEnv*, jclass, jlong handle, jint x, jint y, jint w, jint h, jfloat azimuthDeg, jfloat elevationDeg,
        jfloat strength) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    return engine && engine->isInitialized() &&
                   engine->shadeImpastoReliefToDisplay(x, y, w, h, azimuthDeg, elevationDeg, strength)
               ? JNI_TRUE
               : JNI_FALSE;
    JNI_CATCH(jboolean())
}

// Reads `rect` of the display buffer into the same rectangle of the full-layer `bitmap`.
JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuImpastoDisplay_nativeRead(
        JNIEnv* env, jclass, jlong handle, jobject bitmap, jintArray rect) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized() || rect == nullptr || env->GetArrayLength(rect) < 4) return JNI_FALSE;
    int32_t r[4];
    env->GetIntArrayRegion(rect, 0, 4, reinterpret_cast<jint*>(r));
    if (!gfxRectInside(engine, r)) return JNI_FALSE;
    std::vector<uint8_t> block(static_cast<size_t>(r[2]) * r[3] * 4);
    if (!engine->readImpastoDisplay(r[0], r[1], r[2], r[3], block.data(), block.size())) return JNI_FALSE;
    size_t capacity = 0;
    uint8_t* pixels = gfxLockLayerBitmap(env, engine, bitmap, &capacity);
    if (!pixels) return JNI_FALSE;
    const size_t stride = static_cast<size_t>(engine->width()) * 4;
    for (jint row = 0; row < r[3]; row++) {
        memcpy(pixels + static_cast<size_t>(r[1] + row) * stride + static_cast<size_t>(r[0]) * 4,
               block.data() + static_cast<size_t>(row) * r[2] * 4, static_cast<size_t>(r[2]) * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
    JNI_CATCH(jboolean())
}

// Undo snapshots (StampEngine::docSnapshot); `args` is {a, b, c}. `rect` (int[4]) is read for op 0 and receives the
// swapped rectangle for op 1; it may be null for op 2.
JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuUndoSnapshots_nativeDocSnapshot(
        JNIEnv* env, jobject, jlong handle, jint op, jlongArray args, jintArray rect) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized() || args == nullptr || env->GetArrayLength(args) < 3) return 0;
    jlong a[3];
    env->GetLongArrayRegion(args, 0, 3, a);
    int32_t r[4] = {0, 0, 0, 0};
    const bool hasRect = rect != nullptr && env->GetArrayLength(rect) >= 4;
    if (op != 2 && !hasRect) return 0;
    if (hasRect) env->GetIntArrayRegion(rect, 0, 4, r);
    const uint64_t out = engine->docSnapshot(
        op, static_cast<uint64_t>(a[0]), static_cast<uint64_t>(a[1]), static_cast<uint64_t>(a[2]), r);
    if (op == 1 && out != 0) env->SetIntArrayRegion(rect, 0, 4, r);
    return static_cast<jlong>(out);
    JNI_CATCH(jlong())
}

// `rect` (int[4], may be null) receives the {x, y, w, h} actually copied.
JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeReadbackRect(JNIEnv* env, jobject, jlong handle, jobject outBitmap, jintArray rect) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;
    size_t capacity = 0;
    uint8_t* pixels = gfxLockLayerBitmap(env, engine, outBitmap, &capacity);
    if (!pixels) return JNI_FALSE;
    int32_t r[4] = {0, 0, 0, 0};
    const bool ok = engine->readbackRect(pixels, capacity, r);
    AndroidBitmap_unlockPixels(env, outBitmap);
    if (ok && rect != nullptr && env->GetArrayLength(rect) >= 4) {
        const jint values[4] = {r[0], r[1], r[2], r[3]};
        env->SetIntArrayRegion(rect, 0, 4, values);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeSupportsResidentLayers(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    return engine && engine->isInitialized() && engine->supportsResidentLayers() ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeBindLayer(JNIEnv*, jobject, jlong handle, jlong key, jlong generation) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return 0;
    return static_cast<jlong>(engine->bindLayer(static_cast<uint64_t>(key), static_cast<uint64_t>(generation)));
    JNI_CATCH(jlong())
}

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeUploadLayer(JNIEnv* env, jobject, jlong handle, jlong key, jlong generation, jobject inBitmap) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized() || !engine->supportsResidentLayers()) return 0;
    size_t capacity = 0;
    uint8_t* pixels = gfxLockLayerBitmap(env, engine, inBitmap, &capacity);
    if (!pixels) return 0;
    const uint64_t session = engine->uploadLayer(static_cast<uint64_t>(key),
                                                 static_cast<uint64_t>(generation), pixels, capacity);
    AndroidBitmap_unlockPixels(env, inBitmap);
    return static_cast<jlong>(session);
    JNI_CATCH(jlong())
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeCommitLayer(JNIEnv*, jobject, jlong handle, jlong key, jlong session, jlong generation) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;
    return engine->commitLayer(static_cast<uint64_t>(key), static_cast<uint64_t>(session),
                               static_cast<uint64_t>(generation)) ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeRefreshLayer(JNIEnv* env, jobject, jlong handle, jlongArray ids,
                      jobject bitmap, jintArray rect) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized() || !engine->supportsResidentLayers()) return JNI_FALSE;
    if (ids == nullptr || rect == nullptr || env->GetArrayLength(ids) < 3 || env->GetArrayLength(rect) < 4) {
        return JNI_FALSE;
    }
    jlong id[3];
    jint r[4];
    env->GetLongArrayRegion(ids, 0, 3, id);
    env->GetIntArrayRegion(rect, 0, 4, r);
    if (!gfxRectInside(engine, reinterpret_cast<const int32_t*>(r))) return JNI_FALSE;
    size_t capacity = 0;
    uint8_t* pixels = gfxLockLayerBitmap(env, engine, bitmap, &capacity);
    if (!pixels) return JNI_FALSE;
    // ids = {key, session, generation}, rect = {x, y, w, h}.
    const bool ok = engine->refreshLayer(static_cast<uint64_t>(id[0]), static_cast<uint64_t>(id[1]),
                                         static_cast<uint64_t>(id[2]), pixels, capacity, r[0], r[1], r[2], r[3]);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

// Names the next nativeStampMaskedDabs call's images (StampEngine::setMaskedContentIds).
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeSetMaskedContentIds(
        JNIEnv*, jobject, jlong handle, jlong mask, jlong grain, jlong secondary) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (engine && engine->isInitialized()) {
        engine->setMaskedContentIds(static_cast<uint64_t>(mask), static_cast<uint64_t>(grain),
                                    static_cast<uint64_t>(secondary));
    }
    JNI_CATCH()
}

// Turns on GPU undo shadows for the document engine (StampEngine::undoOpen).
JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeUndoOpen(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;
    return engine->undoOpen() ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeInvalidateLayer(JNIEnv*, jobject, jlong handle, jlong key) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;
    return engine->invalidateLayer(static_cast<uint64_t>(key)) ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeInvalidateAllLayers(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (engine && engine->isInitialized()) engine->invalidateAllLayers();
    JNI_CATCH()
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeSetResidentBudget(JNIEnv*, jobject, jlong handle, jlong bytes) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (engine && engine->isInitialized() && bytes >= 0) engine->setResidentBudget(static_cast<uint64_t>(bytes));
    JNI_CATCH()
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDeviceLost(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    return engine && engine->deviceLost() ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

// ---- Multipass rendering (wgpu only, experimental; StampEngine.h defaults to unsupported) ----

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeSetMultipass(JNIEnv* env, jobject, jlong handle, jfloatArray params) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;
    std::vector<float> values;
    if (params != nullptr) {
        const jsize n = env->GetArrayLength(params);
        values.resize(static_cast<size_t>(n));
        if (n > 0) env->GetFloatArrayRegion(params, 0, n, values.data());
    }
    return engine->setMultipass(values.data(), values.size()) ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeRefine(JNIEnv*, jobject, jlong handle, jfloat budgetMs) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return 0;
    return engine->refine(budgetMs);
    JNI_CATCH(jint())
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeFlushMultipass(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_TRUE;
    return engine->flushMultipass() ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT jdoubleArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeMultipassStats(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return nullptr;
    double stats[16] = {0};
    const size_t n = engine->multipassStats(stats, 16);
    if (n == 0) return nullptr;
    jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(n));
    if (out != nullptr) env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(n), stats);
    return out;
    JNI_CATCH(jdoubleArray())
}

// ---- Direct display (wgpu only; StampEngine.h defaults to unsupported) -----------------------

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDirectCapabilities(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return 0;
    return static_cast<jint>(engine->directCapabilities());
    JNI_CATCH(jint())
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDirectAttach(
    JNIEnv* env, jobject, jlong handle, jobject surface, jint width, jint height) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized() || !surface || width <= 0 || height <= 0) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    const bool ok = engine->directAttach(window, width, height);
    ANativeWindow_release(window);  // directAttach() holds its own reference
    return ok ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDirectDetach(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (engine) engine->directDetach();
    JNI_CATCH()
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDirectBeginStroke(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    return engine && engine->isInitialized() && engine->directBeginStroke() ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDirectPresent(
    JNIEnv* env, jobject, jlong handle, jfloatArray matrix, jboolean newBatch) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return JNI_FALSE;
    float m[6];
    const float* mp = nullptr;
    if (matrix != nullptr) {
        if (env->GetArrayLength(matrix) < 6) return JNI_FALSE;
        env->GetFloatArrayRegion(matrix, 0, 6, m);
        mp = m;
    }
    return engine->directPresent(mp, newBatch == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDirectEndStroke(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    return engine && engine->isInitialized() && engine->directEndStroke() ? JNI_TRUE : JNI_FALSE;
    JNI_CATCH(jboolean())
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (engine) {
        engine->destroy();
        delete engine;
    }
    JNI_CATCH()
}

// ---- Per-device tuning and telemetry (GpuTuning.kt / GpuTelemetry.kt) ----------------------

// Process-wide: applies to engines created after the call.
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeSetStampTuning(JNIEnv*, jobject, jint stampTile, jboolean timestamps) {
    JNI_TRY
    graffux::stampTuning().stampTile.store(stampTile == 8 ? 8 : 16);
    graffux::stampTuning().timestamps.store(timestamps == JNI_TRUE);
    JNI_CATCH()
}

JNIEXPORT jstring JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeGpuInfo(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return nullptr;
    const std::string info = engine->gpuInfo();
    return info.empty() ? nullptr : env->NewStringUTF(info.c_str());
    JNI_CATCH(jstring())
}

// {kind, nanos} pairs flattened; null when the engine has no GPU timings to give.
JNIEXPORT jlongArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_GpuStampEngine_nativeTakePassTimings(JNIEnv* env, jobject, jlong handle) {
    JNI_TRY
    auto* engine = reinterpret_cast<graffux::StampEngine*>(handle);
    if (!engine || !engine->isInitialized()) return nullptr;
    constexpr size_t kCapacityPairs = 1024;
    std::vector<uint64_t> buffer(kCapacityPairs * 2);
    const size_t n = engine->takePassTimings(buffer.data(), kCapacityPairs);
    if (n == 0) return nullptr;
    jlongArray out = env->NewLongArray(static_cast<jsize>(n * 2));
    if (out == nullptr) return nullptr;
    env->SetLongArrayRegion(out, 0, static_cast<jsize>(n * 2), reinterpret_cast<const jlong*>(buffer.data()));
    return out;
    JNI_CATCH(jlongArray())
}

} // extern "C"
