#pragma once
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <random>
#include <string>
#include <vector>
#include <cmath>
using namespace graffux;

// -DMULTIPASS (wgpu only): the same scenarios with multipass rendering on. Drafts render at once and
// the layer is refined in small budgets; MP_SETTLE lands everything before a result is read, and the
// result must be byte-identical to the plain wgpu run (run.sh compares them).
#ifdef MULTIPASS
#define MP_SETTLE() do { e.refine(0.02f); e.flushMultipass(); } while (0)
#define MP_STEP() do { e.refine(0.01f); } while (0)
#else
#define MP_SETTLE() do {} while (0)
#define MP_STEP() do {} while (0)
#endif

static void dump(const char* name, const std::vector<uint8_t>& px) {
    std::string path = std::string(OUTDIR) + "/" + name + ".raw";
    FILE* f = fopen(path.c_str(), "wb"); fwrite(px.data(), 1, px.size(), f); fclose(f);
}

template <class E>
int runAll() {
    const int W = 197, H = 143;
    std::mt19937 rng(1234);
    std::uniform_real_distribution<float> U(0.f, 1.f);
    std::vector<uint8_t> seed(W * H * 4);
    for (int i = 0; i < W * H; ++i) {  // premultiplied random-ish background
        uint8_t a = (i % 7 == 0) ? 0 : (uint8_t)(rng() % 256);
        for (int c = 0; c < 3; ++c) seed[i*4+c] = (uint8_t)((rng() % 256) * a / 255);
        seed[i*4+3] = a;
    }
    auto mkDabs = [&](int n, bool resolved) {
        std::vector<GpuDab> v;
        for (int i = 0; i < n; ++i) {
            GpuDab d{U(rng)*W, U(rng)*H, 1.f + U(rng)*25.f, 0.2f + U(rng)*0.8f, U(rng)*360.f};
            if (resolved || i % 3 == 0) {
                d.colorR = U(rng); d.colorG = U(rng); d.colorB = U(rng); d.colorA = 0.3f + U(rng)*0.7f;
                d.flow = 0.2f + U(rng); d.resolved = 1.f; d.tipRatio = U(rng);
            } else d.tipRatio = 0.3f + U(rng) * 0.7f;
            d.contactDepth = U(rng); d.reservoirLoad = 0.5f + U(rng)*0.5f; d.depositionRate = 0.5f + U(rng)*0.5f; d.substrateResponse = U(rng);
            v.push_back(d);
        }
        return v;
    };
    std::vector<uint8_t> out(W * H * 4);
    E e;
    if (!e.init(W, H)) { fprintf(stderr, "init failed\n"); return 1; }
#ifdef MULTIPASS
    {
        // {enabled, passes, edge_fraction, transition_ms, overtake_ms, draft_scale, ballast, frame_ms}
        const float mp[] = {1.f, 2.f, 0.4f, 150.f, 50.f, 2.f, 1.f, 0.f};
        if (!e.setMultipass(mp, 8)) { fprintf(stderr, "setMultipass failed\n"); return 1; }
    }
#endif
    auto rb = [&](const char* name) { std::fill(out.begin(), out.end(), 0); e.upload(out.data(), out.size()); };
    (void)rb;
    auto fresh = [&]() { e.upload(seed.data(), seed.size()); };
    auto readAll = [&](const char* name) {
        std::vector<uint8_t> buf(W * H * 4, 0x5A);
        MP_SETTLE();
        // full dirty after upload: readback copies everything touched since last readback
        if (!e.readback(buf.data(), buf.size())) { fprintf(stderr, "readback failed %s\n", name); }
        dump(name, buf);
    };
    // 0: upload/readback roundtrip
    fresh(); readAll("s0_upload");
    // 1: max mode
    fresh(); e.stampDabs(mkDabs(60, false), 0xC0FF8040u, 0.35f); readAll("s1_max");
    // 2: buildUp
    fresh(); e.stampDabs(mkDabs(60, false), 0x80336699u, 0.7f, true); readAll("s2_buildup");
    // 3: strokeMax across batches
    fresh(); { auto all = mkDabs(90, false); for (int b = 0; b < 3; ++b) { std::vector<GpuDab> part(all.begin()+b*30, all.begin()+(b+1)*30); e.stampDabs(part, 0xFF20A0E0u, 0.2f, false, {}, true); MP_STEP(); } } readAll("s3_strokemax");
    // 3b: strokeMax continues after partial readbacks (dirty-rect behaviour with a reused buffer)
    fresh(); { std::vector<uint8_t> buf(W*H*4, 0x11); e.readback(buf.data(), buf.size()); auto all = mkDabs(40, true); for (int b = 0; b < 4; ++b) { std::vector<GpuDab> part(all.begin()+b*10, all.begin()+(b+1)*10); e.stampDabs(part, 0xFF102030u, 0.5f, false, {}, true); e.readback(buf.data(), buf.size()); MP_STEP(); } MP_SETTLE(); e.readback(buf.data(), buf.size()); dump("s3b_partial", buf); }
    // 4: substrate + paint height
    { std::vector<uint8_t> tooth(29*31); for (auto& t : tooth) t = rng() % 256; std::vector<float> ph(W*H); for (auto& p : ph) p = U(rng)*0.6f;
      e.uploadSubstrateHeight(tooth.data(), 29, 31);
#ifdef WITH_PAINT_HEIGHT
      e.uploadPaintHeight(ph.data(), W, H);
#endif
      SubstrateStampParams sp; sp.enabled = true;
#ifdef WITH_PAINT_HEIGHT
      sp.hasPaintHeight = true;
#endif
      sp.baseHeight = 0.2f; sp.heightScale = 0.7f; sp.textureScale = 1.7f; sp.textureOffsetX = -13.3f; sp.textureOffsetY = 5.2f;
      fresh(); e.stampDabs(mkDabs(50, true), 0xFFFFFFFFu, 0.4f, false, sp); readAll("s4_substrate");
      fresh(); { auto m = mkDabs(40, false); std::vector<uint8_t> mask(24*24); for (int y=0;y<24;y++) for(int x=0;x<24;x++){ float dx=x-11.5f, dy=y-11.5f; mask[y*24+x]=(uint8_t)std::max(0.f, 255.f*(1.f-std::sqrt(dx*dx+dy*dy)/12.f)); }
        e.stampMaskedDabs(m, 0xE0804020u, 0.5f, mask.data(), 24, 24, nullptr, 0, 0, false, 1.f, 0.f, 0.f, {}, nullptr, 0, 0, sp); readAll("s4b_masked_substrate"); } }
    // 5: masked + grain + secondary
    { std::vector<uint8_t> mask(40*20); for (auto& m : mask) m = rng() % 256; std::vector<uint8_t> grain(17*13); for (auto& g : grain) g = rng() % 256;
      std::vector<uint8_t> smask(16*16); for (auto& m : smask) m = rng()%256;
      auto d = mkDabs(45, false); std::vector<GpuSecondaryDab> sd; for (auto& p : d) sd.push_back(GpuSecondaryDab{p.x+U(rng)*6, p.y-U(rng)*6, p.radius*0.8f, 0.5f+U(rng)*0.5f, U(rng), U(rng)*360, 0.5f+U(rng), (float)(rng()%2)});
      fresh(); e.stampMaskedDabs(d, 0xFF4488CCu, 0.f, mask.data(), 40, 20, grain.data(), 17, 13, true, 1.3f, 2.5f, -1.5f, sd, smask.data(), 16, 16); readAll("s5_masked_all");
      fresh(); e.stampMaskedDabs(d, 0x90CC8844u, 0.f, mask.data(), 40, 20, grain.data(), 17, 13, false, 0.7f, 0.f, 3.f); readAll("s5b_masked_moving"); }
    // 6: color smudge variants
    for (int mode = 0; mode < 4; ++mode) for (int pickup = 0; pickup < 2; ++pickup) for (int merged = 0; merged < 2; ++merged) {
        std::vector<ColorSmudgeDab> sd; float x = 30, y = 40;
        for (int i = 0; i < 14; ++i) { x += 7 + U(rng)*3; y += U(rng)*6 - 2; ColorSmudgeDab c{x, y, 0.3f+U(rng)*0.6f, U(rng)*0.5f, 0.5f+U(rng)*0.5f, 0.5f+U(rng)*0.5f}; c.colorRateMultiplier = 0.5f+U(rng); c.distanceDeltaPx = 7.f; c.baseColorRate = 0.3f; c.chargeDecayRate = 0.02f; c.pickupRate = pickup ? 0.4f : 0.f; sd.push_back(c); }
        std::vector<uint8_t> merged8(W*H*4); for (auto& b : merged8) b = rng()%256;
        fresh(); e.colorSmudge(sd, mode, 9.f, 0.4f, mode % 2 == 0, 0xCC3399FFu, 0.25f, merged ? merged8.data() : nullptr, merged ? W : 0, merged ? H : 0);
        char name[64]; snprintf(name, sizeof name, "s6_smudge_m%d_p%d_s%d", mode, pickup, merged); readAll(name);
    }
    // 7: clear
    fresh(); e.clear(); readAll("s7_clear");
#ifdef WITH_PAINT_HEIGHT
    { std::vector<uint8_t> tooth(29*31); for (auto& t : tooth) t = rng() % 256; std::vector<float> ph(W*H); for (auto& p : ph) p = U(rng)*0.6f;
      dump("ph_tooth", tooth); std::vector<uint8_t> phb((uint8_t*)ph.data(), (uint8_t*)ph.data()+ph.size()*4); dump("ph_height", phb);
      e.uploadSubstrateHeight(tooth.data(), 29, 31); e.uploadPaintHeight(ph.data(), W, H);
      SubstrateStampParams sp; sp.enabled = true; sp.hasPaintHeight = true; sp.baseHeight = 0.2f; sp.heightScale = 0.7f; sp.textureScale = 1.7f; sp.textureOffsetX = -13.3f; sp.textureOffsetY = 5.2f;
      auto d = mkDabs(50, true); std::vector<uint8_t> db((uint8_t*)d.data(), (uint8_t*)d.data()+d.size()*64); dump("ph_dabs", db);
      fresh(); dump("ph_seed", seed); e.stampDabs(d, 0xFFFFFFFFu, 0.4f, false, sp); readAll("ph_out"); }
#endif
    e.destroy();
    printf("done\n");
    return 0;
}
