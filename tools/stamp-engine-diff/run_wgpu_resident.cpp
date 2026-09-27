// Resident-layer parity through the C++ WgpuStampEngine adapter Android uses: the same multi-stroke
// sequences, with undos in between, painted once the pre-residency way (upload() the whole layer
// before every stroke) and once through bindLayer/uploadLayer + refreshLayer (the CPU commit is
// authoritative, as on Android). Every stroke's read-back result, and the resident copy after every
// commit, must be byte-identical. Mixes round (strokeMax, frame batches), masked and smudge strokes.
// Exit status 0 = identical.
#include "include/WgpuStampEngine.h"

#include <cstdio>
#include <cstring>
#include <random>
#include <string>
#include <vector>

using namespace graffux;

namespace {
constexpr int W = 211, H = 157;
constexpr uint64_t KEY = 42;

std::mt19937 rng(77);
float U() { return std::uniform_real_distribution<float>(0.f, 1.f)(rng); }

std::vector<uint8_t> seedLayer() {
    std::vector<uint8_t> v(W * H * 4);
    for (int i = 0; i < W * H; ++i) {
        const uint8_t a = static_cast<uint8_t>(rng() % 256);
        for (int c = 0; c < 3; ++c) v[i * 4 + c] = static_cast<uint8_t>((rng() % 256) * a / 255);
        v[i * 4 + 3] = a;
    }
    return v;
}

enum class Kind { Round, Masked, Smudge };
struct Stroke {
    Kind kind;
    std::vector<GpuDab> dabs;
    std::vector<ColorSmudgeDab> smudge;
};

Stroke makeStroke(Kind kind, float x0, float x1) {
    Stroke s{kind, {}, {}};
    for (int i = 0; i < 28; ++i) {
        GpuDab d{x0 + U() * (x1 - x0), U() * H, 2.f + U() * 12.f, 0.3f + U() * 0.7f, U() * 360.f};
        d.colorR = U(); d.colorG = U(); d.colorB = U(); d.colorA = 0.4f + U() * 0.6f;
        d.flow = 0.3f + U(); d.resolved = 1.f; d.tipRatio = kind == Kind::Masked ? 0.4f + U() * 0.6f : U();
        s.dabs.push_back(d);
        s.smudge.push_back(ColorSmudgeDab{x0 + (x1 - x0) * i / 28.f, 40.f + 30.f * U(), 0.7f, 0.2f, 1.f, 7.f,
                                          1.f, 3.f, 0.f, 0.f, 0.f});
    }
    return s;
}

std::vector<uint8_t> mask(int n) {
    std::vector<uint8_t> m(n * n);
    for (int y = 0; y < n; ++y)
        for (int x = 0; x < n; ++x) m[y * n + x] = static_cast<uint8_t>((x * 7 + y * 13) % 256);
    return m;
}

// Paints `s` in frame batches, reading back into `buf` after each, as the live stroke does.
bool paint(StampEngine& e, const Stroke& s, std::vector<uint8_t>& buf) {
    static const std::vector<uint8_t> tip = mask(32);
    if (s.kind == Kind::Smudge) {
        return e.colorSmudge(s.smudge, 0, 7.f, 0.5f, true, 0xFF804020u) &&
               e.readback(buf.data(), buf.size());
    }
    for (size_t at = 0; at < s.dabs.size(); at += 6) {
        std::vector<GpuDab> part(s.dabs.begin() + at, s.dabs.begin() + std::min(at + 6, s.dabs.size()));
        const bool ok = s.kind == Kind::Round
            ? e.stampDabs(part, 0xFF3070C0u, 0.45f, false, {}, true)
            : e.stampMaskedDabs(part, 0xFF3070C0u, 0.6f, tip.data(), 32, 32);
        int32_t rect[4];
        if (!ok || !e.readbackRect(buf.data(), buf.size(), rect)) return false;
    }
    return true;
}

// Bounding box of bytes that differ, {x, y, w, h}.
void changeRect(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b, int r[4]) {
    int x0 = W, y0 = H, x1 = -1, y1 = -1;
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x)
            if (std::memcmp(&a[(y * W + x) * 4], &b[(y * W + x) * 4], 4) != 0) {
                x0 = std::min(x0, x); y0 = std::min(y0, y); x1 = std::max(x1, x); y1 = std::max(y1, y);
            }
    if (x1 < 0) { r[0] = r[1] = r[2] = r[3] = 0; return; }
    r[0] = x0; r[1] = y0; r[2] = x1 - x0 + 1; r[3] = y1 - y0 + 1;
}

size_t differing(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
    size_t n = 0;
    for (size_t i = 0; i < a.size(); ++i) n += a[i] != b[i];
    return n;
}
}  // namespace

int main() {
    WgpuStampEngine oldE, newE;
    if (!oldE.init(W, H) || !newE.init(W, H)) { std::fprintf(stderr, "wgpu init failed\n"); return 2; }
    if (!newE.supportsResidentLayers()) { std::fprintf(stderr, "library has no resident layers\n"); return 2; }
    std::vector<uint8_t> truth = seedLayer();
    std::vector<std::vector<uint8_t>> history;
    uint64_t nextGen = 1, gen = nextGen++;
    int uploads = 0, failures = 0, strokes = 0;
    const char* plan[] = {"round", "masked", "undo", "round", "smudge", "undo", "undo", "masked", "round"};
    float band = 0.f;
    for (const char* step : plan) {
        if (std::strcmp(step, "undo") == 0) {
            truth = history.back();
            history.pop_back();
            gen = nextGen++;  // the CPU layer changed outside the engine
            std::printf("  undo\n");
            continue;
        }
        const Kind kind = std::strcmp(step, "round") == 0 ? Kind::Round
                        : std::strcmp(step, "masked") == 0 ? Kind::Masked : Kind::Smudge;
        const Stroke s = makeStroke(kind, band, band + 80.f);
        band = band > 110.f ? 0.f : band + 35.f;
        ++strokes;
        // Old path.
        std::vector<uint8_t> want = truth;
        oldE.upload(truth.data(), truth.size());
        if (!paint(oldE, s, want)) { std::fprintf(stderr, "old path failed\n"); return 2; }
        // Resident path.
        uint64_t session = newE.bindLayer(KEY, gen);
        const bool bound = session != 0;
        if (session == 0) {
            ++uploads;
            session = newE.uploadLayer(KEY, gen, truth.data(), truth.size());
        }
        std::vector<uint8_t> got = truth;
        if (session == 0 || !paint(newE, s, got)) { std::fprintf(stderr, "resident path failed\n"); return 2; }
        const size_t n = differing(got, want);
        // The CPU commit: here the GPU preview plus a one-level nudge in a small patch, standing in
        // for CPU/GPU rounding differences, so refreshLayer has real work to do.
        std::vector<uint8_t> committed = got;
        for (int y = 60; y < 64; ++y)
            for (int x = 5; x < 9; ++x) committed[(y * W + x) * 4 + 3] ^= 1;
        int r[4];
        changeRect(truth, committed, r);
        history.push_back(truth);
        truth = committed;
        gen = nextGen++;
        const bool refreshed = newE.refreshLayer(KEY, session, gen, truth.data(), truth.size(), r[0], r[1], r[2], r[3]);
        // The refreshed copy is checked by the next stroke: it binds without an upload and must
        // still match the full-upload path byte for byte.
        const bool ok = n == 0 && refreshed;
        failures += !ok;
        std::printf("  %-7s stroke: %s, %zu bytes differ from full upload, refresh %s\n", step,
                    bound ? "resident hit" : "uploaded", n, refreshed ? "ok" : "FAILED");
    }
    // Uploads happen exactly for the first stroke and the first stroke after each run of undos.
    const int expectedUploads = 3;
    if (uploads != expectedUploads) ++failures;
    std::printf("%d strokes, %d uploads (expected %d; %d avoided), %s\n", strokes, uploads,
                expectedUploads, strokes - uploads, failures == 0 ? "all byte-identical" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
