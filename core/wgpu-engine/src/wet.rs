//! Impasto v2, the wet material simulation, on the GPU (`shaders/wet.wgsl`). The CPU references are
//! core/engine's `ImpastoEngine` (material transfer, wetness deposit, levelling, material shading),
//! `WetMaterialTransport` (pigment transport) and `PersistentWetnessField` (wetness transport and
//! drying); each entry point follows its reference step for step, and `tests/wet.rs` checks them.
//!
//! The caller keeps the canonical material state (raw pigment, height, wetness, active tiles, tile
//! media) and hands it over with [`Engine::wet_begin`]; the simulation then runs entirely on the
//! GPU, the raw pigment meeting the engine layer only where the stroke's colour is stamped
//! ([`Engine::wet_raw_to_layer`] / [`Engine::wet_layer_to_raw`]) and where the lit presentation is
//! written ([`Engine::wet_shade`]). [`Engine::wet_read`] returns the state.

use bytemuck::{Pod, Zeroable};

use super::Engine;
use crate::resident::Rect;

pub const WET_WGSL: &str = include_str!("shaders/wet.wgsl");

/// One dab of a material stroke, binary-identical to the shader's `MaterialDab` (32 bytes).
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, Pod, Zeroable, PartialEq)]
pub struct MaterialDab {
    pub x: f32,
    pub y: f32,
    pub radius: f32,
    pub alpha: f32,
    pub flow: f32,
    pub contact: f32,
    pub _pad: [f32; 2],
}

impl MaterialDab {
    pub fn new(x: f32, y: f32, radius: f32, alpha: f32, flow: f32, contact: f32) -> Self {
        MaterialDab {
            x,
            y,
            radius,
            alpha,
            flow,
            contact,
            _pad: [0.0; 2],
        }
    }
}

/// Floats per tile in the media table: viscosity, yield-like strength, levelling rate, drying
/// rate, base roughness, wet specular strength, then two unused (sanitized `PaintMedium` values).
pub const WET_MEDIA_FLOATS: usize = 8;
/// Dab flag bits from [`Engine::wet_read_ctl`].
pub const WET_FLAG_HEIGHT: u32 = 1;
pub const WET_FLAG_WETNESS: u32 = 2;

/// `PersistentWetnessField.DEFAULT_DRY_EPSILON`.
const DRY_EPSILON: f32 = 1e-4;
const LIGHT_DEG_TO_RAD: f32 = 0.017453292;

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable, Default)]
struct Params {
    width: i32,
    height: i32,
    tile: i32,
    cols: i32,
    rx: i32,
    ry: i32,
    rw: i32,
    rh: i32,
    phase: i32,
    dab_count: u32,
    use_cov: u32,
    use_wet: u32,
    tiles: u32,
    flags_off: u32,
    n: u32,
    mode: u32,
    f: [f32; 8],
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Entry {
    RawToLayer,
    LayerToRaw,
    BlendSelection,
    DepositWetness,
    Transfer,
    LevelPhase,
    PigmentPhase,
    WetDelta,
    WetApply,
    WetCommit,
    ShadeMaterial,
}

const ENTRIES: [(Entry, &str, &[u32]); 11] = [
    (Entry::RawToLayer, "raw_to_layer", &[0, 1, 9]),
    (Entry::LayerToRaw, "layer_to_raw", &[0, 1, 9]),
    (Entry::BlendSelection, "blend_selection", &[0, 1, 2, 7]),
    (Entry::DepositWetness, "deposit_wetness", &[0, 3, 5, 7, 8]),
    (Entry::Transfer, "transfer", &[0, 3, 5, 7, 8]),
    (Entry::LevelPhase, "level_phase", &[0, 3, 5, 6]),
    (Entry::PigmentPhase, "pigment_phase", &[0, 1, 3, 5]),
    (Entry::WetDelta, "wet_delta", &[0, 3, 4, 5]),
    (Entry::WetApply, "wet_apply", &[0, 3, 4, 5, 6]),
    (Entry::WetCommit, "wet_commit", &[0, 5]),
    (Entry::ShadeMaterial, "shade_material", &[0, 1, 3, 6, 9]),
];

const WORKGROUP: u32 = 8;
const EDGE_PHASES: i32 = 4;

/// The simulation's pipelines and state buffers, created by [`Engine::wet_begin`].
pub(super) struct WetGpu {
    pipelines: Vec<(Entry, wgpu::ComputePipeline, &'static [u32])>,
    uniform: wgpu::Buffer,
    raw: wgpu::Buffer,
    raw_before: wgpu::Buffer,
    fields: wgpu::Buffer,
    delta: wgpu::Buffer,
    ctl: wgpu::Buffer,
    media: wgpu::Buffer,
    coverage: wgpu::Buffer,
    dabs: wgpu::Buffer,
    dab_capacity: usize,
    dab_count: usize,
    /// The union of the dabs' pixel boxes (the wetness deposit's dispatch region).
    dab_region: Rect,
    tile: i32,
    cols: i32,
    tiles: u32,
    use_coverage: bool,
    /// Raw, fields, active/next flags + load slot, media as [`Engine::wet_checkpoint_save`] left them.
    checkpoint: Option<[wgpu::Buffer; 4]>,
}

/// What Color Smudge binds to read and write the canvas wetness (engine.rs `color_smudge`).
pub(super) struct SmudgeWetBinding {
    pub(super) fields: wgpu::Buffer,
    pub(super) ctl: wgpu::Buffer,
    pub(super) coverage: wgpu::Buffer,
    pub(super) n: u32,
    pub(super) tile: i32,
    pub(super) cols: i32,
    pub(super) use_coverage: bool,
}

impl WetGpu {
    fn new(device: &wgpu::Device, width: i32, height: i32, tile: i32) -> Self {
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("wet"),
            source: wgpu::ShaderSource::Wgsl(WET_WGSL.into()),
        });
        let pipelines = ENTRIES
            .iter()
            .map(|&(entry, name, bindings)| {
                let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some(name),
                    layout: None,
                    module: &module,
                    entry_point: Some(name),
                    compilation_options: Default::default(),
                    cache: None,
                });
                (entry, pipeline, bindings)
            })
            .collect();
        let n = width as u64 * height as u64;
        let cols = (width + tile - 1) / tile;
        let rows = (height + tile - 1) / tile;
        let tiles = (cols * rows) as u32;
        let buffer = |label: &str, size: u64, usage: wgpu::BufferUsages| {
            device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size: size.max(4),
                usage,
                mapped_at_creation: false,
            })
        };
        let storage = wgpu::BufferUsages::STORAGE
            | wgpu::BufferUsages::COPY_DST
            | wgpu::BufferUsages::COPY_SRC;
        let dab_capacity = 64;
        WetGpu {
            pipelines,
            uniform: buffer(
                "wet params",
                std::mem::size_of::<Params>() as u64,
                wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            ),
            raw: buffer("wet raw", n * 4, storage),
            raw_before: buffer("wet raw before", n * 4, storage),
            fields: buffer("wet fields", n * 8, storage),
            delta: buffer("wet delta", n * 4, storage),
            // Active + next-step flags; the dab flags grow it (see wet_set_dabs).
            ctl: buffer("wet ctl", (tiles as u64 * 2 + 1) * 4, storage),
            media: buffer(
                "wet media",
                tiles as u64 * (WET_MEDIA_FLOATS * 4) as u64,
                storage,
            ),
            coverage: buffer("wet coverage", n * 4, storage),
            dabs: buffer(
                "wet dabs",
                (dab_capacity * std::mem::size_of::<MaterialDab>()) as u64,
                storage,
            ),
            dab_capacity,
            dab_count: 0,
            dab_region: Rect::default(),
            tile,
            cols,
            tiles,
            use_coverage: false,
            checkpoint: None,
        }
    }

    fn checkpoint_sizes(&self) -> [u64; 4] {
        [
            self.raw.size(),
            self.fields.size(),
            (self.tiles as u64 * 2 + 1) * 4,
            self.media.size(),
        ]
    }

    fn resource<'a>(&'a self, binding: u32, layer: &'a wgpu::Buffer) -> wgpu::BindingResource<'a> {
        match binding {
            0 => self.uniform.as_entire_binding(),
            1 => self.raw.as_entire_binding(),
            2 => self.raw_before.as_entire_binding(),
            3 => self.fields.as_entire_binding(),
            4 => self.delta.as_entire_binding(),
            5 => self.ctl.as_entire_binding(),
            6 => self.media.as_entire_binding(),
            7 => self.coverage.as_entire_binding(),
            8 => self.dabs.as_entire_binding(),
            _ => layer.as_entire_binding(),
        }
    }
}

impl Engine {
    fn n_pixels(&self) -> usize {
        (self.width * self.height) as usize
    }

    /// Hands the canonical material state to the GPU: straight ARGB raw pigment, height and
    /// wetness (one value per pixel), the active flag of each `tile_size` tile (row-major, nonzero
    /// = active) and the media table ([`WET_MEDIA_FLOATS`] per tile). No selection until
    /// [`Engine::wet_set_coverage`].
    pub fn wet_begin(
        &mut self,
        raw: &[u32],
        height: &[f32],
        wetness: &[f32],
        active: &[u32],
        tile_size: i32,
        media: &[f32],
    ) -> bool {
        let n = self.n_pixels();
        if tile_size <= 0 || raw.len() < n || height.len() < n || wetness.len() < n {
            return false;
        }
        let reuse = self
            .wet
            .as_ref()
            .is_some_and(|g| g.tile == tile_size && g.raw.size() >= (n * 4) as u64);
        if !reuse {
            self.wet = Some(WetGpu::new(
                &self.device,
                self.width,
                self.height,
                tile_size,
            ));
        }
        let Some(gpu) = self.wet.as_mut() else {
            return false;
        };
        let tiles = gpu.tiles as usize;
        if active.len() < tiles || media.len() < tiles * WET_MEDIA_FLOATS {
            return false;
        }
        gpu.use_coverage = false;
        gpu.dab_count = 0;
        self.queue
            .write_buffer(&gpu.raw, 0, bytemuck::cast_slice(&raw[..n]));
        self.queue
            .write_buffer(&gpu.fields, 0, bytemuck::cast_slice(&height[..n]));
        self.queue.write_buffer(
            &gpu.fields,
            (n * 4) as u64,
            bytemuck::cast_slice(&wetness[..n]),
        );
        // Active and next-step flags, then the reservoir-load slot (0 until a transfer stores one).
        let mut ctl = vec![0u32; tiles * 2 + 1];
        for (flag, &a) in ctl.iter_mut().zip(active) {
            *flag = u32::from(a != 0);
        }
        self.queue
            .write_buffer(&gpu.ctl, 0, bytemuck::cast_slice(&ctl));
        self.queue.write_buffer(
            &gpu.media,
            0,
            bytemuck::cast_slice(&media[..tiles * WET_MEDIA_FLOATS]),
        );
        self.ok()
    }

    /// Replaces the media table (after the stroke records the medium that contacted its tiles).
    pub fn wet_set_media(&mut self, media: &[f32]) -> bool {
        let Some(gpu) = self.wet.as_ref() else {
            return false;
        };
        let count = gpu.tiles as usize * WET_MEDIA_FLOATS;
        if media.len() < count {
            return false;
        }
        self.queue
            .write_buffer(&gpu.media, 0, bytemuck::cast_slice(&media[..count]));
        self.ok()
    }

    /// The selection's per-pixel coverage (0..1), or `None` for no selection.
    pub fn wet_set_coverage(&mut self, coverage: Option<&[f32]>) -> bool {
        let n = self.n_pixels();
        let Some(gpu) = self.wet.as_mut() else {
            return false;
        };
        match coverage {
            Some(c) if c.len() >= n => {
                self.queue
                    .write_buffer(&gpu.coverage, 0, bytemuck::cast_slice(&c[..n]));
                gpu.use_coverage = true;
            }
            Some(_) => return false,
            None => gpu.use_coverage = false,
        }
        self.ok()
    }

    /// The stroke's dabs for [`Engine::wet_transfer`] and [`Engine::wet_deposit`]; clears their
    /// flags.
    pub fn wet_set_dabs(&mut self, dabs: &[MaterialDab]) -> bool {
        let Some(mut gpu) = self.wet.take() else {
            return false;
        };
        if dabs.len() > gpu.dab_capacity {
            gpu.dab_capacity = dabs.len().next_power_of_two();
            gpu.dabs = self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("wet dabs"),
                size: (gpu.dab_capacity * std::mem::size_of::<MaterialDab>()) as u64,
                usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
        }
        let ctl_words = gpu.tiles as usize * 2 + 1 + dabs.len();
        if (ctl_words * 4) as u64 > gpu.ctl.size() {
            // Grow the control buffer, keeping the tile flags.
            let grown = self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("wet ctl"),
                size: ((gpu.tiles as usize * 2 + 1 + gpu.dab_capacity) * 4) as u64,
                usage: wgpu::BufferUsages::STORAGE
                    | wgpu::BufferUsages::COPY_DST
                    | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            });
            let mut encoder = self.device.create_command_encoder(&Default::default());
            encoder.copy_buffer_to_buffer(&gpu.ctl, 0, &grown, 0, (gpu.tiles as u64 * 2 + 1) * 4);
            self.queue.submit([encoder.finish()]);
            gpu.ctl = grown;
        }
        if !dabs.is_empty() {
            self.queue
                .write_buffer(&gpu.dabs, 0, bytemuck::cast_slice(dabs));
            self.queue.write_buffer(
                &gpu.ctl,
                (gpu.tiles as u64 * 2 + 1) * 4,
                bytemuck::cast_slice(&vec![0u32; dabs.len()]),
            );
        }
        gpu.dab_count = dabs.len();
        gpu.dab_region = dab_region(dabs, self.width, self.height);
        self.wet = Some(gpu);
        self.ok()
    }

    /// Converts the raw pigment into the (premultiplied) layer, for stamping the stroke's colour.
    /// Like [`Engine::upload`], this seeds a new stroke: stroke-max state starts from this layer.
    pub fn wet_raw_to_layer(&mut self) -> bool {
        self.mp_session_ending();
        self.detach();
        let all = Rect::new(0, 0, self.width, self.height);
        let ok = self.wet_run(Entry::RawToLayer, all, |_| {});
        self.mark_fully_dirty();
        self.stroke_state_dirty = true;
        self.stroke_written = Rect::default();
        self.mp_session_started();
        ok && self.ok()
    }

    /// Converts the layer back into raw pigment after stamping.
    pub fn wet_layer_to_raw(&mut self) -> bool {
        self.mp_drain();
        let all = Rect::new(0, 0, self.width, self.height);
        self.wet_run(Entry::LayerToRaw, all, |_| {})
    }

    /// Keeps the raw pigment as it is now, for [`Engine::wet_blend_selection`].
    pub fn wet_save_raw_before(&mut self) -> bool {
        let Some(gpu) = self.wet.as_ref() else {
            return false;
        };
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(&gpu.raw, 0, &gpu.raw_before, 0, gpu.raw.size());
        self.queue.submit([encoder.finish()]);
        self.ok()
    }

    /// Keeps the wet state (raw pigment, height, wetness, tile flags, media) on the GPU, so a later
    /// [`Engine::wet_checkpoint_restore`] restarts from it without an upload. A live preview saves
    /// right after [`Engine::wet_begin`]; the commit restores on the same engine.
    pub fn wet_checkpoint_save(&mut self) -> bool {
        let Some(gpu) = self.wet.as_mut() else {
            return false;
        };
        let sizes = gpu.checkpoint_sizes();
        if gpu.checkpoint.is_none() {
            let make = |size: u64| {
                self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("wet checkpoint"),
                    size,
                    usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::COPY_SRC,
                    mapped_at_creation: false,
                })
            };
            gpu.checkpoint = Some(sizes.map(make));
        }
        let saved = gpu.checkpoint.as_ref().expect("made");
        let live = [&gpu.raw, &gpu.fields, &gpu.ctl, &gpu.media];
        let mut encoder = self.device.create_command_encoder(&Default::default());
        for ((from, to), size) in live.iter().zip(saved).zip(sizes) {
            encoder.copy_buffer_to_buffer(from, 0, to, 0, size);
        }
        self.queue.submit([encoder.finish()]);
        self.ok()
    }

    /// Puts back the state [`Engine::wet_checkpoint_save`] kept, as [`Engine::wet_begin`] would
    /// (no selection, no dabs). False without a checkpoint.
    pub fn wet_checkpoint_restore(&mut self) -> bool {
        let Some(gpu) = self.wet.as_mut() else {
            return false;
        };
        let Some(saved) = gpu.checkpoint.as_ref() else {
            return false;
        };
        let sizes = gpu.checkpoint_sizes();
        let live = [&gpu.raw, &gpu.fields, &gpu.ctl, &gpu.media];
        let mut encoder = self.device.create_command_encoder(&Default::default());
        for ((from, to), size) in saved.iter().zip(live).zip(sizes) {
            encoder.copy_buffer_to_buffer(from, 0, to, 0, size);
        }
        self.queue.submit([encoder.finish()]);
        gpu.use_coverage = false;
        gpu.dab_count = 0;
        gpu.dab_region = Rect::default();
        self.ok()
    }

    /// The raw pigment kept only through the selection coverage (the saved pigment elsewhere).
    /// A no-op without a selection.
    pub fn wet_blend_selection(&mut self) -> bool {
        if !self.wet.as_ref().is_some_and(|g| g.use_coverage) {
            return self.ok();
        }
        let all = Rect::new(0, 0, self.width, self.height);
        self.wet_run(Entry::BlendSelection, all, |_| {})
    }

    /// `ImpastoEngine.transferMaterialStroke` over the dabs from [`Engine::wet_set_dabs`]. A
    /// negative `initial_load` continues from the load the previous transfer ended on (a live
    /// stroke's batches carry the reservoir across), which every transfer stores.
    #[allow(clippy::too_many_arguments)]
    pub fn wet_transfer(
        &mut self,
        hardness: f32,
        thickness_rate: f32,
        height_response: f32,
        deposition_rate: f32,
        pickup_rate: f32,
        substrate_response: f32,
        initial_load: f32,
    ) -> bool {
        let count = self.wet.as_ref().map_or(0, |g| g.dab_count);
        let rate = thickness_rate.max(0.0);
        if count == 0 || rate <= 0.0 || (height_response <= 0.0 && pickup_rate <= 0.0) {
            return self.ok();
        }
        // One workgroup: the dispatch region is a single cell.
        self.wet_run(Entry::Transfer, Rect::new(0, 0, 1, 1), |p| {
            p.f = [
                rate,
                hardness,
                height_response,
                deposition_rate,
                pickup_rate,
                substrate_response,
                initial_load,
                0.0,
            ];
        })
    }

    /// `ImpastoEngine.depositWetnessStroke` over the dabs from [`Engine::wet_set_dabs`].
    pub fn wet_deposit(&mut self, hardness: f32, wetness_rate: f32) -> bool {
        let Some(gpu) = self.wet.as_ref() else {
            return false;
        };
        if gpu.dab_count == 0 || wetness_rate.clamp(0.0, 1.0) <= 0.0 {
            return self.ok();
        }
        let region = gpu.dab_region;
        if region.is_empty() {
            return self.ok();
        }
        self.wet_run(Entry::DepositWetness, region, |p| {
            p.f[0] = wetness_rate;
            p.f[1] = hardness;
        })
    }

    /// The control words: each tile's active flag (row-major, nonzero = active), as many zero
    /// words, the reservoir load the last transfer ended on (f32 bits), then one flag word per
    /// current dab ([`WET_FLAG_HEIGHT`], [`WET_FLAG_WETNESS`]). Reads `out.len()` words, at most
    /// the tiles' two sets, the load and the dabs.
    pub fn wet_read_ctl(&mut self, out: &mut [u32]) -> bool {
        let Some(gpu) = self.wet.as_ref() else {
            return false;
        };
        let count = (gpu.tiles as usize * 2 + 1 + gpu.dab_count).min(out.len());
        let src = gpu.ctl.clone();
        let bytes: &mut [u8] = bytemuck::cast_slice_mut(&mut out[..count]);
        self.wet_read_bytes(&src, 0, bytes) && self.ok()
    }

    /// `WetMaterialTransport.advanceArgb` on the raw pigment.
    pub fn wet_pigment(
        &mut self,
        delta_seconds: f32,
        transport_rate: f32,
        iterations: i32,
    ) -> bool {
        let dt = delta_seconds.max(0.0);
        let count = iterations.clamp(0, 4);
        if dt <= 0.0 || transport_rate <= 0.0 || count == 0 {
            return self.ok();
        }
        let iteration_rate = transport_rate.clamp(0.0, 1.0) * dt / count as f32;
        let all = Rect::new(0, 0, self.width, self.height);
        let mut ok = true;
        for _ in 0..count {
            for phase in 0..EDGE_PHASES {
                ok &= self.wet_run(Entry::PigmentPhase, all, |p| {
                    p.phase = phase;
                    p.f[0] = iteration_rate;
                });
            }
        }
        ok
    }

    /// `PersistentWetnessField.advance`. `tile_drying` takes each tile medium's drying rate (the
    /// replay's `dryingRateAt`) instead of `drying_rate`.
    pub fn wet_advance(
        &mut self,
        delta_seconds: f32,
        drying_rate: f32,
        transport_rate: f32,
        tile_drying: bool,
    ) -> bool {
        let dt = delta_seconds.max(0.0);
        if dt <= 0.0 || self.wet.is_none() {
            return self.ok();
        }
        let step = (transport_rate.clamp(0.0, 1.0) * dt).clamp(0.0, 1.0) * 0.25;
        let uniform = ((-drying_rate.clamp(0.0, 1.0) * dt) as f64).exp() as f32;
        let all = Rect::new(0, 0, self.width, self.height);
        let tiles = self.wet.as_ref().map_or(0, |g| g.tiles) as i32;
        self.wet_run(Entry::WetDelta, all, |p| p.f[0] = step)
            && self.wet_run(Entry::WetApply, all, |p| {
                p.mode = u32::from(tile_drying);
                p.f[0] = uniform;
                p.f[1] = dt;
                p.f[2] = DRY_EPSILON;
            })
            && self.wet_run(Entry::WetCommit, Rect::new(0, 0, tiles, 1), |_| {})
    }

    /// `ImpastoEngine.levelWetHeight` over `[x, x + w) x [y, y + h)`.
    pub fn wet_level(
        &mut self,
        delta_seconds: f32,
        iterations: i32,
        x: i32,
        y: i32,
        w: i32,
        h: i32,
    ) -> bool {
        let dt = delta_seconds.max(0.0);
        let count = iterations.clamp(0, 4);
        let region = Rect::new(x, y, w, h).clamp(self.width, self.height);
        if dt <= 0.0 || count == 0 || region.is_empty() {
            return self.ok();
        }
        let mut ok = true;
        for _ in 0..count {
            for phase in 0..EDGE_PHASES {
                ok &= self.wet_run(Entry::LevelPhase, region, |p| {
                    p.phase = phase;
                    p.f[0] = dt;
                    p.f[1] = count as f32;
                });
            }
        }
        ok
    }

    /// `ImpastoRegionShader.shadeMaterial` from the raw pigment into the layer over
    /// `[x, x + w) x [y, y + h)`; the rest of the layer is left as it is. `to_display` writes into
    /// the display buffer instead (read with [`Engine::read_display`]), for a live stroke.
    #[allow(clippy::too_many_arguments)]
    pub fn wet_shade(
        &mut self,
        x: i32,
        y: i32,
        w: i32,
        h: i32,
        light_azimuth_deg: f32,
        light_elevation_deg: f32,
        relief_strength: f32,
        use_wetness: bool,
        to_display: bool,
    ) -> bool {
        let region = Rect::new(x, y, w, h).clamp(self.width, self.height);
        if region.is_empty() {
            return self.ok();
        }
        self.mp_drain();
        let azimuth = light_azimuth_deg * LIGHT_DEG_TO_RAD;
        let elevation = light_elevation_deg * LIGHT_DEG_TO_RAD;
        let lx = azimuth.cos() * elevation.cos();
        let ly = azimuth.sin() * elevation.cos();
        let lz = elevation.sin();
        let (hx0, hy0, hz0) = (lx, ly, lz + 1.0);
        let h_inv = 1.0 / (hx0 * hx0 + hy0 * hy0 + hz0 * hz0).sqrt();
        // Into the display buffer (shared with live Impasto v1), the layer staying the raw paint.
        let display = to_display.then(|| {
            let gpu = self.take_impasto();
            let buffer = gpu.display.clone();
            self.impasto = Some(gpu);
            buffer
        });
        let target = display.unwrap_or_else(|| self.layer.clone());
        let ok = self.wet_run_into(Entry::ShadeMaterial, region, Some(target), |p| {
            p.use_wet = u32::from(use_wetness);
            p.f = [
                lx,
                ly,
                lz,
                relief_strength,
                hx0 * h_inv,
                hy0 * h_inv,
                hz0 * h_inv,
                0.0,
            ];
        });
        if !to_display {
            self.note_write(region);
        }
        ok
    }

    /// Reads the material state back: raw pigment, height, wetness (each one value per pixel).
    pub fn wet_read(&mut self, raw: &mut [u32], height: &mut [f32], wetness: &mut [f32]) -> bool {
        let n = self.n_pixels();
        let Some(gpu) = self.wet.as_ref() else {
            return false;
        };
        if raw.len() < n || height.len() < n || wetness.len() < n {
            return false;
        }
        let (raw_buf, fields) = (gpu.raw.clone(), gpu.fields.clone());
        let (w, h) = (self.width, self.height);
        let stride = w as usize * 4;
        let all = Rect::new(0, 0, w, h);
        self.read_region_into(
            &raw_buf,
            all,
            bytemuck::cast_slice_mut(&mut raw[..n]),
            stride,
            0,
        ) && self.read_region_into(
            &fields,
            all,
            bytemuck::cast_slice_mut(&mut height[..n]),
            stride,
            0,
        ) && self.read_region_into(
            &fields,
            // The wetness half starts one layer-height down.
            Rect::new(0, h, w, h),
            bytemuck::cast_slice_mut(&mut wetness[..n]),
            stride,
            0,
        ) && self.ok()
    }

    fn wet_run(&mut self, entry: Entry, region: Rect, set: impl FnOnce(&mut Params)) -> bool {
        self.wet_run_into(entry, region, None, set)
    }

    /// [`Engine::wet_run`] with binding 9 on `target` instead of the layer.
    fn wet_run_into(
        &mut self,
        entry: Entry,
        region: Rect,
        target: Option<wgpu::Buffer>,
        set: impl FnOnce(&mut Params),
    ) -> bool {
        let Some(gpu) = self.wet.take() else {
            return false;
        };
        let n = self.n_pixels() as u32;
        let mut params = Params {
            width: self.width,
            height: self.height,
            tile: gpu.tile,
            cols: gpu.cols,
            rx: region.x,
            ry: region.y,
            rw: region.w,
            rh: region.h,
            dab_count: gpu.dab_count as u32,
            use_cov: u32::from(gpu.use_coverage),
            tiles: gpu.tiles,
            flags_off: gpu.tiles * 2 + 1,
            n,
            ..Default::default()
        };
        set(&mut params);
        self.queue
            .write_buffer(&gpu.uniform, 0, bytemuck::bytes_of(&params));
        let Some((_, pipeline, bindings)) = gpu.pipelines.iter().find(|(e, _, _)| *e == entry)
        else {
            self.wet = Some(gpu);
            return false;
        };
        let layout = pipeline.get_bind_group_layout(0);
        let layer = target.unwrap_or_else(|| self.layer.clone());
        let entries: Vec<wgpu::BindGroupEntry> = bindings
            .iter()
            .map(|&b| wgpu::BindGroupEntry {
                binding: b,
                resource: gpu.resource(b, &layer),
            })
            .collect();
        let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("wet"),
            layout: &layout,
            entries: &entries,
        });
        let groups = match entry {
            Entry::Transfer => (1, 1),
            Entry::WetCommit => ((region.w as u32).div_ceil(64), 1),
            _ => (
                (region.w as u32).div_ceil(WORKGROUP),
                (region.h as u32).div_ceil(WORKGROUP),
            ),
        };
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(pipeline);
            pass.set_bind_group(0, &bind, &[]);
            pass.dispatch_workgroups(groups.0.max(1), groups.1.max(1), 1);
        }
        self.queue.submit([encoder.finish()]);
        self.wet = Some(gpu);
        self.ok()
    }

    /// Copies `out.len()` bytes of `src` from `offset` into `out` through the staging buffer.
    fn wet_read_bytes(&mut self, src: &wgpu::Buffer, offset: u64, out: &mut [u8]) -> bool {
        let len = out.len() as u64;
        if len == 0 {
            return true;
        }
        if len > self.staging.size() {
            return false;
        }
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(src, offset, &self.staging, 0, len);
        self.queue.submit([encoder.finish()]);
        let slice = self.staging.slice(0..len);
        let (tx, rx) = std::sync::mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |res| {
            let _ = tx.send(res.is_ok());
        });
        if self
            .device
            .poll(wgpu::PollType::wait_indefinitely())
            .is_err()
            || !matches!(rx.recv(), Ok(true))
        {
            return false;
        }
        let copied = match slice.get_mapped_range() {
            Ok(mapped) => {
                out.copy_from_slice(&mapped[..len as usize]);
                true
            }
            Err(_) => false,
        };
        self.staging.unmap();
        copied
    }
}

/// The union of the dabs' CPU pixel boxes `floor(c - r)..=ceil(c + r)`, clamped to the layer.
fn dab_region(dabs: &[MaterialDab], width: i32, height: i32) -> Rect {
    let mut out: Option<Rect> = None;
    for d in dabs.iter().filter(|d| d.radius > 0.0) {
        let x0 = (d.x - d.radius).floor() as i32;
        let y0 = (d.y - d.radius).floor() as i32;
        let x1 = (d.x + d.radius).ceil() as i32;
        let y1 = (d.y + d.radius).ceil() as i32;
        let r = Rect::new(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        out = Some(out.map_or(r, |o| o.union(r)));
    }
    out.map_or(Rect::default(), |r| r.clamp(width, height))
}

impl Engine {
    /// Color Smudge samples, gates and deposits the wet simulation's canvas wetness
    /// (`ColorSmudgeEngine`'s persistent-wetness path) until turned off. False when turning it on
    /// without a wet simulation ([`Engine::wet_begin`]).
    pub fn set_smudge_wetness(&mut self, on: bool) -> bool {
        if on && self.wet.is_none() {
            return false;
        }
        self.smudge_wet = on;
        true
    }

    pub(super) fn wet_smudge_binding(&self) -> Option<SmudgeWetBinding> {
        self.wet.as_ref().map(|g| SmudgeWetBinding {
            fields: g.fields.clone(),
            ctl: g.ctl.clone(),
            coverage: g.coverage.clone(),
            n: (self.width * self.height) as u32,
            tile: g.tile,
            cols: g.cols,
            use_coverage: g.use_coverage,
        })
    }
}
