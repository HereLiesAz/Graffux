//! The wgpu stamp engine: the same contract as `StampEngine.h` (core/nativebridge), running the
//! WGSL ports of stamp.comp / stamp_masked.comp / color_smudge.comp.
//!
//! Layout and semantics copied from GlesStampEngine.cpp, the simpler of the two C++ engines:
//! the layer is one packed premultiplied RGBA8 word per pixel in a storage buffer (byte order
//! R,G,B,A, identical to an Android ARGB_8888 bitmap's memory), dab lists are uploaded verbatim,
//! each stamp call dispatches only over the dabs' bounding region, and readback copies back only
//! the rectangle dirtied since the last readback.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use bytemuck::{Pod, Zeroable};
use wgpu::util::DeviceExt;

const STAMP_TILE: u32 = 16;
const SMUDGE_TILE: u32 = 8;

/// One dab, binary-identical to `graffux::GpuDab` (16 floats / 64 bytes).
#[repr(C)]
#[derive(Clone, Copy, Debug, Pod, Zeroable, PartialEq)]
pub struct GpuDab {
    pub x: f32,
    pub y: f32,
    pub radius: f32,
    pub alpha: f32,
    pub angle_deg: f32,
    pub color_r: f32,
    pub color_g: f32,
    pub color_b: f32,
    pub color_a: f32,
    pub flow: f32,
    pub resolved: f32,
    /// Masked shader: tip height/width. Round shader: per-dab hardness for resolved dabs.
    pub tip_ratio: f32,
    pub contact_depth: f32,
    pub reservoir_load: f32,
    pub deposition_rate: f32,
    pub substrate_response: f32,
}

impl GpuDab {
    /// A legacy (stroke-colour) dab with the C++ struct's defaults.
    pub fn legacy(x: f32, y: f32, radius: f32, alpha: f32, angle_deg: f32) -> Self {
        GpuDab {
            x,
            y,
            radius,
            alpha,
            angle_deg,
            color_r: 0.0,
            color_g: 0.0,
            color_b: 0.0,
            color_a: 0.0,
            flow: 0.0,
            resolved: 0.0,
            tip_ratio: 1.0,
            contact_depth: 1.0,
            reservoir_load: 1.0,
            deposition_rate: 1.0,
            substrate_response: 0.0,
        }
    }
}

/// Binary-identical to `graffux::GpuSecondaryDab` (8 floats / 32 bytes).
#[repr(C)]
#[derive(Clone, Copy, Debug, Pod, Zeroable, PartialEq)]
pub struct GpuSecondaryDab {
    pub x: f32,
    pub y: f32,
    pub radius: f32,
    pub tip_ratio: f32,
    pub alpha: f32,
    pub angle_deg: f32,
    pub flow_multiplier: f32,
    pub keep_inside: f32,
}

/// Binary-identical to `graffux::ColorSmudgeDab` (11 floats / 44 bytes).
#[repr(C)]
#[derive(Clone, Copy, Debug, Pod, Zeroable, PartialEq)]
pub struct ColorSmudgeDab {
    pub x: f32,
    pub y: f32,
    pub smudge_rate: f32,
    pub color_rate: f32,
    pub opacity: f32,
    pub smudge_radius: f32,
    pub color_rate_multiplier: f32,
    pub distance_delta_px: f32,
    pub base_color_rate: f32,
    pub charge_decay_rate: f32,
    pub pickup_rate: f32,
}

/// `graffux::SubstrateStampParams`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SubstrateParams {
    pub enabled: bool,
    pub has_paint_height: bool,
    pub base_height: f32,
    pub height_scale: f32,
    pub texture_scale: f32,
    pub texture_offset_x: f32,
    pub texture_offset_y: f32,
}

impl Default for SubstrateParams {
    fn default() -> Self {
        SubstrateParams {
            enabled: false,
            has_paint_height: false,
            base_height: 0.0,
            height_scale: 0.0,
            texture_scale: 1.0,
            texture_offset_x: 0.0,
            texture_offset_y: 0.0,
        }
    }
}

/// Everything `StampEngine::stampMaskedDabs` takes beyond the dabs and colour.
#[derive(Clone, Copy, Debug)]
pub struct MaskedParams<'a> {
    pub mask: &'a [u8],
    pub mask_width: u32,
    pub mask_height: u32,
    pub grain: Option<(&'a [u8], u32, u32)>,
    pub grain_canvas_locked: bool,
    pub grain_scale: f32,
    pub grain_phase_x: f32,
    pub grain_phase_y: f32,
    pub secondary_dabs: &'a [GpuSecondaryDab],
    pub secondary_mask: Option<(&'a [u8], u32, u32)>,
    pub substrate: SubstrateParams,
}

/// Which wgpu backend(s) to try. Ids are shared with the C ABI / JNI.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BackendChoice {
    /// Vulkan/Metal/DX12 first, then OpenGL (ES). `WGPU_BACKEND` overrides it.
    Auto = 0,
    Vulkan = 1,
    Gl = 2,
}

impl BackendChoice {
    pub fn from_id(id: i32) -> Self {
        match id {
            1 => BackendChoice::Vulkan,
            2 => BackendChoice::Gl,
            _ => BackendChoice::Auto,
        }
    }
}

/// `graffux::ColorSmudgeBenchmarkInfo`. wgpu has one smudge pipeline (8x8 tiles), so the
/// "benchmark" reports that choice with zero timings.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BenchmarkInfo {
    pub vendor_id: u32,
    pub device_id: u32,
    pub selected_tile_size: u32,
    pub nanos8: u64,
    pub nanos16: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable, Default)]
struct StampUniform {
    dab_count: u32,
    hardness: f32,
    color_r: f32,
    color_g: f32,
    color_b: f32,
    base_alpha: f32,
    origin_x: i32,
    origin_y: i32,
    build_up: f32,
    has_substrate: f32,
    has_paint_height: f32,
    substrate_base_height: f32,
    substrate_height_scale: f32,
    substrate_texture_scale: f32,
    substrate_offset_x: f32,
    substrate_offset_y: f32,
    stroke_max: f32,
    layer_width: i32,
    layer_height: i32,
    _pad: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable, Default)]
struct MaskedUniform {
    dab_count: u32,
    hardness: f32,
    color_r: f32,
    color_g: f32,
    color_b: f32,
    base_alpha: f32,
    origin_x: i32,
    origin_y: i32,
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
    layer_width: i32,
    layer_height: i32,
    _pad0: u32,
    _pad1: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable, Default)]
struct SmudgeUniform {
    phase: i32,
    center_x: i32,
    center_y: i32,
    radius: i32,
    sample_radius: i32,
    smear_alpha: i32,
    origin_x: i32,
    origin_y: i32,
    soft: f32,
    smudge_rate: f32,
    color_rate: f32,
    opacity: f32,
    paint_r: f32,
    paint_g: f32,
    paint_b: f32,
    paint_a: f32,
    dilution: f32,
    has_sample_merged: f32,
    reservoir_enabled: f32,
    base_color_rate: f32,
    charge_decay_rate: f32,
    pickup_rate: f32,
    color_rate_multiplier: f32,
    distance_delta_px: f32,
    layer_width: i32,
    layer_height: i32,
    _pad0: u32,
    _pad1: u32,
}

const _: () = assert!(std::mem::size_of::<GpuDab>() == 64);
const _: () = assert!(std::mem::size_of::<GpuSecondaryDab>() == 32);
const _: () = assert!(std::mem::size_of::<ColorSmudgeDab>() == 44);
const _: () = assert!(std::mem::size_of::<StampUniform>() % 16 == 0);
const _: () = assert!(std::mem::size_of::<MaskedUniform>() % 16 == 0);
const _: () = assert!(std::mem::size_of::<SmudgeUniform>() % 16 == 0);

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct Rect {
    x: i32,
    y: i32,
    w: i32,
    h: i32,
}

impl Rect {
    fn is_empty(&self) -> bool {
        self.w <= 0 || self.h <= 0
    }

    fn union(self, other: Rect) -> Rect {
        if other.is_empty() {
            return self;
        }
        if self.is_empty() {
            return other;
        }
        let x0 = self.x.min(other.x);
        let y0 = self.y.min(other.y);
        let x1 = (self.x + self.w).max(other.x + other.w);
        let y1 = (self.y + self.h).max(other.y + other.h);
        Rect {
            x: x0,
            y: y0,
            w: x1 - x0,
            h: y1 - y0,
        }
    }
}

/// GlesStampEngine.cpp's dabRegion(): the dabs' bounding box, clamped to the layer, +1 on the far
/// edge.
fn dab_region(dabs: &[GpuDab], width: i32, height: i32) -> Rect {
    let r0 = dabs[0].radius.max(0.5);
    let (mut min_x, mut max_x) = (dabs[0].x - r0, dabs[0].x + r0);
    let (mut min_y, mut max_y) = (dabs[0].y - r0, dabs[0].y + r0);
    for d in dabs {
        let r = d.radius.max(0.5);
        min_x = min_x.min(d.x - r);
        max_x = max_x.max(d.x + r);
        min_y = min_y.min(d.y - r);
        max_y = max_y.max(d.y + r);
    }
    // `as i32` saturates (NaN -> 0) exactly where C++'s static_cast would be UB; finite inputs agree.
    let origin_x = 0.max(min_x.floor() as i32);
    let origin_y = 0.max(min_y.floor() as i32);
    let end_x = width.min((max_x.ceil() as i32).saturating_add(1));
    let end_y = height.min((max_y.ceil() as i32).saturating_add(1));
    Rect {
        x: origin_x,
        y: origin_y,
        w: 0.max(end_x - origin_x),
        h: 0.max(end_y - origin_y),
    }
}

fn argb_to_rgba_f(argb: u32) -> [f32; 4] {
    [
        ((argb >> 16) & 0xFF) as f32 / 255.0,
        ((argb >> 8) & 0xFF) as f32 / 255.0,
        (argb & 0xFF) as f32 / 255.0,
        ((argb >> 24) & 0xFF) as f32 / 255.0,
    ]
}

struct Tex {
    texture: wgpu::Texture,
    view: wgpu::TextureView,
    width: u32,
    height: u32,
    hash: u64,
}

fn tv(t: &Tex) -> wgpu::BindingResource<'_> {
    wgpu::BindingResource::TextureView(&t.view)
}

fn fnv1a(bytes: &[u8]) -> u64 {
    let mut h: u64 = 1469598103934665603;
    for b in bytes {
        h ^= *b as u64;
        h = h.wrapping_mul(1099511628211);
    }
    h
}

struct GrowBuffer {
    buffer: wgpu::Buffer,
    capacity: u64,
    usage: wgpu::BufferUsages,
    label: &'static str,
}

impl GrowBuffer {
    fn new(
        device: &wgpu::Device,
        label: &'static str,
        usage: wgpu::BufferUsages,
        capacity: u64,
    ) -> Self {
        let buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: capacity,
            usage,
            mapped_at_creation: false,
        });
        GrowBuffer {
            buffer,
            capacity,
            usage,
            label,
        }
    }

    fn ensure(&mut self, device: &wgpu::Device, bytes: u64) {
        if bytes <= self.capacity {
            return;
        }
        let capacity = bytes.max(self.capacity * 2);
        *self = GrowBuffer::new(device, self.label, self.usage, capacity);
    }
}

/// A live engine: one device, one layer. Not thread-safe by contract (same as `StampEngine`);
/// callers serialize every call on one instance.
pub struct Engine {
    device: wgpu::Device,
    queue: wgpu::Queue,
    adapter_info: wgpu::AdapterInfo,
    error: Arc<AtomicBool>,
    width: i32,
    height: i32,
    layer: wgpu::Buffer,
    staging: wgpu::Buffer,
    stroke_state: Option<wgpu::Buffer>,
    stroke_state_dirty: bool,
    placeholder_state: wgpu::Buffer,
    dab_buffer: GrowBuffer,
    secondary_buffer: GrowBuffer,
    carrier: GrowBuffer,
    smudge_uniforms: GrowBuffer,
    stamp_uniform: wgpu::Buffer,
    masked_uniform: wgpu::Buffer,
    uniform_align: u64,
    substrate_tex: Tex,
    paint_height_tex: Tex,
    mask_tex: Tex,
    grain_tex: Tex,
    secondary_mask_tex: Tex,
    sample_source_tex: Tex,
    linear_clamp: wgpu::Sampler,
    nearest_repeat: wgpu::Sampler,
    stamp_layout: wgpu::BindGroupLayout,
    masked_layout: wgpu::BindGroupLayout,
    smudge_layout: wgpu::BindGroupLayout,
    stamp_pipeline: wgpu::ComputePipeline,
    masked_pipeline: wgpu::ComputePipeline,
    smudge_pipeline: wgpu::ComputePipeline,
    dirty: Rect,
}

fn storage_entry(binding: u32, read_only: bool) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Storage { read_only },
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    }
}

fn uniform_entry(binding: u32, dynamic: bool) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Uniform,
            has_dynamic_offset: dynamic,
            min_binding_size: None,
        },
        count: None,
    }
}

fn texture_entry(binding: u32, filterable: bool) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Texture {
            sample_type: wgpu::TextureSampleType::Float { filterable },
            view_dimension: wgpu::TextureViewDimension::D2,
            multisampled: false,
        },
        count: None,
    }
}

fn sampler_entry(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
        count: None,
    }
}

fn make_pipeline(
    device: &wgpu::Device,
    label: &str,
    source: &str,
    layout: &wgpu::BindGroupLayout,
) -> wgpu::ComputePipeline {
    let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some(label),
        source: wgpu::ShaderSource::Wgsl(source.into()),
    });
    let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some(label),
        bind_group_layouts: &[Some(layout)],
        immediate_size: 0,
    });
    device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
        label: Some(label),
        layout: Some(&pipeline_layout),
        module: &module,
        entry_point: Some("main"),
        compilation_options: Default::default(),
        cache: None,
    })
}

pub const STAMP_WGSL: &str = include_str!("shaders/stamp.wgsl");
pub const STAMP_MASKED_WGSL: &str = include_str!("shaders/stamp_masked.wgsl");
pub const COLOR_SMUDGE_WGSL: &str = include_str!("shaders/color_smudge.wgsl");

impl Engine {
    /// `StampEngine::init`. `None` when there is no usable adapter (no GPU, no compute support,
    /// the layer does not fit the device's limits, or device creation failed): the caller falls
    /// back to its CPU path.
    pub fn new(width: i32, height: i32, backend: BackendChoice) -> Option<Engine> {
        if width <= 0 || height <= 0 {
            return None;
        }
        let requested = match backend {
            BackendChoice::Auto => wgpu::Backends::PRIMARY | wgpu::Backends::GL,
            BackendChoice::Vulkan => wgpu::Backends::VULKAN,
            BackendChoice::Gl => wgpu::Backends::GL,
        };
        let backends = if backend == BackendChoice::Auto {
            wgpu::Backends::from_env().unwrap_or(requested)
        } else {
            requested
        };
        Self::with_backends(width, height, backends)
    }

    /// [`Engine::new`] restricted to an explicit backend set (tests pin Vulkan vs GL with this).
    pub fn with_backends(width: i32, height: i32, backends: wgpu::Backends) -> Option<Engine> {
        if width <= 0 || height <= 0 {
            return None;
        }
        let mut instance_desc = wgpu::InstanceDescriptor::new_without_display_handle();
        instance_desc.backends = backends;
        let instance = wgpu::Instance::new(instance_desc);
        // Prefer a non-GL adapter when several are available (Auto); fall back to anything.
        let adapters = pollster::block_on(instance.enumerate_adapters(backends));
        let mut best: Option<wgpu::Adapter> = None;
        for adapter in adapters {
            let caps = adapter.get_downlevel_capabilities();
            if !caps.flags.contains(wgpu::DownlevelFlags::COMPUTE_SHADERS) {
                continue;
            }
            let rank = |a: &wgpu::Adapter| -> i32 {
                let info = a.get_info();
                let backend_rank = if info.backend == wgpu::Backend::Gl {
                    0
                } else {
                    2
                };
                let type_rank = match info.device_type {
                    wgpu::DeviceType::DiscreteGpu | wgpu::DeviceType::IntegratedGpu => 1,
                    _ => 0,
                };
                backend_rank * 2 + type_rank
            };
            if best.as_ref().map_or(true, |b| rank(&adapter) > rank(b)) {
                best = Some(adapter);
            }
        }
        let adapter = best?;
        let limits = adapter.limits();
        let layer_bytes = width as u64 * height as u64 * 4;
        let state_bytes = layer_bytes * 2;
        if state_bytes > limits.max_storage_buffer_binding_size as u64
            || state_bytes > limits.max_buffer_size
        {
            log::warn!(
                "graffux-wgpu: {width}x{height} exceeds this adapter's storage buffer limits"
            );
            return None;
        }
        if limits.max_compute_invocations_per_workgroup < STAMP_TILE * STAMP_TILE
            || limits.max_storage_buffers_per_shader_stage < 3
        {
            return None;
        }
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("graffux-wgpu"),
            required_features: wgpu::Features::empty(),
            required_limits: limits.clone(),
            ..Default::default()
        }))
        .ok()?;
        let error = Arc::new(AtomicBool::new(false));
        {
            let error = error.clone();
            device.on_uncaptured_error(Arc::new(move |e: wgpu::Error| {
                log::error!("graffux-wgpu: {e}");
                eprintln!("graffux-wgpu: {e}");
                error.store(true, Ordering::SeqCst);
            }));
        }
        Some(Self::build(
            device,
            queue,
            adapter.get_info(),
            error,
            width,
            height,
            &limits,
        ))
    }

    fn build(
        device: wgpu::Device,
        queue: wgpu::Queue,
        adapter_info: wgpu::AdapterInfo,
        error: Arc<AtomicBool>,
        width: i32,
        height: i32,
        limits: &wgpu::Limits,
    ) -> Engine {
        let layer_bytes = width as u64 * height as u64 * 4;
        // Buffers are zero-initialized by wgpu, so a fresh layer is already transparent.
        let layer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("layer"),
            size: layer_bytes,
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        });
        let staging = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("readback"),
            size: layer_bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let placeholder_state = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("stroke-state placeholder"),
            size: 16,
            usage: wgpu::BufferUsages::STORAGE,
            mapped_at_creation: false,
        });
        let storage_dst = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST;
        let uniform_dst = wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST;
        let dab_buffer = GrowBuffer::new(&device, "dabs", storage_dst, 64 * 64);
        let secondary_buffer = GrowBuffer::new(&device, "secondary dabs", storage_dst, 32 * 64);
        let carrier = GrowBuffer::new(&device, "smudge carrier", storage_dst, 16 * 1024);
        let uniform_align = (limits.min_uniform_buffer_offset_alignment as u64).max(256);
        let smudge_uniforms =
            GrowBuffer::new(&device, "smudge params", uniform_dst, uniform_align * 64);
        let stamp_uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("stamp params"),
            size: std::mem::size_of::<StampUniform>() as u64,
            usage: uniform_dst,
            mapped_at_creation: false,
        });
        let masked_uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("masked params"),
            size: std::mem::size_of::<MaskedUniform>() as u64,
            usage: uniform_dst,
            mapped_at_creation: false,
        });

        let r8 = wgpu::TextureFormat::R8Unorm;
        let substrate_tex = Self::make_tex(&device, &queue, "substrate", 1, 1, r8, &[0]);
        let paint_height_tex = Self::make_tex(
            &device,
            &queue,
            "paint height",
            1,
            1,
            wgpu::TextureFormat::R32Float,
            &[0, 0, 0, 0],
        );
        let mask_tex = Self::make_tex(&device, &queue, "tip mask", 1, 1, r8, &[255]);
        let grain_tex = Self::make_tex(&device, &queue, "grain", 1, 1, r8, &[255]);
        let secondary_mask_tex =
            Self::make_tex(&device, &queue, "secondary mask", 1, 1, r8, &[255]);
        let sample_source_tex = Self::make_tex(
            &device,
            &queue,
            "sample source",
            1,
            1,
            wgpu::TextureFormat::Rgba8Unorm,
            &[0, 0, 0, 0],
        );

        let linear_clamp = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("linear clamp"),
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let nearest_repeat = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("nearest repeat"),
            address_mode_u: wgpu::AddressMode::Repeat,
            address_mode_v: wgpu::AddressMode::Repeat,
            mag_filter: wgpu::FilterMode::Nearest,
            min_filter: wgpu::FilterMode::Nearest,
            ..Default::default()
        });

        let stamp_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("stamp"),
            entries: &[
                storage_entry(0, true),
                storage_entry(1, false),
                texture_entry(2, true),
                texture_entry(3, false),
                storage_entry(4, false),
                uniform_entry(5, false),
            ],
        });
        let masked_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("stamp masked"),
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
            ],
        });
        let smudge_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("color smudge"),
            entries: &[
                storage_entry(0, false),
                storage_entry(1, false),
                texture_entry(2, true),
                uniform_entry(3, true),
            ],
        });
        let stamp_pipeline = make_pipeline(&device, "stamp", STAMP_WGSL, &stamp_layout);
        let masked_pipeline =
            make_pipeline(&device, "stamp masked", STAMP_MASKED_WGSL, &masked_layout);
        let smudge_pipeline =
            make_pipeline(&device, "color smudge", COLOR_SMUDGE_WGSL, &smudge_layout);

        Engine {
            device,
            queue,
            adapter_info,
            error,
            width,
            height,
            layer,
            staging,
            stroke_state: None,
            stroke_state_dirty: true,
            placeholder_state,
            dab_buffer,
            secondary_buffer,
            carrier,
            smudge_uniforms,
            stamp_uniform,
            masked_uniform,
            uniform_align,
            substrate_tex,
            paint_height_tex,
            mask_tex,
            grain_tex,
            secondary_mask_tex,
            sample_source_tex,
            linear_clamp,
            nearest_repeat,
            stamp_layout,
            masked_layout,
            smudge_layout,
            stamp_pipeline,
            masked_pipeline,
            smudge_pipeline,
            dirty: Rect {
                x: 0,
                y: 0,
                w: width,
                h: height,
            },
        }
    }

    fn make_tex(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        label: &str,
        width: u32,
        height: u32,
        format: wgpu::TextureFormat,
        data: &[u8],
    ) -> Tex {
        let texture = device.create_texture_with_data(
            queue,
            &wgpu::TextureDescriptor {
                label: Some(label),
                size: wgpu::Extent3d {
                    width,
                    height,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                view_formats: &[],
            },
            wgpu::util::TextureDataOrder::LayerMajor,
            data,
        );
        let view = texture.create_view(&Default::default());
        Tex {
            texture,
            view,
            width,
            height,
            hash: fnv1a(data),
        }
    }

    /// GlesStampEngine::ensureTexture: re-upload only when size or content changed.
    fn ensure_tex(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        tex: &mut Tex,
        label: &str,
        width: u32,
        height: u32,
        format: wgpu::TextureFormat,
        data: &[u8],
    ) {
        let hash = fnv1a(data);
        if tex.width == width && tex.height == height && tex.hash == hash {
            return;
        }
        if tex.width != width || tex.height != height {
            *tex = Self::make_tex(device, queue, label, width, height, format, data);
            return;
        }
        let bpp = format.block_copy_size(None).unwrap_or(1);
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &tex.texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            data,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(width * bpp),
                rows_per_image: Some(height),
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
        );
        tex.hash = hash;
    }

    pub fn width(&self) -> i32 {
        self.width
    }

    pub fn height(&self) -> i32 {
        self.height
    }

    /// "Vulkan: llvmpipe (LLVM ...)" -- which backend and adapter this engine landed on.
    pub fn adapter_description(&self) -> String {
        format!(
            "{:?}: {} ({})",
            self.adapter_info.backend, self.adapter_info.name, self.adapter_info.driver_info
        )
    }

    pub fn backend(&self) -> wgpu::Backend {
        self.adapter_info.backend
    }

    fn ok(&self) -> bool {
        !self.error.load(Ordering::SeqCst)
    }

    fn mark_fully_dirty(&mut self) {
        self.dirty = Rect {
            x: 0,
            y: 0,
            w: self.width,
            h: self.height,
        };
    }

    fn layer_bytes(&self) -> u64 {
        self.width as u64 * self.height as u64 * 4
    }

    /// `StampEngine::clear`: transparent layer, new stroke.
    pub fn clear(&mut self) -> bool {
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.clear_buffer(&self.layer, 0, None);
        self.queue.submit([encoder.finish()]);
        self.mark_fully_dirty();
        self.stroke_state_dirty = true;
        self.ok()
    }

    /// `StampEngine::upload`: premultiplied RGBA8, row-major, exactly width*height*4 bytes (more
    /// is allowed and ignored). Seeds a new stroke.
    pub fn upload(&mut self, rgba: &[u8]) -> bool {
        let bytes = self.layer_bytes() as usize;
        if rgba.len() < bytes {
            return false;
        }
        self.queue.write_buffer(&self.layer, 0, &rgba[..bytes]);
        self.mark_fully_dirty();
        self.stroke_state_dirty = true;
        self.ok()
    }

    /// `StampEngine::uploadSubstrateHeight`: an R8 tooth tile, wrapped (REPEAT) by the shaders.
    pub fn upload_substrate_height(&mut self, height_r8: &[u8], width: i32, height: i32) -> bool {
        if width <= 0 || height <= 0 || height_r8.len() < (width * height) as usize {
            return false;
        }
        let data = &height_r8[..(width * height) as usize];
        Self::ensure_tex(
            &self.device,
            &self.queue,
            &mut self.substrate_tex,
            "substrate",
            width as u32,
            height as u32,
            wgpu::TextureFormat::R8Unorm,
            data,
        );
        self.ok()
    }

    /// `StampEngine::uploadPaintHeight`: one float per layer pixel; must match the layer size.
    pub fn upload_paint_height(&mut self, heights: &[f32], width: i32, height: i32) -> bool {
        if width != self.width || height != self.height || heights.len() < (width * height) as usize
        {
            return false;
        }
        let data: &[u8] = bytemuck::cast_slice(&heights[..(width * height) as usize]);
        Self::ensure_tex(
            &self.device,
            &self.queue,
            &mut self.paint_height_tex,
            "paint height",
            width as u32,
            height as u32,
            wgpu::TextureFormat::R32Float,
            data,
        );
        self.ok()
    }

    fn ensure_stroke_state(&mut self, encoder: &mut wgpu::CommandEncoder) {
        if self.stroke_state.is_none() {
            self.stroke_state = Some(self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("stroke state"),
                size: self.layer_bytes() * 2,
                usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            }));
            self.stroke_state_dirty = true;
        }
        if self.stroke_state_dirty {
            if let Some(buffer) = &self.stroke_state {
                encoder.clear_buffer(buffer, 0, None);
            }
            self.stroke_state_dirty = false;
        }
    }

    /// `StampEngine::stampDabs`. `build_up` = sequential SRC_OVER in submission order; otherwise
    /// each pixel's strongest dab wins, across this call only or -- with `stroke_max` -- across
    /// every call since the last upload()/clear().
    pub fn stamp_dabs(
        &mut self,
        dabs: &[GpuDab],
        color_argb: u32,
        hardness: f32,
        build_up: bool,
        substrate: SubstrateParams,
        stroke_max: bool,
    ) -> bool {
        if dabs.is_empty() {
            return false;
        }
        let use_stroke_max = stroke_max && !build_up;
        let mut encoder = self.device.create_command_encoder(&Default::default());
        if use_stroke_max {
            self.ensure_stroke_state(&mut encoder);
        }
        let dab_bytes: &[u8] = bytemuck::cast_slice(dabs);
        self.dab_buffer.ensure(&self.device, dab_bytes.len() as u64);
        self.queue
            .write_buffer(&self.dab_buffer.buffer, 0, dab_bytes);

        let r = dab_region(dabs, self.width, self.height);
        if !r.is_empty() {
            let [cr, cg, cb, ca] = argb_to_rgba_f(color_argb);
            let pc = StampUniform {
                dab_count: dabs.len() as u32,
                hardness,
                color_r: cr,
                color_g: cg,
                color_b: cb,
                base_alpha: ca,
                origin_x: r.x,
                origin_y: r.y,
                build_up: if build_up { 1.0 } else { 0.0 },
                has_substrate: if substrate.enabled { 1.0 } else { 0.0 },
                has_paint_height: if substrate.has_paint_height { 1.0 } else { 0.0 },
                substrate_base_height: substrate.base_height.clamp(0.0, 1.0),
                substrate_height_scale: substrate.height_scale.clamp(0.0, 1.0),
                substrate_texture_scale: substrate.texture_scale.max(0.05),
                substrate_offset_x: substrate.texture_offset_x,
                substrate_offset_y: substrate.texture_offset_y,
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
            let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("stamp"),
                layout: &self.stamp_layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: self.dab_buffer.buffer.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: self.layer.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: wgpu::BindingResource::TextureView(&self.substrate_tex.view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 3,
                        resource: wgpu::BindingResource::TextureView(&self.paint_height_tex.view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 4,
                        resource: state.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 5,
                        resource: self.stamp_uniform.as_entire_binding(),
                    },
                ],
            });
            {
                let mut pass = encoder.begin_compute_pass(&Default::default());
                pass.set_pipeline(&self.stamp_pipeline);
                pass.set_bind_group(0, &bind, &[]);
                pass.dispatch_workgroups(
                    (r.w as u32).div_ceil(STAMP_TILE),
                    (r.h as u32).div_ceil(STAMP_TILE),
                    1,
                );
            }
        }
        self.queue.submit([encoder.finish()]);
        self.dirty = self.dirty.union(r);
        self.ok()
    }

    /// `StampEngine::stampMaskedDabs`: shaped tips, grain, dual-brush secondary tip, substrate.
    pub fn stamp_masked_dabs(
        &mut self,
        dabs: &[GpuDab],
        color_argb: u32,
        hardness: f32,
        p: &MaskedParams,
    ) -> bool {
        if dabs.is_empty() || p.mask_width == 0 || p.mask_height == 0 {
            return false;
        }
        if p.mask.len() < (p.mask_width * p.mask_height) as usize {
            return false;
        }
        if !p.secondary_dabs.is_empty() && p.secondary_dabs.len() != dabs.len() {
            return false;
        }
        let r8 = wgpu::TextureFormat::R8Unorm;
        let mask = &p.mask[..(p.mask_width * p.mask_height) as usize];
        Self::ensure_tex(
            &self.device,
            &self.queue,
            &mut self.mask_tex,
            "tip mask",
            p.mask_width,
            p.mask_height,
            r8,
            mask,
        );
        let grain = p
            .grain
            .filter(|(g, w, h)| *w > 0 && *h > 0 && g.len() >= (*w * *h) as usize);
        let white = [255u8];
        let (grain_data, gw, gh) = match grain {
            Some((g, w, h)) => (&g[..(w * h) as usize], w, h),
            None => (&white[..], 1, 1),
        };
        Self::ensure_tex(
            &self.device,
            &self.queue,
            &mut self.grain_tex,
            "grain",
            gw,
            gh,
            r8,
            grain_data,
        );
        let secondary = if p.secondary_dabs.is_empty() {
            None
        } else {
            p.secondary_mask
                .filter(|(m, w, h)| *w > 0 && *h > 0 && m.len() >= (*w * *h) as usize)
        };
        let (smask, sw, sh) = match secondary {
            Some((m, w, h)) => (&m[..(w * h) as usize], w, h),
            None => (&white[..], 1, 1),
        };
        Self::ensure_tex(
            &self.device,
            &self.queue,
            &mut self.secondary_mask_tex,
            "secondary mask",
            sw,
            sh,
            r8,
            smask,
        );

        let dab_bytes: &[u8] = bytemuck::cast_slice(dabs);
        self.dab_buffer.ensure(&self.device, dab_bytes.len() as u64);
        self.queue
            .write_buffer(&self.dab_buffer.buffer, 0, dab_bytes);
        let dummy = [GpuSecondaryDab::zeroed()];
        let secondary_dabs: &[GpuSecondaryDab] = if secondary.is_some() {
            p.secondary_dabs
        } else {
            &dummy
        };
        let secondary_bytes: &[u8] = bytemuck::cast_slice(secondary_dabs);
        self.secondary_buffer
            .ensure(&self.device, secondary_bytes.len() as u64);
        self.queue
            .write_buffer(&self.secondary_buffer.buffer, 0, secondary_bytes);

        let r = dab_region(dabs, self.width, self.height);
        let mut encoder = self.device.create_command_encoder(&Default::default());
        if !r.is_empty() {
            let [cr, cg, cb, ca] = argb_to_rgba_f(color_argb);
            let s = p.substrate;
            let pc = MaskedUniform {
                dab_count: dabs.len() as u32,
                hardness,
                color_r: cr,
                color_g: cg,
                color_b: cb,
                base_alpha: ca,
                origin_x: r.x,
                origin_y: r.y,
                grain_canvas_locked: if p.grain_canvas_locked { 1.0 } else { 0.0 },
                grain_scale: if grain.is_some() { p.grain_scale } else { 1.0 },
                grain_phase_x: if grain.is_some() {
                    p.grain_phase_x
                } else {
                    0.0
                },
                grain_phase_y: if grain.is_some() {
                    p.grain_phase_y
                } else {
                    0.0
                },
                has_secondary: if secondary.is_some() { 1.0 } else { 0.0 },
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
            let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("stamp masked"),
                layout: &self.masked_layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: self.dab_buffer.buffer.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: self.layer.as_entire_binding(),
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
                        resource: tv(&self.substrate_tex),
                    },
                    wgpu::BindGroupEntry {
                        binding: 7,
                        resource: tv(&self.paint_height_tex),
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
            {
                let mut pass = encoder.begin_compute_pass(&Default::default());
                pass.set_pipeline(&self.masked_pipeline);
                pass.set_bind_group(0, &bind, &[]);
                pass.dispatch_workgroups(
                    (r.w as u32).div_ceil(STAMP_TILE),
                    (r.h as u32).div_ceil(STAMP_TILE),
                    1,
                );
            }
        }
        self.queue.submit([encoder.finish()]);
        self.dirty = self.dirty.union(r);
        self.ok()
    }

    /// `StampEngine::colorSmudge`: the ordered Color Smudge plan (Smear / Dulling, optional pigment
    /// mixing, finite reservoir pickup, Sample Merged). Needs at least two dabs, like the C++
    /// engines: the first dab only seeds the carrier.
    #[allow(clippy::too_many_arguments)]
    pub fn color_smudge(
        &mut self,
        dabs: &[ColorSmudgeDab],
        mode: i32,
        radius_px: f32,
        feathering: f32,
        smear_alpha: bool,
        paint_color_argb: u32,
        dilution: f32,
        sample_source: Option<(&[u8], i32, i32)>,
    ) -> bool {
        if dabs.len() < 2 {
            return false;
        }
        let sample_source =
            sample_source.filter(|(s, w, h)| *w > 0 && *h > 0 && s.len() >= (*w * *h * 4) as usize);
        let has_sample_merged = sample_source.is_some();
        if let Some((src, w, h)) = sample_source {
            Self::ensure_tex(
                &self.device,
                &self.queue,
                &mut self.sample_source_tex,
                "sample source",
                w as u32,
                h as u32,
                wgpu::TextureFormat::Rgba8Unorm,
                &src[..(w * h * 4) as usize],
            );
        }

        let base_mode = mode & 1; // 0 = Smear, 1 = Dulling
        let pigment_mixing = mode >= 2;
        let reservoir_enabled = dabs[0].pickup_rate > 0.0;
        let radius = 1.max(radius_px as i32);
        let diameter = radius * 2 + 1;
        let carrier_bytes = ((diameter as u64 * diameter as u64) + 64) * 16;
        self.carrier.ensure(&self.device, carrier_bytes);

        let soft = 0.25 + feathering.clamp(0.0, 1.0) * 0.7;
        let [paint_r, paint_g, paint_b, paint_a] = argb_to_rgba_f(paint_color_argb);
        let brush_groups = (diameter as u32).div_ceil(SMUDGE_TILE);
        let (width, height) = (self.width, self.height);
        let make = |phase: i32, d: &ColorSmudgeDab| SmudgeUniform {
            phase,
            center_x: d.x as i32,
            center_y: d.y as i32,
            radius,
            sample_radius: 1.max((radius_px * d.smudge_radius).round() as i32),
            smear_alpha: smear_alpha as i32,
            origin_x: 0,
            origin_y: 0,
            soft,
            smudge_rate: d.smudge_rate.clamp(0.0, 1.0),
            color_rate: d.color_rate.clamp(0.0, 1.0),
            opacity: d.opacity.clamp(0.0, 1.0),
            paint_r,
            paint_g,
            paint_b,
            paint_a,
            dilution: dilution.clamp(0.0, 1.0),
            has_sample_merged: (if has_sample_merged { 1.0 } else { 0.0 })
                + (if pigment_mixing { 2.0 } else { 0.0 }),
            reservoir_enabled: if reservoir_enabled { 1.0 } else { 0.0 },
            base_color_rate: d.base_color_rate,
            charge_decay_rate: d.charge_decay_rate.max(0.0),
            pickup_rate: d.pickup_rate.clamp(0.0, 1.0),
            color_rate_multiplier: d.color_rate_multiplier.max(0.0),
            distance_delta_px: d.distance_delta_px.max(0.0),
            layer_width: width,
            layer_height: height,
            _pad0: 0,
            _pad1: 0,
        };

        // Same ordered plan as GlesStampEngine::runColorSmudgePlan.
        let mut plan: Vec<(SmudgeUniform, u32)> = Vec::new();
        if reservoir_enabled {
            plan.push((make(4, &dabs[0]), 1));
        }
        if base_mode == 0 {
            plan.push((make(0, &dabs[0]), brush_groups));
            for d in &dabs[1..] {
                if reservoir_enabled {
                    plan.push((make(5, d), 1));
                    plan.push((make(7, d), 1));
                }
                plan.push((make(1, d), brush_groups));
                if reservoir_enabled {
                    plan.push((make(6, d), 1));
                }
            }
        } else {
            for d in &dabs[1..] {
                if reservoir_enabled {
                    plan.push((make(5, d), 1));
                }
                plan.push((make(2, d), 1));
                plan.push((make(3, d), brush_groups));
                if reservoir_enabled {
                    plan.push((make(6, d), 1));
                }
            }
        }

        let stride = self.uniform_align;
        let mut staged = vec![0u8; plan.len() * stride as usize];
        for (i, (pc, _)) in plan.iter().enumerate() {
            let at = i * stride as usize;
            staged[at..at + std::mem::size_of::<SmudgeUniform>()]
                .copy_from_slice(bytemuck::bytes_of(pc));
        }
        self.smudge_uniforms
            .ensure(&self.device, staged.len() as u64);
        self.queue
            .write_buffer(&self.smudge_uniforms.buffer, 0, &staged);

        let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("color smudge"),
            layout: &self.smudge_layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: self.layer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: self.carrier.buffer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::TextureView(&self.sample_source_tex.view),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: &self.smudge_uniforms.buffer,
                        offset: 0,
                        size: wgpu::BufferSize::new(std::mem::size_of::<SmudgeUniform>() as u64),
                    }),
                },
            ],
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            // One dispatch per phase; wgpu synchronizes storage writes between dispatches.
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.smudge_pipeline);
            for (i, (_, groups)) in plan.iter().enumerate() {
                pass.set_bind_group(0, &bind, &[(i as u64 * stride) as u32]);
                pass.dispatch_workgroups(*groups, *groups, 1);
            }
        }
        self.queue.submit([encoder.finish()]);
        self.mark_fully_dirty();
        self.ok()
    }

    pub fn color_smudge_benchmark_info(&self) -> BenchmarkInfo {
        BenchmarkInfo {
            vendor_id: self.adapter_info.vendor,
            device_id: self.adapter_info.device,
            selected_tile_size: SMUDGE_TILE,
            nanos8: 0,
            nanos16: 0,
        }
    }

    /// `StampEngine::readback`: copies the rectangle dirtied since the previous readback into
    /// `out` (width*height*4 bytes, premultiplied RGBA8) and leaves every other byte untouched,
    /// exactly like the C++ engines -- callers keep one buffer per stroke and it stays current.
    pub fn readback(&mut self, out: &mut [u8]) -> bool {
        if out.len() < self.layer_bytes() as usize {
            return false;
        }
        if self.dirty.is_empty() {
            return self.ok();
        }
        let row_bytes = self.width as u64 * 4;
        let offset = self.dirty.y as u64 * row_bytes;
        let length = self.dirty.h as u64 * row_bytes;
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(&self.layer, offset, &self.staging, offset, length);
        self.queue.submit([encoder.finish()]);
        let slice = self.staging.slice(offset..offset + length);
        let (tx, rx) = std::sync::mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r.is_ok());
        });
        if self
            .device
            .poll(wgpu::PollType::wait_indefinitely())
            .is_err()
        {
            return false;
        }
        if !matches!(rx.recv(), Ok(true)) {
            return false;
        }
        {
            let Ok(mapped) = slice.get_mapped_range() else {
                self.staging.unmap();
                return false;
            };
            let col = self.dirty.x as usize * 4;
            let copy = self.dirty.w as usize * 4;
            for row in 0..self.dirty.h as usize {
                let src = row * row_bytes as usize + col;
                let dst = offset as usize + row * row_bytes as usize + col;
                out[dst..dst + copy].copy_from_slice(&mapped[src..src + copy]);
            }
        }
        self.staging.unmap();
        self.dirty = Rect::default();
        self.ok()
    }

    /// Whole-layer readback regardless of the dirty rectangle (tests, desktop display refresh).
    pub fn read_all(&mut self) -> Option<Vec<u8>> {
        let mut out = vec![0u8; self.layer_bytes() as usize];
        self.mark_fully_dirty();
        if self.readback(&mut out) {
            Some(out)
        } else {
            None
        }
    }
}
