//! Impasto v2 (the wet material simulation) on the GPU against CPU references written line for
//! line from core/engine's Kotlin (`ImpastoEngine`, `WetMaterialTransport`,
//! `PersistentWetnessField`, `ImpastoRegionShader.shadeMaterial`; f32 throughout). Runs once per
//! backend with an adapter; skipped with a note when there is none.

use graffux_wgpu::wgpu::Backends;
use graffux_wgpu::{Engine, MaterialDab, WET_FLAG_HEIGHT, WET_FLAG_WETNESS, WET_MEDIA_FLOATS};

const W: i32 = 83;
const H: i32 = 61;
const TILE: i32 = 16;
const COLS: i32 = (W + TILE - 1) / TILE;
const ROWS: i32 = (H + TILE - 1) / TILE;
const N: usize = (W * H) as usize;

struct Rng(u64);
impl Rng {
    fn f(&mut self) -> f32 {
        self.0 = self
            .0
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        ((self.0 >> 40) as f32) / ((1u64 << 24) as f32)
    }
}

fn engines() -> Vec<(&'static str, Engine)> {
    let mut out = Vec::new();
    for (name, b) in [("vulkan", Backends::VULKAN), ("gl", Backends::GL)] {
        match Engine::with_backends(W, H, b) {
            Some(e) => out.push((name, e)),
            None => eprintln!("[{name}] no adapter -- skipped"),
        }
    }
    out
}

/// The material state handed to the engine.
#[derive(Clone)]
struct State {
    raw: Vec<u32>,
    height: Vec<f32>,
    wet: Vec<f32>,
    active: Vec<bool>,
    media: Vec<f32>,
}

fn tile_of(x: i32, y: i32) -> usize {
    ((y / TILE) * COLS + x / TILE) as usize
}

fn state(seed: u64) -> State {
    let mut rng = Rng(seed);
    let tiles = (COLS * ROWS) as usize;
    let active: Vec<bool> = (0..tiles).map(|t| t % 3 != 1).collect();
    let mut raw = vec![0u32; N];
    let mut height = vec![0f32; N];
    let mut wet = vec![0f32; N];
    for y in 0..H {
        for x in 0..W {
            let i = (y * W + x) as usize;
            let c = |r: &mut Rng| (r.f() * 255.0) as u32;
            raw[i] = (c(&mut rng) << 24) | (c(&mut rng) << 16) | (c(&mut rng) << 8) | c(&mut rng);
            height[i] = rng.f() * 0.8;
            if active[tile_of(x, y)] {
                wet[i] = rng.f();
            }
        }
    }
    let mut media = vec![0f32; tiles * WET_MEDIA_FLOATS];
    for t in 0..tiles {
        let m = &mut media[t * WET_MEDIA_FLOATS..(t + 1) * WET_MEDIA_FLOATS];
        m[0] = rng.f() * 0.6; // viscosity
        m[1] = rng.f(); // yield
        m[2] = 0.3 + rng.f() * 0.7; // levelling
        m[3] = rng.f() * 0.5; // drying
        m[4] = rng.f(); // roughness
        m[5] = rng.f(); // specular
    }
    State {
        raw,
        height,
        wet,
        active,
        media,
    }
}

fn begin(e: &mut Engine, s: &State) {
    let active: Vec<u32> = s.active.iter().map(|&a| u32::from(a)).collect();
    assert!(e.wet_begin(&s.raw, &s.height, &s.wet, &active, TILE, &s.media));
}

fn read(e: &mut Engine) -> (Vec<u32>, Vec<f32>, Vec<f32>) {
    let mut raw = vec![0u32; N];
    let mut height = vec![0f32; N];
    let mut wet = vec![0f32; N];
    assert!(e.wet_read(&mut raw, &mut height, &mut wet));
    (raw, height, wet)
}

fn ctl(e: &mut Engine, dabs: usize) -> Vec<u32> {
    let mut out = vec![0u32; (COLS * ROWS) as usize * 2 + dabs];
    assert!(e.wet_read_ctl(&mut out));
    out
}

fn dab_flags(e: &mut Engine, dabs: usize) -> Vec<u32> {
    ctl(e, dabs)[(COLS * ROWS) as usize * 2..].to_vec()
}

fn want_active(s: &State, dabs: &[MaterialDab]) -> Vec<bool> {
    let mut w = s.clone();
    cpu_deposit(&mut w.wet, &mut w.active, dabs, 0.3, 0.5, None);
    w.active
}

fn assert_close(name: &str, what: &str, got: &[f32], want: &[f32], tol: f32) {
    let mut worst = 0f32;
    for (g, w) in got.iter().zip(want) {
        worst = worst.max((g - w).abs());
    }
    assert!(worst <= tol, "[{name}] {what}: worst |gpu - cpu| = {worst}");
}

fn channel_diff(a: u32, b: u32) -> i32 {
    (0..4)
        .map(|k| (((a >> (k * 8)) & 0xFF) as i32 - ((b >> (k * 8)) & 0xFF) as i32).abs())
        .max()
        .unwrap()
}

// ---- CPU references (Kotlin, line for line) ----------------------------------------------------

fn stamp_coverage(r_norm: f32, hardness: f32) -> f32 {
    if r_norm <= 0.0 {
        return 1.0;
    }
    if r_norm >= 1.0 {
        return 0.0;
    }
    let h = hardness.clamp(0.0, 1.0);
    if r_norm <= h {
        return 1.0;
    }
    let denom = 1.0 - h;
    if denom <= 1e-4 {
        return 1.0;
    }
    ((1.0 - r_norm) / denom).clamp(0.0, 1.0)
}

fn build_up(current: f32, flow: f32) -> f32 {
    let c = current.clamp(0.0, 1.0);
    (c + flow.clamp(0.0, 1.0) * (1.0 - c)).clamp(0.0, 1.0)
}

fn dab_box(d: &MaterialDab) -> (i32, i32, i32, i32) {
    (
        ((d.x - d.radius).floor() as i32).max(0),
        ((d.y - d.radius).floor() as i32).max(0),
        ((d.x + d.radius).ceil() as i32).min(W - 1),
        ((d.y + d.radius).ceil() as i32).min(H - 1),
    )
}

struct Medium {
    height_response: f32,
    deposition: f32,
    pickup: f32,
    substrate: f32,
}

/// `ImpastoEngine.transferMaterialStroke` on the smooth substrate. Returns per-dab touched flags.
fn cpu_transfer(
    height: &mut [f32],
    dabs: &[MaterialDab],
    hardness: f32,
    rate: f32,
    m: &Medium,
    initial_load: f32,
    coverage: Option<&[f32]>,
) -> Vec<bool> {
    let rate = rate.max(0.0);
    let mut load = initial_load.clamp(0.0, 1.0);
    let mut flags = vec![false; dabs.len()];
    for (i, d) in dabs.iter().enumerate() {
        if d.radius <= 0.0 {
            continue;
        }
        let (min_x, min_y, max_x, max_y) = dab_box(d);
        if min_x > max_x || min_y > max_y {
            continue;
        }
        let load_at_start = load;
        let contact_depth = d.contact.clamp(0.0, 1.0);
        let alpha_flow = d.alpha.clamp(0.0, 1.0) * d.flow.max(0.0);
        let mut deposited = 0f32;
        let mut picked = 0f32;
        let mut mass = 0f32;
        let contact = |x: i32, y: i32, before: f32| -> Option<(f32, f32)> {
            let sel = coverage.map_or(1.0, |c| c[(y * W + x) as usize].clamp(0.0, 1.0));
            if sel <= 0.0 {
                return None;
            }
            let dx = x as f32 + 0.5 - d.x;
            let dy = y as f32 + 0.5 - d.y;
            let dist = (dx * dx + dy * dy).sqrt();
            if dist >= d.radius {
                return None;
            }
            let c = stamp_coverage((dist / d.radius).clamp(0.0, 1.0), hardness);
            if c <= 0.0 {
                return None;
            }
            let _ = before;
            let response = m.substrate.clamp(0.0, 1.0);
            let gate = ((1.0 - response) + response).clamp(0.0, 1.0);
            let ct = (c * sel * contact_depth * alpha_flow * gate).clamp(0.0, 1.0);
            (ct > 0.0).then_some((ct, c * sel))
        };
        for y in min_y..=max_y {
            for x in min_x..=max_x {
                let before = height[(y * W + x) as usize].clamp(0.0, 1.0);
                let Some((ct, cs)) = contact(x, y, before) else {
                    continue;
                };
                mass += cs;
                let inc = rate * m.height_response * m.deposition * load_at_start * ct;
                deposited += (build_up(before, inc) - before).max(0.0);
            }
        }
        let denom = (mass * rate.max(1e-4)).max(1e-4);
        let spent = if mass > 0.0 {
            ((deposited / denom) * 0.12).clamp(0.0, load_at_start)
        } else {
            0.0
        };
        let load_after = (load_at_start - spent).clamp(0.0, 1.0);
        let capacity = 1.0 - load_after;
        let max_pickup = capacity * denom / 0.12;
        for y in min_y..=max_y {
            for x in min_x..=max_x {
                let idx = (y * W + x) as usize;
                let before = height[idx].clamp(0.0, 1.0);
                let Some((ct, _)) = contact(x, y, before) else {
                    continue;
                };
                let inc = rate * m.height_response * m.deposition * load_at_start * ct;
                let mut local = build_up(before, inc).clamp(0.0, 1.0);
                let remaining = (max_pickup - picked).max(0.0);
                let request = rate * m.pickup * capacity * ct;
                let removed = local.min(request.min(remaining));
                local = (local - removed).max(0.0);
                picked += removed;
                if local != before {
                    height[idx] = local;
                    flags[i] = true;
                }
            }
        }
        let refill = if mass > 0.0 {
            ((picked / denom) * 0.12).clamp(0.0, capacity)
        } else {
            0.0
        };
        load = (load_after + refill).clamp(0.0, 1.0);
    }
    flags
}

/// `ImpastoEngine.depositWetnessStroke`; returns per-dab touched flags.
fn cpu_deposit(
    wet: &mut [f32],
    active: &mut [bool],
    dabs: &[MaterialDab],
    hardness: f32,
    rate: f32,
    coverage: Option<&[f32]>,
) -> Vec<bool> {
    let rate = rate.clamp(0.0, 1.0);
    let mut flags = vec![false; dabs.len()];
    for (i, d) in dabs.iter().enumerate() {
        if d.radius <= 0.0 {
            continue;
        }
        let (min_x, min_y, max_x, max_y) = dab_box(d);
        for y in min_y..=max_y {
            for x in min_x..=max_x {
                let sel = coverage.map_or(1.0, |c| c[(y * W + x) as usize].clamp(0.0, 1.0));
                if sel <= 0.0 {
                    continue;
                }
                let dx = x as f32 + 0.5 - d.x;
                let dy = y as f32 + 0.5 - d.y;
                let dist = (dx * dx + dy * dy).sqrt();
                if dist >= d.radius {
                    continue;
                }
                let c = stamp_coverage((dist / d.radius).clamp(0.0, 1.0), hardness);
                let amount = rate
                    * c
                    * sel
                    * d.contact.clamp(0.0, 1.0)
                    * d.alpha.clamp(0.0, 1.0)
                    * d.flow.max(0.0);
                if amount <= 0.0 {
                    continue;
                }
                let idx = (y * W + x) as usize;
                active[tile_of(x, y)] = true;
                wet[idx] = (wet[idx] + amount).clamp(0.0, 1.0);
                flags[i] = true;
            }
        }
    }
    flags
}

fn media_at(s: &State, x: i32, y: i32, k: usize) -> f32 {
    s.media[tile_of(x, y) * WET_MEDIA_FLOATS + k]
}

/// Active tiles in row-major order, as `activeTileCoordinates`.
fn active_tiles(active: &[bool]) -> Vec<(i32, i32)> {
    (0..active.len())
        .filter(|&t| active[t])
        .map(|t| (t as i32 % COLS, t as i32 / COLS))
        .collect()
}

/// `ImpastoEngine.levelWetHeight` (four edge phases), terrain zero.
fn cpu_level(s: &mut State, dt: f32, iterations: i32, bounds: (i32, i32, i32, i32)) {
    let count = iterations.clamp(0, 4);
    let (bl, bt, br, bb) = bounds;
    let tiles = active_tiles(&s.active);
    let wet = s.wet.clone();
    let wet_at = |x: i32, y: i32| wet[(y * W + x) as usize];
    let exchange = |s: &mut State, a: usize, ax: i32, ay: i32, b: usize, bx: i32, by: i32| {
        let w = ((wet_at(ax, ay) + wet_at(bx, by)) * 0.5).clamp(0.0, 1.0);
        if w <= 0.0 {
            return;
        }
        let viscosity = (media_at(s, ax, ay, 0) + media_at(s, bx, by, 0)) * 0.5;
        let leveling = (media_at(s, ax, ay, 2) + media_at(s, bx, by, 2)) * 0.5;
        let yield_strength = (media_at(s, ax, ay, 1) + media_at(s, bx, by, 1)) * 0.5;
        let fluidity = w * (1.0 - viscosity);
        if fluidity <= 0.0 || leveling <= 0.0 {
            return;
        }
        let diff = s.height[b].max(0.0) + 0.0 - (s.height[a].max(0.0) + 0.0);
        let threshold = yield_strength * (1.0 - w) * 0.08;
        let excess = diff.abs() - threshold;
        if excess <= 0.0 {
            return;
        }
        let step = (leveling * dt / count as f32).clamp(0.0, 0.25);
        let amount = excess * step * fluidity;
        if amount <= 0.0 {
            return;
        }
        if diff > 0.0 {
            let moved = amount.min(s.height[b].max(0.0).min((1.0 - s.height[a]).max(0.0)));
            if moved <= 0.0 {
                return;
            }
            s.height[b] = (s.height[b] - moved).max(0.0);
            s.height[a] = (s.height[a] + moved).clamp(0.0, 1.0);
        } else {
            let moved = amount.min(s.height[a].max(0.0).min((1.0 - s.height[b]).max(0.0)));
            if moved <= 0.0 {
                return;
            }
            s.height[a] = (s.height[a] - moved).max(0.0);
            s.height[b] = (s.height[b] + moved).clamp(0.0, 1.0);
        }
    };
    for _ in 0..count {
        for phase in 0..4 {
            for &(tx, ty) in &tiles {
                let left = bl.max(tx * TILE);
                let top = bt.max(ty * TILE);
                let right = br.min(W.min(tx * TILE + TILE));
                let bottom = bb.min(H.min(ty * TILE + TILE));
                for y in top..bottom {
                    for x in left..right {
                        let idx = (y * W + x) as usize;
                        if phase < 2 {
                            if x % 2 == phase && x + 1 < br && s.active[tile_of(x + 1, y)] {
                                exchange(s, idx, x, y, idx + 1, x + 1, y);
                            }
                        } else if y % 2 == phase - 2 && y + 1 < bb && s.active[tile_of(x, y + 1)] {
                            exchange(s, idx, x, y, idx + W as usize, x, y + 1);
                        }
                    }
                }
            }
        }
    }
}

/// `WetMaterialTransport.advanceArgb` (four edge phases).
fn cpu_pigment(s: &mut State, dt: f32, rate: f32, iterations: i32) {
    let count = iterations.clamp(0, 4);
    let iteration_rate = rate.clamp(0.0, 1.0) * dt / count as f32;
    let tiles = active_tiles(&s.active);
    let exchange = |raw: &mut [u32], a: usize, b: usize, step: f32| {
        let (pa, pb) = (raw[a], raw[b]);
        let mut oa = 0u32;
        let mut ob = 0u32;
        for shift in [24u32, 16, 8, 0] {
            let av = ((pa >> shift) & 0xFF) as i32;
            let bv = ((pb >> shift) & 0xFF) as i32;
            let mv = ((bv - av) as f32 * step + 0.5).floor() as i32;
            oa |= ((av + mv).clamp(0, 255) as u32) << shift;
            ob |= ((bv - mv).clamp(0, 255) as u32) << shift;
        }
        raw[a] = oa;
        raw[b] = ob;
    };
    for _ in 0..count {
        for phase in 0..4 {
            for &(tx, ty) in &tiles {
                for y in ty * TILE..H.min(ty * TILE + TILE) {
                    for x in tx * TILE..W.min(tx * TILE + TILE) {
                        let idx = (y * W + x) as usize;
                        let nb = if phase < 2 {
                            (x % 2 == phase && x + 1 < W && s.active[tile_of(x + 1, y)])
                                .then_some((x + 1, y))
                        } else {
                            (y % 2 == phase - 2 && y + 1 < H && s.active[tile_of(x, y + 1)])
                                .then_some((x, y + 1))
                        };
                        let Some((nx, ny)) = nb else { continue };
                        let mobility =
                            ((s.wet[idx] + s.wet[(ny * W + nx) as usize]) * 0.5).clamp(0.0, 1.0);
                        let step = (iteration_rate * mobility).clamp(0.0, 0.25);
                        if step > 0.0 {
                            exchange(&mut s.raw, idx, (ny * W + nx) as usize, step);
                        }
                    }
                }
            }
        }
    }
}

/// `PersistentWetnessField.advance` (per-tile drying when `tile_drying`).
fn cpu_advance(s: &mut State, dt: f32, drying: f32, transport: f32, tile_drying: bool) {
    let step = (transport.clamp(0.0, 1.0) * dt).clamp(0.0, 1.0) * 0.25;
    let mut delta = vec![0f32; N];
    let tiles = active_tiles(&s.active);
    let px = |tx: i32, ty: i32| {
        (ty * TILE..H.min(ty * TILE + TILE))
            .flat_map(move |y| (tx * TILE..W.min(tx * TILE + TILE)).map(move |x| (x, y)))
    };
    if step > 0.0 {
        for &(tx, ty) in &tiles {
            for (x, y) in px(tx, ty) {
                let a = (y * W + x) as usize;
                for (bx, by) in [(x + 1, y), (x, y + 1)] {
                    if bx < W && by < H && s.active[tile_of(bx, by)] {
                        let b = (by * W + bx) as usize;
                        let flux = (s.wet[b] - s.wet[a]) * step;
                        delta[a] += flux;
                        delta[b] -= flux;
                    }
                }
            }
        }
    }
    let uniform = ((-drying.clamp(0.0, 1.0) * dt) as f64).exp() as f32;
    let mut still = vec![false; s.active.len()];
    for &(tx, ty) in &tiles {
        for (x, y) in px(tx, ty) {
            let i = (y * W + x) as usize;
            let mul = if tile_drying {
                ((-media_at(s, x, y, 3).clamp(0.0, 1.0) * dt) as f64).exp() as f32
            } else {
                uniform
            };
            let next = ((s.wet[i] + delta[i]).clamp(0.0, 1.0) * mul).clamp(0.0, 1.0);
            s.wet[i] = if next <= 1e-4 { 0.0 } else { next };
            if s.wet[i] > 1e-4 {
                still[tile_of(x, y)] = true;
            }
        }
    }
    for (a, st) in s.active.iter_mut().zip(still) {
        *a = *a && st;
    }
}

const AZ: f32 = 135.0;
const EL: f32 = 45.0;

/// `ImpastoRegionShader.shadeMaterial`; straight ARGB out.
fn cpu_shade(s: &State, x: i32, y: i32, relief: f32, use_wet: bool) -> u32 {
    let d2r = 0.017453292f32;
    let (az, el) = (AZ * d2r, EL * d2r);
    let lx = az.cos() * el.cos();
    let ly = az.sin() * el.cos();
    let lz = el.sin();
    let (hx0, hy0, hz0) = (lx, ly, lz + 1.0);
    let hinv = 1.0 / (hx0 * hx0 + hy0 * hy0 + hz0 * hz0).sqrt();
    let at = |x: i32, y: i32| s.height[(y.clamp(0, H - 1) * W + x.clamp(0, W - 1)) as usize];
    let dhdx = (at(x + 1, y) - at(x - 1, y)) / 2.0;
    let dhdy = (at(x, y + 1) - at(x, y - 1)) / 2.0;
    let inv = 1.0 / (dhdx * dhdx + dhdy * dhdy + 1.0).sqrt();
    let (nx, ny, nz) = (-dhdx * inv, -dhdy * inv, inv);
    let diffuse = nx * lx + ny * ly + nz * lz;
    let m = (1.0 + relief.max(0.0) * (diffuse - lz)).clamp(0.0, 3.0);
    let wet = if use_wet {
        s.wet[(y * W + x) as usize].clamp(0.0, 1.0)
    } else {
        0.0
    };
    let roughness = (media_at(s, x, y, 4) * (1.0 - wet) + 0.08 * wet).clamp(0.08, 1.0);
    let shininess = 4.0 + (1.0 - roughness) * 60.0;
    let ndh = (nx * hx0 * hinv + ny * hy0 * hinv + nz * hz0 * hinv).clamp(0.0, 1.0);
    let spec = media_at(s, x, y, 5) * wet * (ndh as f64).powf(shininess as f64) as f32;
    let c = s.raw[(y * W + x) as usize];
    let add = 255.0 * spec.clamp(0.0, 1.0);
    let ch = |sh: u32| (((c >> sh) & 0xFF) as f32 * m + add) as i32;
    ((c >> 24) << 24)
        | ((ch(16).clamp(0, 255) as u32) << 16)
        | ((ch(8).clamp(0, 255) as u32) << 8)
        | ch(0).clamp(0, 255) as u32
}

// ---- tests -------------------------------------------------------------------------------------

fn stroke(seed: u64) -> Vec<MaterialDab> {
    let mut rng = Rng(seed);
    (0..24)
        .map(|i| {
            MaterialDab::new(
                8.0 + i as f32 * 2.9 + rng.f(),
                12.0 + rng.f() * 36.0,
                3.0 + rng.f() * 9.0,
                0.4 + rng.f() * 0.6,
                0.5 + rng.f(),
                rng.f(),
            )
        })
        .collect()
}

#[test]
fn transfer_matches_the_cpu_reference() {
    let m = Medium {
        height_response: 0.9,
        deposition: 0.8,
        pickup: 0.6,
        substrate: 0.3,
    };
    for (name, mut e) in engines() {
        for with_selection in [false, true] {
            let s = state(11);
            let coverage: Vec<f32> = (0..N)
                .map(|i| ((i % W as usize) as f32 / W as f32 * 1.6 - 0.3).clamp(0.0, 1.0))
                .collect();
            let cov = with_selection.then_some(coverage.as_slice());
            let dabs = stroke(5);
            begin(&mut e, &s);
            assert!(e.wet_set_coverage(cov));
            assert!(e.wet_set_dabs(&dabs));
            assert!(e.wet_transfer(
                0.4,
                0.7,
                m.height_response,
                m.deposition,
                m.pickup,
                m.substrate,
                0.8
            ));
            let flags = dab_flags(&mut e, dabs.len());
            let (_, height, _) = read(&mut e);

            let mut want = s.height.clone();
            let want_flags = cpu_transfer(&mut want, &dabs, 0.4, 0.7, &m, 0.8, cov);
            // Pass 1's reduction and pass 2's prefix sum add in a different order than the CPU's
            // running sums.
            assert_close(name, "transfer height", &height, &want, 2e-4);
            for (g, w) in flags.iter().zip(&want_flags) {
                assert_eq!(g & WET_FLAG_HEIGHT != 0, *w, "[{name}] transfer flags");
            }
            assert!(
                want_flags.iter().any(|&f| f),
                "[{name}] transfer touched nothing"
            );
            assert_ne!(height, s.height, "[{name}] transfer changed nothing");
        }
    }
}

#[test]
fn wetness_deposit_matches_the_cpu_reference() {
    for (name, mut e) in engines() {
        let s = state(12);
        let dabs = stroke(6);
        begin(&mut e, &s);
        assert!(e.wet_set_dabs(&dabs));
        assert!(e.wet_deposit(0.3, 0.5));
        let flags = dab_flags(&mut e, dabs.len());
        let ctl = ctl(&mut e, 0);
        for (t, a) in want_active(&s, &dabs).iter().enumerate() {
            assert_eq!(ctl[t] != 0, *a, "[{name}] active tile {t} after deposit");
        }
        let (_, _, wet) = read(&mut e);

        let mut want = s.clone();
        let want_flags = cpu_deposit(&mut want.wet, &mut want.active, &dabs, 0.3, 0.5, None);
        assert_close(name, "deposited wetness", &wet, &want.wet, 1e-6);
        assert_ne!(wet, s.wet, "[{name}] deposit changed nothing");
        for (g, w) in flags.iter().zip(&want_flags) {
            assert_eq!(g & WET_FLAG_WETNESS != 0, *w, "[{name}] deposit flags");
        }
        // The newly wet tiles are active: a pigment pass now moves colour there exactly as the
        // reference does with the same active set.
        assert!(e.wet_pigment(0.5, 0.9, 2));
        cpu_pigment(&mut want, 0.5, 0.9, 2);
        let (raw, _, _) = read(&mut e);
        assert_eq!(raw, want.raw, "[{name}] pigment after deposit");
    }
}

#[test]
fn levelling_matches_the_cpu_reference() {
    for (name, mut e) in engines() {
        let s = state(13);
        begin(&mut e, &s);
        assert!(e.wet_level(0.4, 2, 5, 3, 70, 50));
        let (_, height, _) = read(&mut e);
        let mut want = s.clone();
        cpu_level(&mut want, 0.4, 2, (5, 3, 75, 53));
        assert_close(name, "levelled height", &height, &want.height, 1e-6);
        assert_ne!(height, s.height, "[{name}] levelling moved nothing");
    }
}

#[test]
fn pigment_transport_matches_the_cpu_reference_exactly() {
    for (name, mut e) in engines() {
        let s = state(14);
        begin(&mut e, &s);
        assert!(e.wet_pigment(0.8, 0.7, 3));
        let (raw, _, _) = read(&mut e);
        let mut want = s.clone();
        cpu_pigment(&mut want, 0.8, 0.7, 3);
        assert_eq!(raw, want.raw, "[{name}] pigment transport");
        assert_ne!(raw, s.raw, "[{name}] transport moved nothing");
    }
}

#[test]
fn wetness_advance_matches_the_cpu_reference() {
    for (name, mut e) in engines() {
        for tile_drying in [false, true] {
            let s = state(15);
            begin(&mut e, &s);
            assert!(e.wet_advance(0.7, 0.3, 0.6, tile_drying));
            // A second step sees the first step's active set.
            assert!(e.wet_advance(9.0, 0.9, 0.6, tile_drying));
            let (_, _, wet) = read(&mut e);
            let mut want = s.clone();
            cpu_advance(&mut want, 0.7, 0.3, 0.6, tile_drying);
            cpu_advance(&mut want, 9.0, 0.9, 0.6, tile_drying);
            // The flux sums add in a different order; per-tile drying is f32 exp on the GPU.
            assert_close(name, "advanced wetness", &wet, &want.wet, 2e-5);
        }
    }
}

#[test]
fn shading_matches_the_cpu_reference_and_only_touches_its_region() {
    for (name, mut e) in engines() {
        let mut s = state(16);
        // Opaque pigment: the premultiplied layer then holds it losslessly.
        for c in s.raw.iter_mut() {
            *c |= 0xFF00_0000;
        }
        let base = vec![0x8040_2010u32; N];
        assert!(e.upload(bytemuck::cast_slice(&base)));
        begin(&mut e, &s);
        let (rx, ry, rw, rh) = (7, 9, 40, 30);
        assert!(e.wet_shade(rx, ry, rw, rh, AZ, EL, 0.75, true));
        let layer = e.read_all().unwrap();
        let px: &[u32] = bytemuck::cast_slice(&layer);
        let mut worst = 0;
        for y in 0..H {
            for x in 0..W {
                let got = px[(y * W + x) as usize];
                let inside = x >= rx && x < rx + rw && y >= ry && y < ry + rh;
                if !inside {
                    assert_eq!(got, 0x8040_2010, "[{name}] shade wrote outside its region");
                    continue;
                }
                // Layer RGBA (R low) -> ARGB.
                let b = got.to_le_bytes();
                let argb = (u32::from(b[3]) << 24)
                    | (u32::from(b[0]) << 16)
                    | (u32::from(b[1]) << 8)
                    | u32::from(b[2]);
                worst = worst.max(channel_diff(argb, cpu_shade(&s, x, y, 0.75, true)));
            }
        }
        // f32 pow on the GPU against the CPU's f64.
        assert!(
            worst <= 2,
            "[{name}] shade: worst channel difference {worst}"
        );
    }
}

#[test]
fn raw_round_trips_through_the_layer_for_opaque_pigment() {
    for (name, mut e) in engines() {
        let mut s = state(17);
        for c in s.raw.iter_mut() {
            *c |= 0xFF00_0000;
        }
        begin(&mut e, &s);
        assert!(e.wet_raw_to_layer());
        assert!(e.wet_layer_to_raw());
        let (raw, _, _) = read(&mut e);
        assert_eq!(raw, s.raw, "[{name}] raw -> layer -> raw");
    }
}
