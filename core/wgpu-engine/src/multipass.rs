//! Multipass rendering (experimental, behind Settings -> "Multipass drying (experimental)", off by
//! default). A child module of `engine`, so it reaches the engine's private state.
//!
//! **Goal.** Full-quality rendering must never hold the user back, on any hardware. Every stamp
//! call is split into a *draft* (the same stamp at reduced resolution, with its feather trimmed) and
//! the *final* dab (exactly today's dispatch). Drafts are a hard guarantee: every readback, and
//! every refinement call, renders all pending drafts first. Finals run only in the time left over,
//! in strict submission order, split into chunks small enough for that time. Full quality arrives
//! whenever the hardware gets to it; nothing waits for it except the few places that need the final
//! layer itself (see [`Engine::flush`]).
//!
//! **Buffers.**
//!
//! * `layer` (the engine's own): the ground truth. Only final work writes it, in canonical order,
//!   so it is byte-identical to the multipass-off result and it is what resident layers and
//!   commits use.
//! * `draft`: `1/scale` resolution, the stroke's draft contributions over transparent, plus a
//!   feather key per texel (see the draft shaders).
//! * `base`: per tile, the layer as it was when the tile last landed.
//! * `display`: what readback returns: `base OVER reveal(upsampled draft)` for tiles still waiting
//!   for final work, easing to the layer when it lands (`display.wgsl`).
//!
//! **Tiles** (32x32): a tile counts the final items still queued over it. When the count reaches
//! zero the final result has landed there: the display snapshots what is on screen, the tile's base
//! becomes the layer, its draft texels are cleared, and the display eases from the snapshot to the
//! layer over `transition_ms` (0 = snap). Until then the draft's feather is revealed progressively,
//! paced by an ETA from measured throughput (`progress.rs`).

use std::ops::Range;
use std::time::Instant;

use super::*;
use crate::draft::{self, draft_dab};
use crate::progress::{AnimParams, Ema, EtaEstimator, TileAnim};
use crate::scheduler::{Item, PassSpec, Scheduler, SchedulerConfig};

/// Display tile size, in layer pixels (a multiple of every draft scale).
pub const TILE: i32 = 32;
const MAX_TILES_PER_DISPATCH: usize = 128;
/// Chunk grid of final work, in pixels: a multiple of every stamp workgroup edge (8 or 16).
const BLOCK: i32 = 16;
/// Workgroup edge of the draft and display shaders (fixed @workgroup_size(16, 16)).
const MP_WG: u32 = 16;
const DRAFT_PASS: usize = 0;
/// Width of the reveal's soft step, in feather-key units.
const REVEAL_BAND: f32 = 0.3;
/// How much the landing ease staggers pixels by how much they change (0 = plain crossfade).
const LANDING_SPREAD: f32 = 0.6;
/// Assumed cost of one refinement unit (a pixel times a dab) before anything is measured, ms.
const DEFAULT_MS_PER_UNIT: f64 = 2e-5;
/// Smallest refinement chunk, in pixel*dab units (one 16x16 block times 2 dabs).
const MIN_CHUNK_UNITS: f64 = 256.0 * 2.0;

/// Settings of the experiment. Passed as floats through the C ABI / JNI, in this order.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MultipassConfig {
    pub enabled: bool,
    /// Requested quality levels (N >= 2). The engine renders two real levels (draft, final) and
    /// keeps the display continuous in between; see the design doc for why more real levels are
    /// not built.
    pub passes: u32,
    /// Where the draft's trimmed edge sits in the feather, clamped to [1/3, 1/2].
    pub edge_fraction: f32,
    /// Landing ease in ms (cosmetic, never a deadline); 0 = snap.
    pub transition_ms: f32,
    /// Aging among refinement passes: a waiting later pass overtakes a fresh earlier one after
    /// this long (see scheduler.rs).
    pub overtake_ms: f32,
    /// Draft resolution divisor (1, 2, 4 or 8) the device tier allows (Kotlin `GpuBudget`); 0 =
    /// 2. The engine's own frame-time measurement may coarsen it further (up to 8), never finer.
    pub draft_scale: u32,
    /// Test/benchmark only: every refinement chunk is repeated into a scratch buffer this many
    /// times in all (1 = off), to prove drafts do not slow down when refinement gets expensive.
    pub refine_ballast: f32,
    /// Frame period hint in ms; 0 = measured from the readback cadence.
    pub frame_ms: f32,
    /// Fraction of each frame's leftover time refinement may use (the tier's thermally scaled
    /// `refinementFraction`), 0..1. 0 still makes minimal progress.
    pub refine_fraction: f32,
    /// Largest refinement chunk edge in pixels (the tier's refinement tile size); 0 = no cap.
    pub max_chunk_px: u32,
}

impl Default for MultipassConfig {
    fn default() -> Self {
        MultipassConfig {
            enabled: false,
            passes: 2,
            edge_fraction: draft::EDGE_FRACTION_DEFAULT,
            transition_ms: 150.0,
            overtake_ms: 50.0,
            draft_scale: 0,
            refine_ballast: 1.0,
            frame_ms: 0.0,
            refine_fraction: 1.0,
            max_chunk_px: 0,
        }
    }
}

impl MultipassConfig {
    pub const FLOATS: usize = 10;

    /// `[enabled, passes, edge_fraction, transition_ms, overtake_ms, draft_scale, refine_ballast,
    /// frame_ms, refine_fraction, max_chunk_px]`; missing trailing values take their defaults.
    pub fn from_floats(v: &[f32]) -> MultipassConfig {
        let d = MultipassConfig::default();
        let at = |i: usize, dflt: f32| v.get(i).copied().filter(|x| x.is_finite()).unwrap_or(dflt);
        MultipassConfig {
            enabled: at(0, 0.0) > 0.5,
            passes: at(1, d.passes as f32).max(2.0) as u32,
            edge_fraction: draft::clamp_edge_fraction(at(2, d.edge_fraction)),
            transition_ms: at(3, d.transition_ms).max(0.0),
            overtake_ms: at(4, d.overtake_ms).max(0.0),
            draft_scale: at(5, 0.0).max(0.0) as u32,
            refine_ballast: at(6, 1.0).max(1.0),
            frame_ms: at(7, 0.0).max(0.0),
            refine_fraction: at(8, 1.0).clamp(0.0, 1.0),
            max_chunk_px: at(9, 0.0).max(0.0) as u32,
        }
    }

    pub fn to_floats(&self) -> [f32; Self::FLOATS] {
        [
            if self.enabled { 1.0 } else { 0.0 },
            self.passes as f32,
            self.edge_fraction,
            self.transition_ms,
            self.overtake_ms,
            self.draft_scale as f32,
            self.refine_ballast,
            self.frame_ms,
            self.refine_fraction,
            self.max_chunk_px as f32,
        ]
    }

    fn sanitized(mut self) -> Self {
        self.passes = self.passes.max(2);
        self.edge_fraction = draft::clamp_edge_fraction(self.edge_fraction);
        self.transition_ms = self.transition_ms.max(0.0);
        self.draft_scale = match self.draft_scale {
            0 => 0,
            1 => 1,
            2 | 3 => 2,
            4..=7 => 4,
            _ => 8,
        };
        self.refine_ballast = self.refine_ballast.max(1.0);
        self.refine_fraction = if self.refine_fraction.is_finite() {
            self.refine_fraction.clamp(0.0, 1.0)
        } else {
            1.0
        };
        self
    }
}

/// Diagnostics (tests, benchmarks, feel reports).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct MultipassStats {
    pub pending_drafts: usize,
    pub pending_refinement: usize,
    pub active_tiles: usize,
    /// Moving averages, ms: draft rendering per frame, readback (present), display composite.
    pub draft_ms: f64,
    pub present_ms: f64,
    pub compose_ms: f64,
    /// Measured refinement throughput (pixel*dab units per GPU ms) and duty cycle.
    pub refine_units_per_ms: f64,
    pub duty: f64,
    /// Longest estimated wait, over active tiles, until their final result lands.
    pub max_eta_ms: f64,
    pub draft_scale: u32,
    pub frame_ms: f64,
    pub drafts_run: u64,
    pub chunks_run: u64,
}

impl MultipassStats {
    pub const DOUBLES: usize = 13;

    pub fn to_array(&self) -> [f64; Self::DOUBLES] {
        [
            self.pending_drafts as f64,
            self.pending_refinement as f64,
            self.active_tiles as f64,
            self.draft_ms,
            self.present_ms,
            self.compose_ms,
            self.refine_units_per_ms,
            self.duty,
            self.max_eta_ms,
            self.draft_scale as f64,
            self.frame_ms,
            self.drafts_run as f64,
            self.chunks_run as f64,
        ]
    }
}

// ---- queued work ------------------------------------------------------------------------------

/// Owned copy of an R8 image a queued call referenced.
#[derive(Clone)]
struct R8 {
    data: Arc<Vec<u8>>,
    width: u32,
    height: u32,
    hash: u64,
}

impl R8 {
    fn new(data: &[u8], width: u32, height: u32) -> R8 {
        let data = data[..(width * height) as usize].to_vec();
        let hash = fnv1a(&data);
        R8 {
            data: Arc::new(data),
            width,
            height,
            hash,
        }
    }
}

struct StampCall {
    dabs: Vec<GpuDab>,
    color: u32,
    hardness: f32,
    build_up: bool,
    substrate: SubstrateParams,
    stroke_max: bool,
}

struct MaskedCall {
    dabs: Vec<GpuDab>,
    color: u32,
    hardness: f32,
    mask: R8,
    grain: Option<R8>,
    grain_canvas_locked: bool,
    grain_scale: f32,
    grain_phase: (f32, f32),
    secondary_dabs: Vec<GpuSecondaryDab>,
    secondary_mask: Option<R8>,
    substrate: SubstrateParams,
}

enum Call {
    Stamp(StampCall),
    Masked(MaskedCall),
}

impl Call {
    fn dab_count(&self) -> usize {
        match self {
            Call::Stamp(c) => c.dabs.len(),
            Call::Masked(c) => c.dabs.len(),
        }
    }

    /// Whether the dab list may be split across dispatches with identical results: sequential
    /// SRC_OVER (build-up, masked) and stroke-wide max-combine are; per-call max-combine is not.
    fn dabs_splittable(&self) -> bool {
        match self {
            Call::Stamp(c) => c.build_up || c.stroke_max,
            Call::Masked(_) => true,
        }
    }

    fn kind(&self) -> usize {
        match self {
            Call::Stamp(_) => 0,
            Call::Masked(_) => 1,
        }
    }
}

/// Position of a final item's execution: 16x16 blocks of its footprint, row by row, and within a
/// block the next dab when the dab list is being split.
#[derive(Clone, Copy, Debug, Default)]
struct Cursor {
    bx: i32,
    by: i32,
    dab: usize,
    /// For an item with an empty footprint: the one (state-clearing) submit is done.
    done_empty: bool,
}

struct Work {
    call: Arc<Call>,
    subst_tex: Arc<Tex>,
    paint_tex: Arc<Tex>,
    /// The call's dab region (`dab_region`) and the pixels its dispatch can write.
    region: Rect,
    footprint: Rect,
    cursor: Cursor,
}

impl Work {
    fn blocks(&self) -> (i32, i32) {
        (
            (self.footprint.w + BLOCK - 1) / BLOCK,
            (self.footprint.h + BLOCK - 1) / BLOCK,
        )
    }

    fn done(&self) -> bool {
        if self.footprint.is_empty() {
            return self.cursor.done_empty;
        }
        self.cursor.by >= self.blocks().1
    }

    /// Next chunk worth at most `max_units` (pixel*dab units); `force` returns at least a minimal
    /// one. Exact by construction: every pixel of the footprint is dispatched once per dab, in dab
    /// order, and pixels are independent ([`next_chunk_generic`]).
    fn next_chunk(&mut self, max_units: f64, force: bool) -> Option<(Rect, Range<usize>)> {
        let n = self.call.dab_count();
        let splittable = self.call.dabs_splittable();
        next_chunk_generic(&mut self.cursor, self.footprint, n, splittable, max_units, force)
    }

    fn remaining_units(&self) -> f64 {
        let n = self.call.dab_count() as f64;
        if self.footprint.is_empty() {
            return 0.0;
        }
        let (bw, bh) = self.blocks();
        let blocks_left =
            ((bh - self.cursor.by) as i64 * bw as i64 - self.cursor.bx as i64).max(0) as f64;
        let px = (BLOCK * BLOCK) as f64;
        (blocks_left * n - self.cursor.dab as f64) * px
    }
}

// ---- GPU uniforms -----------------------------------------------------------------------------

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable, Default)]
struct DraftStampUniform {
    dab_count: u32,
    origin_x: i32,
    origin_y: i32,
    build_up: f32,
    scale: f32,
    has_substrate: f32,
    has_paint_height: f32,
    substrate_base_height: f32,
    substrate_height_scale: f32,
    substrate_texture_scale: f32,
    substrate_offset_x: f32,
    substrate_offset_y: f32,
    draft_width: i32,
    draft_height: i32,
    _pad0: u32,
    _pad1: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable, Default)]
struct DraftMaskedUniform {
    dab_count: u32,
    origin_x: i32,
    origin_y: i32,
    scale: f32,
    grain_canvas_locked: f32,
    grain_scale: f32,
    grain_phase_x: f32,
    grain_phase_y: f32,
    has_secondary: f32,
    has_substrate: f32,
    has_paint_height: f32,
    substrate_base_height: f32,
    substrate_height_scale: f32,
    substrate_texture_scale: f32,
    substrate_offset_x: f32,
    substrate_offset_y: f32,
    draft_width: i32,
    draft_height: i32,
    mask_width: f32,
    mask_levels: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable, Default)]
struct TileParam {
    x: i32,
    y: i32,
    slot: i32,
    _pad: i32,
    edge: f32,
    weight: f32,
    _pad1: f32,
    _pad2: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct DisplayUniform {
    layer_width: i32,
    layer_height: i32,
    draft_width: i32,
    draft_height: i32,
    scale: f32,
    tile: i32,
    band: f32,
    spread: f32,
    tiles: [TileParam; MAX_TILES_PER_DISPATCH],
}

const _: () = assert!(std::mem::size_of::<DraftStampUniform>() % 16 == 0);
const _: () = assert!(std::mem::size_of::<DraftMaskedUniform>() % 16 == 0);
const _: () = assert!(std::mem::size_of::<DisplayUniform>() % 16 == 0);

pub const DRAFT_STAMP_WGSL: &str = include_str!("shaders/draft_stamp.wgsl");
pub const DRAFT_MASKED_WGSL: &str = include_str!("shaders/draft_masked.wgsl");
pub const DISPLAY_WGSL: &str = include_str!("shaders/display.wgsl");

/// An R8 texture with a full mip chain (the draft's lower-detail tip mask).
struct MipTex {
    /// Kept alive for `view`.
    _texture: wgpu::Texture,
    view: wgpu::TextureView,
    width: u32,
    levels: u32,
    hash: u64,
}

impl MipTex {
    fn new(device: &wgpu::Device, queue: &wgpu::Queue, img: &R8) -> MipTex {
        let chain = draft::mip_chain(&img.data, img.width, img.height);
        let texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("draft tip mask"),
            size: wgpu::Extent3d {
                width: img.width,
                height: img.height,
                depth_or_array_layers: 1,
            },
            mip_level_count: chain.len() as u32,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::R8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        for (level, (data, w, h)) in chain.iter().enumerate() {
            queue.write_texture(
                wgpu::TexelCopyTextureInfo {
                    texture: &texture,
                    mip_level: level as u32,
                    origin: wgpu::Origin3d::ZERO,
                    aspect: wgpu::TextureAspect::All,
                },
                data,
                wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(*w),
                    rows_per_image: Some(*h),
                },
                wgpu::Extent3d {
                    width: *w,
                    height: *h,
                    depth_or_array_layers: 1,
                },
            );
        }
        let view = texture.create_view(&Default::default());
        MipTex {
            _texture: texture,
            view,
            width: img.width,
            levels: chain.len() as u32,
            hash: img.hash,
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
struct TileState {
    /// Final items still queued over this tile.
    pending: u32,
    anim: TileAnim,
    slot: Option<u32>,
    /// The display holds a composite of the tile's current content at this reveal edge (None: the
    /// content changed since, or it was never composited).
    shown_edge: Option<f32>,
}

/// Reveal-edge change below which a tile is not recomposited (about one 8-bit level of the feather).
const EDGE_EPSILON: f32 = 0.004;

pub(super) struct Multipass {
    cfg: MultipassConfig,
    sched: Scheduler<Work>,
    origin: Instant,
    clock: Option<f64>,

    scale: u32,
    draft_w: i32,
    draft_h: i32,
    display: wgpu::Buffer,
    base: wgpu::Buffer,
    draft: wgpu::Buffer,
    snaps: wgpu::Buffer,
    slot_capacity: u32,
    free_slots: Vec<u32>,
    next_slot: u32,
    ballast: Option<wgpu::Buffer>,

    draft_dabs: GrowBuffer,
    draft_secondary: GrowBuffer,
    draft_stamp_uniform: wgpu::Buffer,
    draft_masked_uniform: wgpu::Buffer,
    display_uniform: wgpu::Buffer,
    draft_mask: Option<MipTex>,
    draft_grain: Tex,
    draft_secondary_mask: Tex,
    linear_mip_clamp: wgpu::Sampler,
    draft_stamp_layout: wgpu::BindGroupLayout,
    draft_masked_layout: wgpu::BindGroupLayout,
    display_layout: wgpu::BindGroupLayout,
    draft_stamp_pipeline: wgpu::ComputePipeline,
    draft_masked_pipeline: wgpu::ComputePipeline,
    display_pipeline: wgpu::ComputePipeline,

    tiles_x: i32,
    tiles_y: i32,
    tiles: Vec<TileState>,

    anim: AnimParams,
    eta: EtaEstimator,
    ms_per_unit: [Ema; 2],
    /// Fixed cost of one chunk's submit and wait with no work, measured (so per-unit estimates are
    /// not inflated by it and chunks do not shrink toward nothing).
    chunk_overhead_ms: Ema,
    draft_ms: Ema,
    present_ms: Ema,
    compose_ms: Ema,
    frame_ms: Ema,
    last_frame_start: Option<f64>,
    refine_busy_since_frame: f64,
    drafts_run: u64,
    chunks_run: u64,
}

fn storage(device: &wgpu::Device, label: &str, size: u64, extra: wgpu::BufferUsages) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some(label),
        size: size.max(16),
        usage: wgpu::BufferUsages::STORAGE
            | wgpu::BufferUsages::COPY_DST
            | wgpu::BufferUsages::COPY_SRC
            | extra,
        mapped_at_creation: false,
    })
}

fn uniform(device: &wgpu::Device, label: &str, size: usize) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some(label),
        size: size as u64,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

fn hash_parts(parts: &[u64]) -> u64 {
    let bytes: &[u8] = bytemuck::cast_slice(parts);
    fnv1a(bytes)
}

fn substrate_bits(s: &SubstrateParams) -> [u64; 4] {
    [
        (s.enabled as u64) | ((s.has_paint_height as u64) << 1),
        ((s.base_height.to_bits() as u64) << 32) | s.height_scale.to_bits() as u64,
        ((s.texture_scale.to_bits() as u64) << 32) | s.texture_offset_x.to_bits() as u64,
        s.texture_offset_y.to_bits() as u64,
    ]
}

impl Multipass {
    fn new(e: &Engine, cfg: MultipassConfig) -> Multipass {
        let device = &e.device;
        let layer_bytes = e.layer_bytes();
        let scale = if cfg.draft_scale == 0 { 2 } else { cfg.draft_scale };
        let (draft_w, draft_h) = draft_dims(e.width, e.height, scale);
        let tiles_x = (e.width + TILE - 1) / TILE;
        let tiles_y = (e.height + TILE - 1) / TILE;
        let slot_bytes = (TILE * TILE * 4) as u64;
        let slot_capacity = 64;

        let draft_stamp_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("draft stamp"),
            entries: &[
                storage_entry(0, true),
                storage_entry(1, false),
                texture_entry(2, true),
                texture_entry(3, false),
                uniform_entry(4, false),
            ],
        });
        let draft_masked_layout =
            device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("draft masked"),
                entries: &[
                    storage_entry(0, true),
                    storage_entry(1, false),
                    texture_entry(2, true),
                    texture_entry(3, true),
                    texture_entry(4, true),
                    storage_entry(5, true),
                    texture_entry(6, true),
                    texture_entry(7, false),
                    uniform_entry(8, false),
                    sampler_entry(9),
                    sampler_entry(10),
                    sampler_entry(11),
                ],
            });
        let display_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("multipass display"),
            entries: &[
                storage_entry(0, false),
                storage_entry(1, true),
                storage_entry(2, true),
                storage_entry(3, true),
                uniform_entry(4, false),
            ],
        });
        let draft_stamp_pipeline =
            make_pipeline(device, "draft stamp", DRAFT_STAMP_WGSL, &draft_stamp_layout, &[]);
        let draft_masked_pipeline =
            make_pipeline(device, "draft masked", DRAFT_MASKED_WGSL, &draft_masked_layout, &[]);
        let display_pipeline =
            make_pipeline(device, "multipass display", DISPLAY_WGSL, &display_layout, &[]);
        let r8 = wgpu::TextureFormat::R8Unorm;
        let storage_dst = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST;

        Multipass {
            sched: Scheduler::new(SchedulerConfig::new(2, cfg.overtake_ms as f64)),
            cfg,
            origin: Instant::now(),
            clock: None,
            scale,
            draft_w,
            draft_h,
            display: storage(device, "multipass display", layer_bytes, wgpu::BufferUsages::empty()),
            base: storage(device, "multipass base", layer_bytes, wgpu::BufferUsages::empty()),
            draft: storage(
                device,
                "multipass draft",
                draft_w as u64 * draft_h as u64 * 8,
                wgpu::BufferUsages::empty(),
            ),
            snaps: storage(
                device,
                "multipass snapshots",
                slot_bytes * slot_capacity as u64,
                wgpu::BufferUsages::empty(),
            ),
            slot_capacity,
            free_slots: Vec::new(),
            next_slot: 0,
            ballast: None,
            draft_dabs: GrowBuffer::new(device, "draft dabs", storage_dst, 64 * 64),
            draft_secondary: GrowBuffer::new(device, "draft secondary dabs", storage_dst, 32 * 64),
            draft_stamp_uniform: uniform(
                device,
                "draft stamp params",
                std::mem::size_of::<DraftStampUniform>(),
            ),
            draft_masked_uniform: uniform(
                device,
                "draft masked params",
                std::mem::size_of::<DraftMaskedUniform>(),
            ),
            display_uniform: uniform(
                device,
                "multipass display params",
                std::mem::size_of::<DisplayUniform>(),
            ),
            draft_mask: None,
            draft_grain: Engine::make_tex(device, &e.queue, "draft grain", 1, 1, r8, &[255]),
            draft_secondary_mask: Engine::make_tex(
                device,
                &e.queue,
                "draft secondary mask",
                1,
                1,
                r8,
                &[255],
            ),
            linear_mip_clamp: device.create_sampler(&wgpu::SamplerDescriptor {
                label: Some("linear mip clamp"),
                address_mode_u: wgpu::AddressMode::ClampToEdge,
                address_mode_v: wgpu::AddressMode::ClampToEdge,
                mag_filter: wgpu::FilterMode::Linear,
                min_filter: wgpu::FilterMode::Linear,
                mipmap_filter: wgpu::MipmapFilterMode::Linear,
                ..Default::default()
            }),
            draft_stamp_layout,
            draft_masked_layout,
            display_layout,
            draft_stamp_pipeline,
            draft_masked_pipeline,
            display_pipeline,
            tiles_x,
            tiles_y,
            tiles: vec![TileState::default(); (tiles_x * tiles_y) as usize],
            anim: AnimParams {
                q_cap: 0.8,
                landing_ms: cfg.transition_ms,
            },
            eta: EtaEstimator::default(),
            ms_per_unit: [Ema::default(); 2],
            chunk_overhead_ms: Ema::default(),
            draft_ms: Ema::default(),
            present_ms: Ema::default(),
            compose_ms: Ema::default(),
            frame_ms: Ema::default(),
            last_frame_start: None,
            refine_busy_since_frame: 0.0,
            drafts_run: 0,
            chunks_run: 0,
        }
    }

    /// A displayed frame starts (a readback or a direct present of a new batch): feeds the frame
    /// cadence and the refinement duty cycle the budget and ETA use.
    fn begin_frame(&mut self) {
        let now = self.now();
        if let Some(last) = self.last_frame_start {
            let interval = now - last;
            if interval > 0.0 {
                if interval < 100.0 {
                    self.frame_ms.add(interval, 0.1);
                }
                self.eta
                    .observe_duty(self.refine_busy_since_frame, interval.max(self.frame_period()));
            }
        }
        self.last_frame_start = Some(now);
        self.refine_busy_since_frame = 0.0;
    }

    fn now(&self) -> f64 {
        self.clock
            .unwrap_or_else(|| self.origin.elapsed().as_secs_f64() * 1000.0)
    }

    fn frame_period(&self) -> f64 {
        if self.cfg.frame_ms > 0.0 {
            return self.cfg.frame_ms as f64;
        }
        self.frame_ms.get().unwrap_or(1000.0 / 60.0).clamp(4.0, 50.0)
    }

    /// Tiles `(tx0, ty0, tx1, ty1)` (exclusive ends) overlapping `r`.
    fn tile_span(&self, r: Rect) -> (i32, i32, i32, i32) {
        if r.is_empty() {
            return (0, 0, 0, 0);
        }
        let tx0 = (r.x / TILE).clamp(0, self.tiles_x);
        let ty0 = (r.y / TILE).clamp(0, self.tiles_y);
        let tx1 = ((r.x + r.w + TILE - 1) / TILE).clamp(0, self.tiles_x);
        let ty1 = ((r.y + r.h + TILE - 1) / TILE).clamp(0, self.tiles_y);
        (tx0, ty0, tx1, ty1)
    }

    fn tiles_in(&self, r: Rect) -> impl Iterator<Item = usize> + '_ {
        let (tx0, ty0, tx1, ty1) = self.tile_span(r);
        let tiles_x = self.tiles_x;
        (ty0..ty1).flat_map(move |ty| (tx0..tx1).map(move |tx| (ty * tiles_x + tx) as usize))
    }

    fn tile_rect(&self, t: usize, width: i32, height: i32) -> Rect {
        let tx = t as i32 % self.tiles_x;
        let ty = t as i32 / self.tiles_x;
        Rect::new(tx * TILE, ty * TILE, TILE, TILE).clamp(width, height)
    }

    fn active_tiles(&self) -> usize {
        self.tiles.iter().filter(|t| t.anim.active()).count()
    }

    fn free_slot(&mut self, t: usize) {
        if let Some(s) = self.tiles[t].slot.take() {
            self.free_slots.push(s);
        }
    }
}

fn draft_dims(width: i32, height: i32, scale: u32) -> (i32, i32) {
    let s = scale as i32;
    ((width + s - 1) / s, (height + s - 1) / s)
}

/// Copies rectangle `r` (inside the layer) between two layer-sized buffers.
fn copy_rect(encoder: &mut wgpu::CommandEncoder, src: &wgpu::Buffer, dst: &wgpu::Buffer, r: Rect, width: i32) {
    if r.is_empty() {
        return;
    }
    let row = width as u64 * 4;
    if r.w == width {
        encoder.copy_buffer_to_buffer(src, r.y as u64 * row, dst, r.y as u64 * row, r.h as u64 * row);
        return;
    }
    for y in r.y..r.y + r.h {
        let at = y as u64 * row + r.x as u64 * 4;
        encoder.copy_buffer_to_buffer(src, at, dst, at, r.w as u64 * 4);
    }
}

impl Engine {
    pub(super) fn mp_on(&self) -> bool {
        self.mp.as_ref().is_some_and(|m| m.cfg.enabled)
    }

    /// Runs `f` with the multipass state taken out of the engine (both can then be borrowed
    /// mutably). Only called while multipass is on.
    fn with_mp<R>(&mut self, f: impl FnOnce(&mut Engine, &mut Multipass) -> R) -> R {
        let mut mp = self.mp.take().expect("multipass state");
        let out = f(self, &mut mp);
        self.mp = Some(mp);
        out
    }

    // ---- public API ---------------------------------------------------------------------------

    /// Turns multipass rendering on or off (`cfg.enabled`) and sets its parameters. Turning it off
    /// first lands everything queued, so the layer and the display are the final result; from then
    /// on every call takes exactly the pre-multipass path. False if the display buffers cannot be
    /// allocated (multipass stays off).
    pub fn set_multipass(&mut self, cfg: MultipassConfig) -> bool {
        let cfg = cfg.sanitized();
        if !cfg.enabled {
            if self.mp_on() {
                self.mp_drain();
                // The display equals the layer now; the caller's readback buffer is current.
            }
            if let Some(mp) = self.mp.as_mut() {
                mp.cfg.enabled = false;
            }
            return self.ok();
        }
        if self.mp.is_none() {
            let layer = self.layer_bytes();
            let limits = self.device.limits();
            if layer > limits.max_storage_buffer_binding_size || layer > limits.max_buffer_size {
                return false;
            }
            let mp = Multipass::new(self, cfg);
            self.mp = Some(Box::new(mp));
        }
        let was_on = self.mp_on();
        {
            let mp = self.mp.as_mut().unwrap();
            let clock = mp.clock;
            mp.cfg = cfg;
            mp.clock = clock;
            mp.anim.landing_ms = cfg.transition_ms;
            if mp.sched.pending() == 0 {
                mp.sched = Scheduler::new(SchedulerConfig::new(2, cfg.overtake_ms as f64));
            }
        }
        if !was_on {
            self.mp_session_started();
        }
        self.ok()
    }

    pub fn multipass_config(&self) -> Option<MultipassConfig> {
        self.mp.as_ref().filter(|m| m.cfg.enabled).map(|m| m.cfg)
    }

    /// Tests only: a fixed clock (ms) for the display animation and ETA instead of wall time.
    pub fn set_multipass_clock(&mut self, now_ms: Option<f64>) {
        if let Some(mp) = self.mp.as_mut() {
            mp.clock = now_ms;
        }
    }

    /// Refinement for up to `budget_ms` (<= 0: what is left of the current frame, measured). Pending
    /// drafts always run first. Returns 1 while work or display animation remains, 0 when idle, -1
    /// on failure (or when multipass is off: 0).
    pub fn refine(&mut self, budget_ms: f32) -> i32 {
        if !self.mp_on() {
            return 0;
        }
        self.with_mp(|e, mp| e.mp_refine(mp, budget_ms as f64));
        if !self.ok() {
            return -1;
        }
        let mp = self.mp.as_ref().unwrap();
        i32::from(mp.sched.pending() > 0 || mp.active_tiles() > 0)
    }

    /// Lands everything queued and finishes every display ease instantly: afterwards the layer is
    /// the final result and a readback shows exactly it. Blocks for the queued work. Needed only where
    /// the GPU layer itself is used (a GPU commit, `read_region`, turning multipass off).
    pub fn flush(&mut self) -> bool {
        self.mp_drain();
        self.ok()
    }

    pub fn multipass_stats(&self) -> MultipassStats {
        let Some(mp) = self.mp.as_ref() else {
            return MultipassStats::default();
        };
        let now = mp.now();
        let mut eta_max: f64 = 0.0;
        let units = mp.backlog_units_per_tile();
        for (t, tile) in mp.tiles.iter().enumerate() {
            if tile.anim.active() && tile.pending > 0 {
                eta_max = eta_max.max(mp.eta.eta_ms(units[t]));
            }
        }
        let _ = now;
        MultipassStats {
            pending_drafts: mp.sched.pending_in(DRAFT_PASS),
            pending_refinement: mp.sched.pending_refinement(),
            active_tiles: mp.active_tiles(),
            draft_ms: mp.draft_ms.get().unwrap_or(0.0),
            present_ms: mp.present_ms.get().unwrap_or(0.0),
            compose_ms: mp.compose_ms.get().unwrap_or(0.0),
            refine_units_per_ms: mp.eta.throughput().unwrap_or(0.0),
            duty: mp.eta.duty().unwrap_or(0.0),
            max_eta_ms: eta_max,
            draft_scale: mp.scale,
            frame_ms: mp.frame_period(),
            drafts_run: mp.drafts_run,
            chunks_run: mp.chunks_run,
        }
    }

    /// The displayed quality parameter of the tile containing layer pixel (x, y), 0..1 (1 = the
    /// final result is on screen, or nothing is pending there). Tests and diagnostics.
    pub fn multipass_tile_quality(&self, x: i32, y: i32) -> f32 {
        let Some(mp) = self.mp.as_ref() else { return 1.0 };
        if x < 0 || y < 0 || x >= self.width || y >= self.height {
            return 1.0;
        }
        let t = ((y / TILE) * mp.tiles_x + x / TILE) as usize;
        mp.tiles[t].anim.quality(mp.now(), &mp.anim)
    }

    /// Estimated wait until the final result for the tile containing (x, y) lands, in frames of the
    /// measured frame period (0 when nothing is pending there).
    pub fn multipass_tile_eta_frames(&self, x: i32, y: i32) -> f64 {
        let Some(mp) = self.mp.as_ref() else { return 0.0 };
        if x < 0 || y < 0 || x >= self.width || y >= self.height {
            return 0.0;
        }
        let t = ((y / TILE) * mp.tiles_x + x / TILE) as usize;
        if mp.tiles[t].pending == 0 {
            return 0.0;
        }
        let units = mp.backlog_units_per_tile();
        mp.eta.eta_frames(units[t], mp.frame_period())
    }

    // ---- submission ---------------------------------------------------------------------------

    pub(super) fn mp_submit_stamp(
        &mut self,
        dabs: &[GpuDab],
        color_argb: u32,
        hardness: f32,
        build_up: bool,
        substrate: SubstrateParams,
        stroke_max: bool,
    ) -> bool {
        let call = Call::Stamp(StampCall {
            dabs: dabs.to_vec(),
            color: color_argb,
            hardness,
            build_up,
            substrate,
            stroke_max,
        });
        let r = dab_region(dabs, self.width, self.height);
        let mut compat = vec![0u64, build_up as u64];
        compat.extend_from_slice(&substrate_bits(&substrate));
        compat.push(Arc::as_ptr(&self.substrate_tex) as u64);
        compat.push(Arc::as_ptr(&self.paint_height_tex) as u64);
        self.mp_submit(call, r, hash_parts(&compat))
    }

    pub(super) fn mp_submit_masked(
        &mut self,
        dabs: &[GpuDab],
        color_argb: u32,
        hardness: f32,
        p: &MaskedParams,
    ) -> bool {
        let r8 = |img: Option<(&[u8], u32, u32)>| {
            img.filter(|(d, w, h)| *w > 0 && *h > 0 && d.len() >= (*w * *h) as usize)
                .map(|(d, w, h)| R8::new(d, w, h))
        };
        let mask = R8::new(p.mask, p.mask_width, p.mask_height);
        let grain = r8(p.grain);
        let secondary_mask = if p.secondary_dabs.is_empty() {
            None
        } else {
            r8(p.secondary_mask)
        };
        let s = p.substrate;
        let mut compat = vec![
            1u64,
            mask.hash,
            grain.as_ref().map_or(0, |g| g.hash),
            secondary_mask.as_ref().map_or(0, |g| g.hash),
            p.grain_canvas_locked as u64,
            ((p.grain_scale.to_bits() as u64) << 32) | p.grain_phase_x.to_bits() as u64,
            p.grain_phase_y.to_bits() as u64,
        ];
        compat.extend_from_slice(&substrate_bits(&s));
        compat.push(Arc::as_ptr(&self.substrate_tex) as u64);
        compat.push(Arc::as_ptr(&self.paint_height_tex) as u64);
        let call = Call::Masked(MaskedCall {
            dabs: dabs.to_vec(),
            color: color_argb,
            hardness,
            mask,
            grain,
            grain_canvas_locked: p.grain_canvas_locked,
            grain_scale: p.grain_scale,
            grain_phase: (p.grain_phase_x, p.grain_phase_y),
            secondary_dabs: if secondary_mask.is_some() {
                p.secondary_dabs.to_vec()
            } else {
                Vec::new()
            },
            secondary_mask,
            substrate: s,
        });
        let r = dab_region(dabs, self.width, self.height);
        self.mp_submit(call, r, hash_parts(&compat))
    }

    fn mp_submit(&mut self, call: Call, region: Rect, compat: u64) -> bool {
        let footprint = self.dispatch_footprint(region);
        let call = Arc::new(call);
        let subst_tex = self.substrate_tex.clone();
        let paint_tex = self.paint_height_tex.clone();
        let make = || Work {
            call: call.clone(),
            subst_tex: subst_tex.clone(),
            paint_tex: paint_tex.clone(),
            region,
            footprint,
            cursor: Cursor::default(),
        };
        let draft = make();
        let fin = make();
        let units = fin.remaining_units();
        self.with_mp(|_, mp| {
            let now = mp.now();
            let tiles: Vec<usize> = mp.tiles_in(footprint).collect();
            for t in tiles {
                mp.tiles[t].pending += 1;
            }
            mp.sched.submit(
                now,
                vec![
                    PassSpec {
                        pass: DRAFT_PASS,
                        cost: units,
                        compat: Some(compat),
                        payload: draft,
                    },
                    PassSpec {
                        pass: 1,
                        cost: units,
                        compat: None,
                        payload: fin,
                    },
                ],
            );
        });
        self.ok()
    }

    // ---- sessions -----------------------------------------------------------------------------

    /// The layer the queued work targets is about to be replaced (upload, clear, another layer
    /// bound). Queued final work is dropped: the replacement overwrites what it would have painted.
    /// If it targeted a resident layer, that copy is now missing it, so it is invalidated (the next
    /// bind misses and uploads, which is always correct).
    pub(super) fn mp_session_ending(&mut self) {
        if !self.mp_on() {
            return;
        }
        let had_final = self.mp.as_ref().unwrap().sched.pending_refinement() > 0;
        self.mp_discard_pending();
        if had_final {
            if let Some(key) = self.active_key {
                if let Some(e) = self.residents.get_mut(key) {
                    e.tag = Tag::Invalid;
                }
            }
        }
    }

    /// A new session on the current layer: the display and every tile's base start as the layer.
    pub(super) fn mp_session_started(&mut self) {
        if !self.mp_on() {
            return;
        }
        self.with_mp(|e, mp| {
            // Adapt the draft resolution to what drafts cost on this device (only between strokes,
            // when the draft buffer is empty anyway).
            // The tier's scale is the floor; measured draft cost coarsens it (to 8 at most) when
            // drafts eat too much of the frame, and relaxes back toward the floor when cheap.
            let floor = if mp.cfg.draft_scale == 0 { 2 } else { mp.cfg.draft_scale };
            mp.scale = mp.scale.max(floor);
            let period = mp.frame_period();
            if let Some(ms) = mp.draft_ms.get() {
                let wanted = if ms > 0.3 * period && mp.scale < 8 {
                    mp.scale * 2
                } else if ms < 0.05 * period && mp.scale > floor {
                    mp.scale / 2
                } else {
                    mp.scale
                };
                if wanted != mp.scale {
                    mp.scale = wanted;
                    mp.draft_ms = Ema::default();
                }
            }
            let (dw, dh) = draft_dims(e.width, e.height, mp.scale);
            if (dw, dh) != (mp.draft_w, mp.draft_h) {
                mp.draft = storage(&e.device, "multipass draft", dw as u64 * dh as u64 * 8, wgpu::BufferUsages::empty());
                mp.draft_w = dw;
                mp.draft_h = dh;
            }
            for t in 0..mp.tiles.len() {
                mp.free_slot(t);
                mp.tiles[t] = TileState::default();
            }
            let mut encoder = e.device.create_command_encoder(&Default::default());
            encoder.clear_buffer(&mp.draft, 0, None);
            encoder.copy_buffer_to_buffer(&e.layer, 0, &mp.base, 0, e.layer_bytes());
            encoder.copy_buffer_to_buffer(&e.layer, 0, &mp.display, 0, e.layer_bytes());
            e.queue.submit([encoder.finish()]);
        });
    }

    /// Drops everything queued. The display (and each tile's base) is reset to the layer where the
    /// dropped work would have painted. Returns the union of the dropped final work's footprints.
    pub(super) fn mp_discard_pending(&mut self) -> Rect {
        if !self.mp_on() {
            return Rect::default();
        }
        self.with_mp(|e, mp| {
            let mut dropped = Rect::default();
            for item in mp.sched.clear() {
                if item.pass != DRAFT_PASS {
                    dropped = dropped.union(item.payload.footprint);
                }
            }
            let mut reset = Rect::default();
            for t in 0..mp.tiles.len() {
                if mp.tiles[t].anim.active() || mp.tiles[t].pending > 0 {
                    reset = reset.union(mp.tile_rect(t, e.width, e.height));
                    mp.free_slot(t);
                    mp.tiles[t] = TileState::default();
                }
            }
            e.mp_resync(mp, reset);
            dropped
        })
    }

    /// The layer changed directly over `r` (Color Smudge, row restore) with nothing queued: the
    /// display and bases follow it there.
    pub(super) fn mp_synced(&mut self, r: Rect) {
        if !self.mp_on() {
            return;
        }
        let r = r.clamp(self.width, self.height);
        self.with_mp(|e, mp| {
            let mut whole = Rect::default();
            let tiles: Vec<usize> = mp.tiles_in(r).collect();
            for t in tiles {
                mp.free_slot(t);
                mp.tiles[t] = TileState::default();
                whole = whole.union(mp.tile_rect(t, e.width, e.height));
            }
            e.mp_resync(mp, whole);
        });
    }

    /// base = display = layer over `r` (whole tiles), their draft texels cleared, `r` dirty.
    fn mp_resync(&mut self, mp: &mut Multipass, r: Rect) {
        if r.is_empty() {
            return;
        }
        let mut encoder = self.device.create_command_encoder(&Default::default());
        copy_rect(&mut encoder, &self.layer, &mp.base, r, self.width);
        copy_rect(&mut encoder, &self.layer, &mp.display, r, self.width);
        let tiles: Vec<usize> = mp.tiles_in(r).collect();
        for t in tiles {
            clear_draft_tile(&mut encoder, mp, t);
        }
        self.queue.submit([encoder.finish()]);
        self.dirty = self.dirty.union(r);
    }

    /// Everything queued lands now, and every ease finishes instantly.
    pub(super) fn mp_drain(&mut self) {
        if !self.mp_on() {
            return;
        }
        self.with_mp(|e, mp| {
            e.mp_run_drafts(mp);
            while mp.sched.pending_refinement() > 0 {
                e.mp_refine(mp, f64::INFINITY);
                if !e.ok() {
                    // A device error: forget the rest rather than spin.
                    mp.sched.clear();
                    break;
                }
            }
            e.mp_compose(mp, true);
        });
    }

    // ---- drafts ---------------------------------------------------------------------------------

    /// Every pending draft, merged into as few dispatches as their parameters allow. Never
    /// budgeted and never skipped (the draft guarantee).
    fn mp_run_drafts(&mut self, mp: &mut Multipass) {
        let batches = mp.sched.take_drafts();
        if batches.is_empty() {
            return;
        }
        let start = Instant::now();
        let now = mp.now();
        for batch in &batches {
            self.mp_draft_batch(mp, batch);
            for item in batch {
                let tiles: Vec<usize> = mp.tiles_in(item.payload.footprint).collect();
                for t in tiles {
                    mp.tiles[t].anim.on_draft(now);
                    mp.tiles[t].shown_edge = None;
                }
                self.dirty = self.dirty.union(item.payload.footprint);
            }
            mp.drafts_run += batch.len() as u64;
        }
        let _ = self.device.poll(wgpu::PollType::wait_indefinitely());
        let ms = start.elapsed().as_secs_f64() * 1000.0;
        mp.draft_ms.add(ms, 0.2);
    }

    fn mp_draft_batch(&mut self, mp: &mut Multipass, batch: &[Item<Work>]) {
        let scale = mp.scale;
        let mut region = Rect::default();
        for item in batch {
            region = region.union(item.payload.region);
        }
        if region.is_empty() {
            return;
        }
        // Draft texels whose centers can fall inside the region (plus one for widened dabs).
        let s = scale as i32;
        let pad = (draft::min_draft_radius(scale).ceil() as i32) / s + 1;
        let x0 = (region.x / s - pad).max(0);
        let y0 = (region.y / s - pad).max(0);
        let x1 = ((region.x + region.w + s - 1) / s + pad).min(mp.draft_w);
        let y1 = ((region.y + region.h + s - 1) / s + pad).min(mp.draft_h);
        if x1 <= x0 || y1 <= y0 {
            return;
        }
        let groups = (
            ((x1 - x0) as u32).div_ceil(MP_WG),
            ((y1 - y0) as u32).div_ceil(MP_WG),
        );
        let first = &batch[0].payload;
        match first.call.as_ref() {
            Call::Stamp(c0) => {
                let mut dabs = Vec::new();
                for item in batch {
                    if let Call::Stamp(c) = item.payload.call.as_ref() {
                        dabs.extend(
                            c.dabs
                                .iter()
                                .map(|d| draft_dab(d, c.color, c.hardness, scale, false)),
                        );
                    }
                }
                let bytes: &[u8] = bytemuck::cast_slice(&dabs);
                mp.draft_dabs.ensure(&self.device, bytes.len() as u64);
                self.queue.write_buffer(&mp.draft_dabs.buffer, 0, bytes);
                let sp = c0.substrate;
                let u = DraftStampUniform {
                    dab_count: dabs.len() as u32,
                    origin_x: x0,
                    origin_y: y0,
                    build_up: if c0.build_up { 1.0 } else { 0.0 },
                    scale: scale as f32,
                    has_substrate: if sp.enabled { 1.0 } else { 0.0 },
                    has_paint_height: if sp.has_paint_height { 1.0 } else { 0.0 },
                    substrate_base_height: sp.base_height.clamp(0.0, 1.0),
                    substrate_height_scale: sp.height_scale.clamp(0.0, 1.0),
                    substrate_texture_scale: sp.texture_scale.max(0.05),
                    substrate_offset_x: sp.texture_offset_x,
                    substrate_offset_y: sp.texture_offset_y,
                    draft_width: mp.draft_w,
                    draft_height: mp.draft_h,
                    _pad0: 0,
                    _pad1: 0,
                };
                self.queue
                    .write_buffer(&mp.draft_stamp_uniform, 0, bytemuck::bytes_of(&u));
                let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("draft stamp"),
                    layout: &mp.draft_stamp_layout,
                    entries: &[
                        wgpu::BindGroupEntry {
                            binding: 0,
                            resource: mp.draft_dabs.buffer.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 1,
                            resource: mp.draft.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 2,
                            resource: tv(&first.subst_tex),
                        },
                        wgpu::BindGroupEntry {
                            binding: 3,
                            resource: tv(&first.paint_tex),
                        },
                        wgpu::BindGroupEntry {
                            binding: 4,
                            resource: mp.draft_stamp_uniform.as_entire_binding(),
                        },
                    ],
                });
                let mut encoder = self.device.create_command_encoder(&Default::default());
                {
                    let mut pass = encoder.begin_compute_pass(&Default::default());
                    pass.set_pipeline(&mp.draft_stamp_pipeline);
                    pass.set_bind_group(0, &bind, &[]);
                    pass.dispatch_workgroups(groups.0, groups.1, 1);
                }
                self.queue.submit([encoder.finish()]);
            }
            Call::Masked(c0) => {
                let mut dabs = Vec::new();
                let mut secondary = Vec::new();
                for item in batch {
                    if let Call::Masked(c) = item.payload.call.as_ref() {
                        dabs.extend(
                            c.dabs
                                .iter()
                                .map(|d| draft_dab(d, c.color, c.hardness, scale, true)),
                        );
                        if c0.secondary_mask.is_some() {
                            secondary.extend_from_slice(&c.secondary_dabs);
                        }
                    }
                }
                if secondary.is_empty() {
                    secondary.push(GpuSecondaryDab::zeroed());
                }
                if mp.draft_mask.as_ref().is_none_or(|m| m.hash != c0.mask.hash) {
                    mp.draft_mask = Some(MipTex::new(&self.device, &self.queue, &c0.mask));
                }
                let r8f = wgpu::TextureFormat::R8Unorm;
                let white = [255u8];
                let (gd, gw, gh): (&[u8], u32, u32) = match &c0.grain {
                    Some(g) => (&g.data, g.width, g.height),
                    None => (&white, 1, 1),
                };
                Engine::ensure_tex(&self.device, &self.queue, &mut mp.draft_grain, "draft grain", gw, gh, r8f, gd);
                let (sd, sw, sh): (&[u8], u32, u32) = match &c0.secondary_mask {
                    Some(g) => (&g.data, g.width, g.height),
                    None => (&white, 1, 1),
                };
                Engine::ensure_tex(
                    &self.device,
                    &self.queue,
                    &mut mp.draft_secondary_mask,
                    "draft secondary mask",
                    sw,
                    sh,
                    r8f,
                    sd,
                );
                let bytes: &[u8] = bytemuck::cast_slice(&dabs);
                mp.draft_dabs.ensure(&self.device, bytes.len() as u64);
                self.queue.write_buffer(&mp.draft_dabs.buffer, 0, bytes);
                let sbytes: &[u8] = bytemuck::cast_slice(&secondary);
                mp.draft_secondary.ensure(&self.device, sbytes.len() as u64);
                self.queue.write_buffer(&mp.draft_secondary.buffer, 0, sbytes);
                let sp = c0.substrate;
                let has_grain = c0.grain.is_some();
                let mask = mp.draft_mask.as_ref().unwrap();
                let u = DraftMaskedUniform {
                    dab_count: dabs.len() as u32,
                    origin_x: x0,
                    origin_y: y0,
                    scale: scale as f32,
                    grain_canvas_locked: if c0.grain_canvas_locked { 1.0 } else { 0.0 },
                    grain_scale: if has_grain { c0.grain_scale } else { 1.0 },
                    grain_phase_x: if has_grain { c0.grain_phase.0 } else { 0.0 },
                    grain_phase_y: if has_grain { c0.grain_phase.1 } else { 0.0 },
                    has_secondary: if c0.secondary_mask.is_some() { 1.0 } else { 0.0 },
                    has_substrate: if sp.enabled { 1.0 } else { 0.0 },
                    has_paint_height: if sp.has_paint_height { 1.0 } else { 0.0 },
                    substrate_base_height: sp.base_height.clamp(0.0, 1.0),
                    substrate_height_scale: sp.height_scale.clamp(0.0, 1.0),
                    substrate_texture_scale: sp.texture_scale.max(0.05),
                    substrate_offset_x: sp.texture_offset_x,
                    substrate_offset_y: sp.texture_offset_y,
                    draft_width: mp.draft_w,
                    draft_height: mp.draft_h,
                    mask_width: mask.width as f32,
                    mask_levels: mask.levels as f32,
                };
                self.queue
                    .write_buffer(&mp.draft_masked_uniform, 0, bytemuck::bytes_of(&u));
                let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("draft masked"),
                    layout: &mp.draft_masked_layout,
                    entries: &[
                        wgpu::BindGroupEntry {
                            binding: 0,
                            resource: mp.draft_dabs.buffer.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 1,
                            resource: mp.draft.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 2,
                            resource: wgpu::BindingResource::TextureView(&mask.view),
                        },
                        wgpu::BindGroupEntry {
                            binding: 3,
                            resource: tv(&mp.draft_grain),
                        },
                        wgpu::BindGroupEntry {
                            binding: 4,
                            resource: tv(&mp.draft_secondary_mask),
                        },
                        wgpu::BindGroupEntry {
                            binding: 5,
                            resource: mp.draft_secondary.buffer.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 6,
                            resource: tv(&first.subst_tex),
                        },
                        wgpu::BindGroupEntry {
                            binding: 7,
                            resource: tv(&first.paint_tex),
                        },
                        wgpu::BindGroupEntry {
                            binding: 8,
                            resource: mp.draft_masked_uniform.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 9,
                            resource: wgpu::BindingResource::Sampler(&mp.linear_mip_clamp),
                        },
                        wgpu::BindGroupEntry {
                            binding: 10,
                            resource: wgpu::BindingResource::Sampler(&self.nearest_repeat),
                        },
                        wgpu::BindGroupEntry {
                            binding: 11,
                            resource: wgpu::BindingResource::Sampler(&self.linear_clamp),
                        },
                    ],
                });
                let mut encoder = self.device.create_command_encoder(&Default::default());
                {
                    let mut pass = encoder.begin_compute_pass(&Default::default());
                    pass.set_pipeline(&mp.draft_masked_pipeline);
                    pass.set_bind_group(0, &bind, &[]);
                    pass.dispatch_workgroups(groups.0, groups.1, 1);
                }
                self.queue.submit([encoder.finish()]);
            }
        }
    }

    // ---- refinement ---------------------------------------------------------------------------

    /// Drafts first, then final work in canonical order for up to `budget_ms` (<= 0: the rest of
    /// the frame). At least one chunk runs when final work is queued, so it always progresses.
    fn mp_refine(&mut self, mp: &mut Multipass, budget_ms: f64) {
        self.mp_run_drafts(mp);
        if mp.sched.pending_refinement() == 0 {
            return;
        }
        let start = Instant::now();
        let budget = if budget_ms > 0.0 {
            budget_ms
        } else {
            let period = mp.frame_period();
            let since = mp.last_frame_start.map_or(0.0, |t| (mp.now() - t).max(0.0));
            // End before the next batch is due (the frame started a little before its readback),
            // with a margin for estimate error: refinement must never delay a draft.
            ((period - since) * 0.8 - 1.0).max(0.0) * mp.cfg.refine_fraction as f64
        };
        let mut first = true;
        loop {
            let elapsed = start.elapsed().as_secs_f64() * 1000.0;
            if !first && elapsed >= budget {
                break;
            }
            let now = mp.now();
            let Some(pass) = mp.sched.pick_refinement(now) else {
                break;
            };
            let head = mp.sched.head_mut(pass).expect("picked head");
            let kind = head.payload.call.kind();
            let ms_per_unit = mp.ms_per_unit[kind].get().unwrap_or(DEFAULT_MS_PER_UNIT).max(1e-9);
            if mp.chunk_overhead_ms.get().is_none() {
                mp.chunk_overhead_ms.add(self.mp_measure_submit_overhead(), 1.0);
            }
            let overhead = mp.chunk_overhead_ms.get().unwrap_or(0.0);
            // Chunks of at most half what is left (after the fixed per-chunk cost), so one
            // mis-estimated chunk cannot eat the frame; never below a minimum, so fixed costs do not
            // dominate. Forced progress still takes the minimum on a starving budget.
            let mut max_units = if budget.is_infinite() {
                f64::INFINITY
            } else {
                (((budget - elapsed).max(0.0) * 0.5 - overhead).max(0.0) / ms_per_unit)
                    .max(MIN_CHUNK_UNITS)
            };
            if mp.cfg.max_chunk_px > 0 && !budget.is_infinite() {
                let edge = mp.cfg.max_chunk_px as f64;
                max_units = max_units.min(edge * edge * head.payload.call.dab_count() as f64);
            }
            let chunk = if head.payload.footprint.is_empty() {
                None
            } else {
                head.payload.next_chunk(max_units, first)
            };
            if chunk.is_none() && !head.payload.footprint.is_empty() {
                break;
            }
            let t0 = Instant::now();
            let units = self.mp_exec_final(mp, pass, chunk);
            let _ = self.device.poll(wgpu::PollType::wait_indefinitely());
            let ms = t0.elapsed().as_secs_f64() * 1000.0;
            if units > 0.0 {
                let fixed = mp.chunk_overhead_ms.get().unwrap_or(0.0);
                mp.ms_per_unit[kind].add((ms - fixed).max(ms * 0.1) / units, 0.2);
                mp.eta.observe_work(units, ms);
            }
            mp.chunks_run += 1;
            first = false;
            let head = mp.sched.head_mut(pass).expect("head");
            head.started = true;
            head.cost = head.payload.remaining_units();
            if head.payload.done() {
                let item = mp.sched.pop_head(pass).expect("head");
                self.mp_landed(mp, &item.payload);
            }
            if !self.ok() {
                break;
            }
        }
        mp.refine_busy_since_frame += start.elapsed().as_secs_f64() * 1000.0;
    }

    /// Wall time of an empty submit plus wait, ms (median of three).
    fn mp_measure_submit_overhead(&mut self) -> f64 {
        let mut samples = [0.0f64; 3];
        for s in &mut samples {
            let t0 = Instant::now();
            let encoder = self.device.create_command_encoder(&Default::default());
            self.queue.submit([encoder.finish()]);
            let _ = self.device.poll(wgpu::PollType::wait_indefinitely());
            *s = t0.elapsed().as_secs_f64() * 1000.0;
        }
        samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
        samples[1]
    }

    /// One chunk of the head final item of `pass`: exactly the dispatch the call makes with
    /// multipass off, restricted to `chunk`'s pixels and dab range. Returns its work units.
    fn mp_exec_final(&mut self, mp: &mut Multipass, pass: usize, chunk: Option<(Rect, Range<usize>)>) -> f64 {
        let head = mp.sched.head_mut(pass).expect("head");
        let call = head.payload.call.clone();
        let subst_tex = head.payload.subst_tex.clone();
        let paint_tex = head.payload.paint_tex.clone();
        if chunk.is_none() {
            head.payload.cursor.done_empty = true;
        }
        let ballast = mp.cfg.refine_ballast.round().max(1.0) as u32;
        let units = match chunk.as_ref() {
            Some((r, dabs)) => r.w as f64 * r.h as f64 * dabs.len() as f64,
            None => 0.0,
        };
        match call.as_ref() {
            Call::Stamp(c) => {
                let b = (ballast > 1).then(|| mp.ballast_buffer(self));
                self.mp_exec_stamp(c, &subst_tex, &paint_tex, chunk, b.as_ref().map(|b| (b, ballast - 1)));
            }
            Call::Masked(c) => {
                // An empty region: stamp_masked_dabs submits nothing then either.
                if let Some(ch) = chunk {
                    let b = (ballast > 1).then(|| mp.ballast_buffer(self));
                    self.mp_exec_masked(c, &subst_tex, &paint_tex, ch, b.as_ref().map(|b| (b, ballast - 1)));
                }
            }
        }
        // Real work units: the measured time (ballast included) per unit is what sizes the next
        // chunks, so an expensive refinement gets proportionally smaller chunks.
        units
    }

    /// `stamp_dabs`' dispatch for `chunk` (None: an empty region, which only clears strokeMax state
    /// as the direct call would). `ballast` (benchmark only): that many extra copies of the dispatch
    /// into a scratch buffer, in the same submit, never touching strokeMax state.
    fn mp_exec_stamp(
        &mut self,
        c: &StampCall,
        subst_tex: &Tex,
        paint_tex: &Tex,
        chunk: Option<(Rect, Range<usize>)>,
        ballast: Option<(&wgpu::Buffer, u32)>,
    ) {
        let use_stroke_max = c.stroke_max && !c.build_up;
        let mut encoder = self.device.create_command_encoder(&Default::default());
        if use_stroke_max {
            self.ensure_stroke_state(&mut encoder);
        }
        if let Some((r, range)) = chunk.as_ref().filter(|(r, range)| !r.is_empty() && !range.is_empty()) {
            let dabs = &c.dabs[range.clone()];
            let dab_bytes: &[u8] = bytemuck::cast_slice(dabs);
            self.dab_buffer.ensure(&self.device, dab_bytes.len() as u64);
            self.queue.write_buffer(&self.dab_buffer.buffer, 0, dab_bytes);
            let [cr, cg, cb, ca] = argb_to_rgba_f(c.color);
            let s = c.substrate;
            let pc = StampUniform {
                dab_count: dabs.len() as u32,
                hardness: c.hardness,
                color_r: cr,
                color_g: cg,
                color_b: cb,
                base_alpha: ca,
                origin_x: r.x,
                origin_y: r.y,
                build_up: if c.build_up { 1.0 } else { 0.0 },
                has_substrate: if s.enabled { 1.0 } else { 0.0 },
                has_paint_height: if s.has_paint_height { 1.0 } else { 0.0 },
                substrate_base_height: s.base_height.clamp(0.0, 1.0),
                substrate_height_scale: s.height_scale.clamp(0.0, 1.0),
                substrate_texture_scale: s.texture_scale.max(0.05),
                substrate_offset_x: s.texture_offset_x,
                substrate_offset_y: s.texture_offset_y,
                stroke_max: if use_stroke_max { 1.0 } else { 0.0 },
                layer_width: self.width,
                layer_height: self.height,
                _pad: 0,
            };
            self.queue
                .write_buffer(&self.stamp_uniform, 0, bytemuck::bytes_of(&pc));
            let state = if use_stroke_max {
                self.stroke_state.as_ref().unwrap()
            } else {
                &self.placeholder_state
            };
            let make_bind = |target: &wgpu::Buffer, state: &wgpu::Buffer, uniform: &wgpu::Buffer| {
                self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("stamp"),
                    layout: &self.stamp_layout,
                    entries: &[
                        wgpu::BindGroupEntry {
                            binding: 0,
                            resource: self.dab_buffer.buffer.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 1,
                            resource: target.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 2,
                            resource: tv(subst_tex),
                        },
                        wgpu::BindGroupEntry {
                            binding: 3,
                            resource: tv(paint_tex),
                        },
                        wgpu::BindGroupEntry {
                            binding: 4,
                            resource: state.as_entire_binding(),
                        },
                        wgpu::BindGroupEntry {
                            binding: 5,
                            resource: uniform.as_entire_binding(),
                        },
                    ],
                })
            };
            let bind = make_bind(&self.layer, state, &self.stamp_uniform);
            let extra = ballast.map(|(buffer, n)| {
                let scratch_uniform = self.device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                    label: Some("ballast params"),
                    contents: bytemuck::bytes_of(&StampUniform { stroke_max: 0.0, ..pc }),
                    usage: wgpu::BufferUsages::UNIFORM,
                });
                (make_bind(buffer, &self.placeholder_state, &scratch_uniform), n)
            });
            let groups = (
                (r.w as u32).div_ceil(self.stamp_tile),
                (r.h as u32).div_ceil(self.stamp_tile),
            );
            {
                let mut pass = encoder.begin_compute_pass(&Default::default());
                pass.set_pipeline(&self.stamp_pipeline);
                pass.set_bind_group(0, &bind, &[]);
                pass.dispatch_workgroups(groups.0, groups.1, 1);
                if let Some((extra_bind, n)) = extra.as_ref() {
                    pass.set_bind_group(0, extra_bind, &[]);
                    for _ in 0..*n {
                        pass.dispatch_workgroups(groups.0, groups.1, 1);
                    }
                }
            }
        }
        self.queue.submit([encoder.finish()]);
        if let Some((r, _)) = chunk {
            self.note_truth(r);
        }
    }

    /// `stamp_masked_dabs`' dispatch for `chunk` (see [`Engine::mp_exec_stamp`]).
    fn mp_exec_masked(
        &mut self,
        c: &MaskedCall,
        subst_tex: &Tex,
        paint_tex: &Tex,
        chunk: (Rect, Range<usize>),
        ballast: Option<(&wgpu::Buffer, u32)>,
    ) {
        let (r, range) = chunk;
        let r8 = wgpu::TextureFormat::R8Unorm;
        Self::ensure_tex(
            &self.device,
            &self.queue,
            &mut self.mask_tex,
            "tip mask",
            c.mask.width,
            c.mask.height,
            r8,
            &c.mask.data,
        );
        let white = [255u8];
        let (gd, gw, gh): (&[u8], u32, u32) = match &c.grain {
            Some(g) => (&g.data, g.width, g.height),
            None => (&white, 1, 1),
        };
        Self::ensure_tex(&self.device, &self.queue, &mut self.grain_tex, "grain", gw, gh, r8, gd);
        let (sd, sw, sh): (&[u8], u32, u32) = match &c.secondary_mask {
            Some(g) => (&g.data, g.width, g.height),
            None => (&white, 1, 1),
        };
        Self::ensure_tex(
            &self.device,
            &self.queue,
            &mut self.secondary_mask_tex,
            "secondary mask",
            sw,
            sh,
            r8,
            sd,
        );
        if r.is_empty() || range.is_empty() {
            return;
        }
        let dabs = &c.dabs[range.clone()];
        let dab_bytes: &[u8] = bytemuck::cast_slice(dabs);
        self.dab_buffer.ensure(&self.device, dab_bytes.len() as u64);
        self.queue.write_buffer(&self.dab_buffer.buffer, 0, dab_bytes);
        let dummy = [GpuSecondaryDab::zeroed()];
        let has_secondary = c.secondary_mask.is_some();
        let secondary: &[GpuSecondaryDab] = if has_secondary {
            &c.secondary_dabs[range.clone()]
        } else {
            &dummy
        };
        let secondary_bytes: &[u8] = bytemuck::cast_slice(secondary);
        self.secondary_buffer
            .ensure(&self.device, secondary_bytes.len() as u64);
        self.queue
            .write_buffer(&self.secondary_buffer.buffer, 0, secondary_bytes);
        let [cr, cg, cb, ca] = argb_to_rgba_f(c.color);
        let s = c.substrate;
        let has_grain = c.grain.is_some();
        let pc = MaskedUniform {
            dab_count: dabs.len() as u32,
            hardness: c.hardness,
            color_r: cr,
            color_g: cg,
            color_b: cb,
            base_alpha: ca,
            origin_x: r.x,
            origin_y: r.y,
            grain_canvas_locked: if c.grain_canvas_locked { 1.0 } else { 0.0 },
            grain_scale: if has_grain { c.grain_scale } else { 1.0 },
            grain_phase_x: if has_grain { c.grain_phase.0 } else { 0.0 },
            grain_phase_y: if has_grain { c.grain_phase.1 } else { 0.0 },
            has_secondary: if has_secondary { 1.0 } else { 0.0 },
            has_substrate: if s.enabled { 1.0 } else { 0.0 },
            has_paint_height: if s.has_paint_height { 1.0 } else { 0.0 },
            substrate_base_height: s.base_height.clamp(0.0, 1.0),
            substrate_height_scale: s.height_scale.clamp(0.0, 1.0),
            substrate_texture_scale: s.texture_scale.max(0.05),
            substrate_offset_x: s.texture_offset_x,
            substrate_offset_y: s.texture_offset_y,
            layer_width: self.width,
            layer_height: self.height,
            _pad0: 0,
            _pad1: 0,
        };
        self.queue
            .write_buffer(&self.masked_uniform, 0, bytemuck::bytes_of(&pc));
        let make_bind = |target: &wgpu::Buffer| self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("stamp masked"),
            layout: &self.masked_layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: self.dab_buffer.buffer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: target.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: tv(&self.mask_tex),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: tv(&self.grain_tex),
                },
                wgpu::BindGroupEntry {
                    binding: 4,
                    resource: tv(&self.secondary_mask_tex),
                },
                wgpu::BindGroupEntry {
                    binding: 5,
                    resource: self.secondary_buffer.buffer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 6,
                    resource: tv(subst_tex),
                },
                wgpu::BindGroupEntry {
                    binding: 7,
                    resource: tv(paint_tex),
                },
                wgpu::BindGroupEntry {
                    binding: 8,
                    resource: self.masked_uniform.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 9,
                    resource: wgpu::BindingResource::Sampler(&self.linear_clamp),
                },
                wgpu::BindGroupEntry {
                    binding: 10,
                    resource: wgpu::BindingResource::Sampler(&self.nearest_repeat),
                },
            ],
        });
        let bind = make_bind(&self.layer);
        let extra = ballast.map(|(buffer, n)| (make_bind(buffer), n));
        let groups = (
            (r.w as u32).div_ceil(self.stamp_tile),
            (r.h as u32).div_ceil(self.stamp_tile),
        );
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.masked_pipeline);
            pass.set_bind_group(0, &bind, &[]);
            pass.dispatch_workgroups(groups.0, groups.1, 1);
            if let Some((extra_bind, n)) = extra.as_ref() {
                pass.set_bind_group(0, extra_bind, &[]);
                for _ in 0..*n {
                    pass.dispatch_workgroups(groups.0, groups.1, 1);
                }
            }
        }
        self.queue.submit([encoder.finish()]);
        self.note_truth(r);
    }

    /// A final write to the layer: taints/extends the resident copy like `note_write`, but the
    /// displayed image only changes when the tile lands.
    fn note_truth(&mut self, r: Rect) {
        if let Some(key) = self.active_key {
            let session = self.session;
            let (width, height) = (self.width, self.height);
            if let Some(e) = self.residents.get_mut(key) {
                if e.tag != Tag::Invalid {
                    e.tag = Tag::Tainted(session);
                }
                e.touched = e.touched.union(r).clamp(width, height);
            }
        }
    }

    /// A final item finished: tiles with nothing else queued have their final result. Each one's
    /// on-screen image is snapshotted, its base becomes the layer, its draft texels are cleared, and
    /// the display starts easing to it.
    fn mp_landed(&mut self, mp: &mut Multipass, work: &Work) {
        let tiles: Vec<usize> = mp.tiles_in(work.footprint).collect();
        let now = mp.now();
        let mut encoder = self.device.create_command_encoder(&Default::default());
        let mut any = false;
        for t in tiles {
            let tile = &mut mp.tiles[t];
            tile.pending = tile.pending.saturating_sub(1);
            if tile.pending > 0 {
                continue;
            }
            any = true;
            let r = mp.tile_rect(t, self.width, self.height);
            if mp.anim.landing_ms > 0.0 {
                let slot = match mp.tiles[t].slot {
                    Some(s) => s,
                    None => {
                        let s = mp.alloc_slot(self, &mut encoder);
                        mp.tiles[t].slot = Some(s);
                        s
                    }
                };
                // What is on screen now, row by row into the slot (stride TILE).
                let row = self.width as u64 * 4;
                let slot_base = slot as u64 * (TILE * TILE * 4) as u64;
                for y in 0..r.h {
                    encoder.copy_buffer_to_buffer(
                        &mp.display,
                        (r.y + y) as u64 * row + r.x as u64 * 4,
                        &mp.snaps,
                        slot_base + (y * TILE * 4) as u64,
                        r.w as u64 * 4,
                    );
                }
            }
            copy_rect(&mut encoder, &self.layer, &mp.base, r, self.width);
            clear_draft_tile(&mut encoder, mp, t);
            let params = mp.anim;
            mp.tiles[t].anim.land(now, &params);
            mp.tiles[t].shown_edge = None;
        }
        if any {
            self.queue.submit([encoder.finish()]);
        }
    }

    // ---- display --------------------------------------------------------------------------------

    /// Readback with multipass on: drafts, display animation and composite, then the changed
    /// rectangle of the displayed image.
    pub(super) fn mp_readback_rect(&mut self, out: &mut [u8]) -> Option<(i32, i32, i32, i32)> {
        self.with_mp(|e, mp| {
            mp.begin_frame();
            e.mp_run_drafts(mp);
            e.mp_compose(mp, false);
            let t0 = Instant::now();
            let r = e.dirty.clamp(e.width, e.height);
            let result = if r.is_empty() {
                e.dirty = Rect::default();
                Some((0, 0, 0, 0))
            } else {
                let stride = e.width as usize * 4;
                let display = mp.display.clone();
                if e.read_region_into(&display, r, out, stride, (r.y as usize * stride) + r.x as usize * 4) {
                    e.dirty = Rect::default();
                    Some((r.x, r.y, r.w, r.h))
                } else {
                    None
                }
            };
            mp.present_ms.add(t0.elapsed().as_secs_f64() * 1000.0, 0.2);
            result.filter(|_| e.ok())
        })
    }

    /// Direct display (direct.rs): this frame's drafts and display composite, then the display
    /// buffer to present from -- exactly what [`Engine::mp_readback_rect`] would copy, without the
    /// copy. `new_frame`: a new batch was just stamped (measures the frame cadence, like a
    /// readback); false for a re-present of the refinement ease while the pen rests. None when
    /// multipass is off. Never runs refinement, so a draft is never delayed behind it.
    pub(super) fn mp_present_source(&mut self, new_frame: bool) -> Option<wgpu::Buffer> {
        if !self.mp_on() {
            return None;
        }
        Some(self.with_mp(|e, mp| {
            let t0 = Instant::now();
            if new_frame {
                mp.begin_frame();
            }
            e.mp_run_drafts(mp);
            e.mp_compose(mp, false);
            mp.present_ms.add(t0.elapsed().as_secs_f64() * 1000.0, 0.2);
            mp.display.clone()
        }))
    }

    /// Advances every active tile's animation and composites them into the display (one dispatch
    /// per 128 tiles). `finish`: every ease ends now (flush points).
    fn mp_compose(&mut self, mp: &mut Multipass, finish: bool) {
        let now = mp.now();
        let units = mp.backlog_units_per_tile();
        let params = mp.anim;
        let f = mp.cfg.edge_fraction;
        let mut list: Vec<TileParam> = Vec::new();
        for (t, backlog) in units.iter().enumerate() {
            if !mp.tiles[t].anim.active() {
                continue;
            }
            let eta = mp.eta.eta_ms(*backlog);
            let tile = &mut mp.tiles[t];
            if finish {
                if tile.anim.landing.is_some() {
                    tile.anim.landing = Some((now - params.landing_ms as f64 - 1.0, 1.0));
                }
            } else {
                tile.anim.advance(now, eta, &params);
            }
            let q = tile.anim.quality(now, &params);
            let weight = tile.anim.landing_weight(now, &params);
            let edge = f + q * (1.0 + REVEAL_BAND - f);
            // Unchanged on screen: new content, a running ease or a visible reveal step recomposite.
            let unchanged = tile.anim.landing.is_none()
                && tile.shown_edge.is_some_and(|e| (e - edge).abs() < EDGE_EPSILON);
            if unchanged {
                continue;
            }
            tile.shown_edge = Some(edge);
            let r = Rect::new((t as i32 % mp.tiles_x) * TILE, (t as i32 / mp.tiles_x) * TILE, TILE, TILE);
            list.push(TileParam {
                x: r.x,
                y: r.y,
                slot: match (tile.anim.landing, tile.slot) {
                    (Some(_), Some(s)) => s as i32,
                    _ => -1,
                },
                _pad: 0,
                edge,
                weight,
                _pad1: 0.0,
                _pad2: 0.0,
            });
            if tile.anim.finish_landing(now, &params) {
                mp.free_slot(t);
            }
            self.dirty = self.dirty.union(r.clamp(self.width, self.height));
        }
        if list.is_empty() {
            return;
        }
        let start = Instant::now();
        for group in list.chunks(MAX_TILES_PER_DISPATCH) {
            let mut u = DisplayUniform {
                layer_width: self.width,
                layer_height: self.height,
                draft_width: mp.draft_w,
                draft_height: mp.draft_h,
                scale: mp.scale as f32,
                tile: TILE,
                band: REVEAL_BAND,
                spread: LANDING_SPREAD,
                tiles: [TileParam::default(); MAX_TILES_PER_DISPATCH],
            };
            u.tiles[..group.len()].copy_from_slice(group);
            self.queue
                .write_buffer(&mp.display_uniform, 0, bytemuck::bytes_of(&u));
            let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("multipass display"),
                layout: &mp.display_layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: mp.display.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: mp.base.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: mp.draft.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 3,
                        resource: mp.snaps.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 4,
                        resource: mp.display_uniform.as_entire_binding(),
                    },
                ],
            });
            let mut encoder = self.device.create_command_encoder(&Default::default());
            {
                let mut pass = encoder.begin_compute_pass(&Default::default());
                pass.set_pipeline(&mp.display_pipeline);
                pass.set_bind_group(0, &bind, &[]);
                let per = (TILE as u32).div_ceil(MP_WG);
                pass.dispatch_workgroups(per, per, group.len() as u32);
            }
            self.queue.submit([encoder.finish()]);
        }
        let _ = self.device.poll(wgpu::PollType::wait_indefinitely());
        mp.compose_ms.add(start.elapsed().as_secs_f64() * 1000.0, 0.2);
    }
}

fn clear_draft_tile(encoder: &mut wgpu::CommandEncoder, mp: &Multipass, t: usize) {
    let s = mp.scale as i32;
    let tx = t as i32 % mp.tiles_x;
    let ty = t as i32 / mp.tiles_x;
    let x0 = tx * TILE / s;
    let y0 = ty * TILE / s;
    let x1 = ((tx + 1) * TILE / s).min(mp.draft_w);
    let y1 = ((ty + 1) * TILE / s).min(mp.draft_h);
    if x1 <= x0 {
        return;
    }
    for y in y0..y1 {
        let at = (y as u64 * mp.draft_w as u64 + x0 as u64) * 8;
        encoder.clear_buffer(&mp.draft, at, Some((x1 - x0) as u64 * 8));
    }
}

impl Multipass {
    /// Work queued in the final FIFO up to and including the last item over each tile.
    fn backlog_units_per_tile(&self) -> Vec<f64> {
        let mut out = vec![0.0; self.tiles.len()];
        let mut cum = 0.0;
        for item in self.sched.queue(1) {
            cum += item.cost.max(1.0);
            for t in self.tiles_in(item.payload.footprint) {
                out[t] = cum;
            }
        }
        out
    }

    fn alloc_slot(&mut self, e: &Engine, encoder: &mut wgpu::CommandEncoder) -> u32 {
        if let Some(s) = self.free_slots.pop() {
            return s;
        }
        let s = self.next_slot;
        self.next_slot += 1;
        if s >= self.slot_capacity {
            let slot_bytes = (TILE * TILE * 4) as u64;
            let capacity = self.slot_capacity * 2;
            let grown = storage(&e.device, "multipass snapshots", slot_bytes * capacity as u64, wgpu::BufferUsages::empty());
            encoder.copy_buffer_to_buffer(&self.snaps, 0, &grown, 0, slot_bytes * self.slot_capacity as u64);
            self.snaps = grown;
            self.slot_capacity = capacity;
        }
        s
    }

    fn ballast_buffer(&mut self, e: &Engine) -> wgpu::Buffer {
        self.ballast
            .get_or_insert_with(|| storage(&e.device, "refinement ballast", e.layer_bytes(), wgpu::BufferUsages::empty()))
            .clone()
    }
}

/// [`Work::next_chunk`]'s logic on plain values (shared with its unit test).
fn next_chunk_generic(
    cursor: &mut Cursor,
    f: Rect,
    n: usize,
    splittable: bool,
    max_units: f64,
    force: bool,
) -> Option<(Rect, Range<usize>)> {
    let bw = (f.w + BLOCK - 1) / BLOCK;
    let bh = (f.h + BLOCK - 1) / BLOCK;
    let c = *cursor;
    if c.by >= bh {
        return None;
    }
    let block_px = (BLOCK * BLOCK) as f64;
    let rect = |bx: i32, by: i32, nbx: i32, nby: i32| {
        let x = f.x + bx * BLOCK;
        let y = f.y + by * BLOCK;
        Rect::new(x, y, (nbx * BLOCK).min(f.x + f.w - x), (nby * BLOCK).min(f.y + f.h - y))
    };
    if c.bx == 0 && c.dab == 0 {
        let row_units = block_px * bw as f64 * n as f64;
        let rows = ((max_units / row_units).floor() as i32).min(bh - c.by);
        if rows >= 1 {
            cursor.by += rows;
            return Some((rect(0, c.by, bw, rows), 0..n));
        }
    }
    let per_block = block_px * (n - c.dab) as f64;
    // A block part-way through its dabs finishes alone: the blocks after it have not had the
    // earlier dabs yet.
    let run_limit = if c.dab > 0 { 1 } else { bw - c.bx };
    let mut blocks = ((max_units / per_block).floor() as i32).min(run_limit);
    if blocks < 1 && force && !splittable {
        blocks = 1;
    }
    if blocks >= 1 {
        let out = (rect(c.bx, c.by, blocks, 1), c.dab..n);
        cursor.dab = 0;
        cursor.bx += blocks;
        if cursor.bx >= bw {
            cursor.bx = 0;
            cursor.by += 1;
        }
        return Some(out);
    }
    if !splittable {
        return None;
    }
    let mut k = (max_units / block_px).floor() as usize;
    if k == 0 {
        if !force {
            return None;
        }
        k = 1;
    }
    let end = (c.dab + k).min(n);
    let out = (rect(c.bx, c.by, 1, 1), c.dab..end);
    cursor.dab = end;
    if end >= n {
        cursor.dab = 0;
        cursor.bx += 1;
        if cursor.bx >= bw {
            cursor.bx = 0;
            cursor.by += 1;
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Walks a footprint with the cursor logic, returning how often each (pixel block, dab) pair was
    /// covered: must be exactly once each, in dab order per block.
    fn cursor_cover(fw: i32, fh: i32, n: usize, splittable: bool, budget: f64) -> Vec<Vec<usize>> {
        cursor_cover_with(fw, fh, n, splittable, &mut |_| budget)
    }

    fn cursor_cover_with(
        fw: i32,
        fh: i32,
        n: usize,
        splittable: bool,
        budget: &mut dyn FnMut(usize) -> f64,
    ) -> Vec<Vec<usize>> {
        let footprint = Rect::new(3, 5, fw, fh);
        let mut cursor = Cursor::default();
        let (bw, bh) = ((fw + BLOCK - 1) / BLOCK, (fh + BLOCK - 1) / BLOCK);
        let mut seen = vec![Vec::new(); (bw * bh) as usize];
        let mut guard = 0;
        loop {
            guard += 1;
            assert!(guard < 100_000);
            let got = next_chunk_generic(&mut cursor, footprint, n, splittable, budget(guard), true);
            let Some((r, range)) = got else { break };
            let bx0 = (r.x - footprint.x) / BLOCK;
            let by0 = (r.y - footprint.y) / BLOCK;
            let bx1 = bx0 + (r.w + BLOCK - 1) / BLOCK;
            let by1 = by0 + (r.h + BLOCK - 1) / BLOCK;
            for by in by0..by1 {
                for bx in bx0..bx1 {
                    seen[(by * bw + bx) as usize].extend(range.clone());
                }
            }
        }
        seen
    }

    #[test]
    fn chunks_cover_every_block_and_dab_exactly_once_in_order() {
        for (fw, fh) in [(16, 16), (100, 37), (257, 64), (5, 300)] {
            for n in [1usize, 3, 40] {
                for splittable in [false, true] {
                    for budget in [1.0, 300.0, 256.0 * 5.0, 1e5, 1e9] {
                        let seen = cursor_cover(fw, fh, n, splittable, budget);
                        for s in &seen {
                            assert_eq!(s, &(0..n).collect::<Vec<_>>(), "{fw}x{fh} n={n} split={splittable} budget={budget}");
                        }
                    }
                }
            }
        }
    }

    /// Budgets that change between calls (a frame's leftover time varies), including a block left
    /// part-way through its dabs and then a huge budget.
    #[test]
    fn chunks_stay_exact_when_the_budget_changes_between_calls() {
        let mut seed = 7u64;
        for (fw, fh) in [(100, 37), (257, 64), (48, 48)] {
            for n in [3usize, 17] {
                for splittable in [false, true] {
                    let mut next = |_| {
                        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                        [1.0, 300.0, 256.0 * 2.5, 256.0 * 40.0, f64::INFINITY][(seed >> 60) as usize % 5]
                    };
                    let seen = cursor_cover_with(fw, fh, n, splittable, &mut next);
                    for s in &seen {
                        assert_eq!(s, &(0..n).collect::<Vec<_>>(), "{fw}x{fh} n={n} split={splittable}");
                    }
                }
            }
        }
    }

    #[test]
    fn config_round_trips_through_floats() {
        let c = MultipassConfig {
            enabled: true,
            passes: 3,
            edge_fraction: 0.45,
            transition_ms: 90.0,
            overtake_ms: 20.0,
            draft_scale: 4,
            refine_ballast: 10.0,
            frame_ms: 16.0,
            refine_fraction: 0.5,
            max_chunk_px: 256,
        };
        assert_eq!(MultipassConfig::from_floats(&c.to_floats()), c);
        assert_eq!(MultipassConfig::from_floats(&[]), MultipassConfig::default());
        assert_eq!(MultipassConfig::from_floats(&[1.0, 2.0, 0.9]).edge_fraction, 0.5);
    }
}

