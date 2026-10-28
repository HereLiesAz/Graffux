//! Photo adjustment layers on the GPU (`shaders/adjust.wgsl`, `shaders/adjust_blur.wgsl`).
//!
//! An adjustment layer shows the composite of the layers below it, adjusted, times its mask. The
//! editor uploads that composite once ([`Adjuster::set_source`]) and the mask once
//! ([`Adjuster::set_mask`]); a slider move is then one compute pass and one readback
//! ([`Adjuster::render`]), so a drag never touches the pixels on the CPU. The Detail tab's blurred
//! luma depends on the source only, so it is computed once per upload, not per move.
//!
//! The device is the adjuster's own, separate from the stamp engine's: adjustments run beside a
//! live canvas engine and must not share its single-threaded command stream. One adjuster is
//! sized to one composite; the editor keeps one per canvas size.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;

use crate::engine::BackendChoice;

pub const ADJUST_WGSL: &str = include_str!("shaders/adjust.wgsl");
pub const ADJUST_BLUR_WGSL: &str = include_str!("shaders/adjust_blur.wgsl");

/// Floats in a parameter block (`Params` in adjust.wgsl): 16 vec4s. The layout is documented
/// there and in core/engine's `PhotoAdjustParams`, which packs it.
pub const PARAM_FLOATS: usize = 64;
/// Floats in the curves table: master, red, green, blue, 256 entries each.
pub const CURVE_FLOATS: usize = 1024;

/// Index of "detail on" in the parameter block (detail1.y).
const DETAIL_ON: usize = 29;
/// Index of "effects on" (outline or sketch; fx3.z): they read the blurred luma too.
const EFFECTS_ON: usize = 62;
/// Where the adjuster tells the shader the small blur radius (detail1.w).
const SMALL_RADIUS: usize = 31;
/// Where the adjuster tells the shader whether the edge plane exists (fx3.w).
const EDGE_PLANE: usize = 63;

pub struct Adjuster {
    device: wgpu::Device,
    queue: wgpu::Queue,
    width: u32,
    height: u32,
    src: wgpu::Buffer,
    mask: wgpu::Buffer,
    curves: wgpu::Buffer,
    dst: wgpu::Buffer,
    blur: wgpu::Buffer,
    staging: wgpu::Buffer,
    uniform: wgpu::Buffer,
    pipeline: wgpu::ComputePipeline,
    bind: wgpu::BindGroup,
    blur_pipeline: wgpu::ComputePipeline,
    blur_binds: Vec<(wgpu::Buffer, wgpu::BindGroup)>,
    blur_stale: bool,
    /// Whether `blur` has room for the Outline effect's edge plane (`blur[2n..3n]`). Without it
    /// the shader computes the same gradients from the small blur plane in the move pass.
    edge_plane: bool,
    edge_capacity: bool,
    small_radius: u32,
    large_radius: u32,
    error: Arc<AtomicBool>,
    last_nanos: u64,
    adapter_info: String,
}

fn storage(device: &wgpu::Device, label: &str, size: u64, read: bool) -> wgpu::Buffer {
    let mut usage = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST;
    if read {
        usage |= wgpu::BufferUsages::COPY_SRC;
    }
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some(label),
        size: size.max(4),
        usage,
        mapped_at_creation: false,
    })
}

impl Adjuster {
    /// `None` when there is no adapter with compute shaders, or the composite does not fit it.
    pub fn new(width: i32, height: i32, backend: BackendChoice) -> Option<Adjuster> {
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

    /// [`Adjuster::new`] on an explicit backend set (tests pin one with this).
    pub fn with_backends(width: i32, height: i32, backends: wgpu::Backends) -> Option<Adjuster> {
        if width <= 0 || height <= 0 {
            return None;
        }
        let mut instance_desc = wgpu::InstanceDescriptor::new_without_display_handle();
        instance_desc.backends = backends;
        let instance = wgpu::Instance::new(instance_desc);
        let adapters = pollster::block_on(instance.enumerate_adapters(backends));
        // A non-GL adapter first, then a real GPU over a software one: the stamp engine's order.
        let rank = |a: &wgpu::Adapter| {
            let info = a.get_info();
            let backend = if info.backend == wgpu::Backend::Gl { 0 } else { 2 };
            let kind = match info.device_type {
                wgpu::DeviceType::DiscreteGpu | wgpu::DeviceType::IntegratedGpu => 1,
                _ => 0,
            };
            backend * 2 + kind
        };
        let adapter = adapters
            .into_iter()
            .filter(|a| {
                a.get_downlevel_capabilities()
                    .flags
                    .contains(wgpu::DownlevelFlags::COMPUTE_SHADERS)
            })
            .max_by_key(rank)?;
        let limits = adapter.limits();
        let n = width as u64 * height as u64;
        // The largest binding is the two blurred luma planes: 2n floats.
        if n * 8 > limits.max_storage_buffer_binding_size as u64
            || n * 8 > limits.max_buffer_size
            || limits.max_storage_buffers_per_shader_stage < 5
        {
            log::warn!("graffux-wgpu adjust: {width}x{height} exceeds this adapter's limits");
            return None;
        }
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("graffux-adjust"),
            required_limits: limits.clone(),
            ..Default::default()
        }))
        .ok()?;
        let error = Arc::new(AtomicBool::new(false));
        {
            let error = error.clone();
            device.on_uncaptured_error(Arc::new(move |e: wgpu::Error| {
                log::error!("graffux-wgpu adjust: {e}");
                error.store(true, Ordering::SeqCst);
            }));
        }
        let info = adapter.get_info();
        let adapter_info = format!("{:?}: {}", info.backend, info.name);
        Some(Self::build(device, queue, width as u32, height as u32, error, adapter_info))
    }

    fn build(
        device: wgpu::Device,
        queue: wgpu::Queue,
        width: u32,
        height: u32,
        error: Arc<AtomicBool>,
        adapter_info: String,
    ) -> Adjuster {
        let n = width as u64 * height as u64;
        let src = storage(&device, "adjust src", n * 4, false);
        let mask = storage(&device, "adjust mask", n.div_ceil(4) * 4, false);
        let limits = device.limits();
        // Three planes when they fit (small, large, edges); the limit check in `with_backends`
        // only guarantees two.
        let edge_plane = n * 12 <= limits.max_storage_buffer_binding_size as u64
            && n * 12 <= limits.max_buffer_size;
        let curves = storage(&device, "adjust curves", CURVE_FLOATS as u64 * 4, false);
        let dst = storage(&device, "adjust dst", n * 4, true);
        let blur = storage(&device, "adjust blur", if edge_plane { n * 12 } else { n * 8 }, true);
        let work = storage(&device, "adjust blur work", n * 8, false);
        let staging = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("adjust staging"),
            size: (n * 4).max(4),
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("adjust params"),
            size: (PARAM_FLOATS * 4) as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("adjust"),
            source: wgpu::ShaderSource::Wgsl(ADJUST_WGSL.into()),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("adjust"),
            layout: None,
            module: &module,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        let layout = pipeline.get_bind_group_layout(0);
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("adjust"),
            layout: &layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: uniform.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 1, resource: src.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 2, resource: mask.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 3, resource: curves.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 4, resource: dst.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 5, resource: blur.as_entire_binding() },
            ],
        });
        let blur_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("adjust blur"),
            source: wgpu::ShaderSource::Wgsl(ADJUST_BLUR_WGSL.into()),
        });
        let blur_pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("adjust blur"),
            layout: None,
            module: &blur_module,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        // Texture-scale radii: ~1/400 of the long edge for fine detail, ~1/40 for clarity.
        let long = width.max(height);
        let small_radius = (long / 400).clamp(1, 8);
        let large_radius = (long / 40).clamp(4, 64);
        let n32 = n as u32;
        // (mode, radius, in, out): luma; small h, small v; large h, large v; then the Outline
        // effect's gradients of the small plane, when there is room for them.
        let mut passes = vec![
            (0u32, 0u32, 0u32, 0u32),
            (1, small_radius, 0, n32),
            (2, small_radius, n32, 0),
            (1, large_radius, 0, n32),
            (2, large_radius, n32, n32),
        ];
        if edge_plane {
            passes.push((3, small_radius, 0, 2 * n32));
        }
        let blur_layout = blur_pipeline.get_bind_group_layout(0);
        let blur_binds = passes
            .iter()
            .map(|&(mode, radius, input, output)| {
                let params: [u32; 8] = [width, height, radius, mode, input, output, 0, 0];
                let buf = device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("adjust blur params"),
                    size: 32,
                    usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
                    mapped_at_creation: false,
                });
                queue.write_buffer(&buf, 0, bytemuck::cast_slice(&params));
                let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("adjust blur"),
                    layout: &blur_layout,
                    entries: &[
                        wgpu::BindGroupEntry { binding: 0, resource: buf.as_entire_binding() },
                        wgpu::BindGroupEntry { binding: 1, resource: src.as_entire_binding() },
                        wgpu::BindGroupEntry { binding: 2, resource: work.as_entire_binding() },
                        wgpu::BindGroupEntry { binding: 3, resource: blur.as_entire_binding() },
                    ],
                });
                (buf, group)
            })
            .collect();
        let mut adjuster = Adjuster {
            device,
            queue,
            width,
            height,
            src,
            mask,
            curves,
            dst,
            blur,
            staging,
            uniform,
            pipeline,
            bind,
            blur_pipeline,
            blur_binds,
            blur_stale: true,
            edge_plane,
            edge_capacity: edge_plane,
            small_radius,
            large_radius,
            error,
            last_nanos: 0,
            adapter_info,
        };
        adjuster.set_curves(&identity_curves());
        adjuster
    }

    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }

    /// "Vulkan: <adapter>".
    pub fn adapter_info(&self) -> &str {
        &self.adapter_info
    }

    /// The blur radii the Detail tab uses at this size, (small, large) in pixels.
    pub fn blur_radii(&self) -> (u32, u32) {
        (self.small_radius, self.large_radius)
    }

    fn pixels(&self) -> usize {
        self.width as usize * self.height as usize
    }

    fn ok(&self) -> bool {
        !self.error.load(Ordering::SeqCst)
    }

    /// The composite below the layer, premultiplied RGBA8, `width * height * 4` bytes.
    pub fn set_source(&mut self, rgba: &[u8]) -> bool {
        if rgba.len() < self.pixels() * 4 {
            return false;
        }
        self.queue.write_buffer(&self.src, 0, &rgba[..self.pixels() * 4]);
        self.blur_stale = true;
        self.ok()
    }

    /// The layer's mask, one byte per pixel (255 = full effect); the render then multiplies by it
    /// when the parameter block's "mask on" is set.
    pub fn set_mask(&mut self, alpha: &[u8]) -> bool {
        let n = self.pixels();
        if alpha.len() < n {
            return false;
        }
        let mut padded = alpha[..n].to_vec();
        padded.resize(n.div_ceil(4) * 4, 0);
        self.queue.write_buffer(&self.mask, 0, &padded);
        self.ok()
    }

    /// The curves table: master then red, green, blue, 256 outputs in 0..1 each.
    pub fn set_curves(&mut self, table: &[f32]) -> bool {
        if table.len() < CURVE_FLOATS {
            return false;
        }
        self.queue
            .write_buffer(&self.curves, 0, bytemuck::cast_slice(&table[..CURVE_FLOATS]));
        self.ok()
    }

    fn groups(&self) -> (u32, u32) {
        (self.width.div_ceil(8), self.height.div_ceil(8))
    }

    /// Renders with `params` ([`PARAM_FLOATS`] floats) into `out` (`width * height * 4` bytes,
    /// premultiplied RGBA8). The wall time of the GPU work and readback is [`Adjuster::last_nanos`].
    pub fn render(&mut self, params: &[f32], out: &mut [u8]) -> bool {
        let n = self.pixels();
        if params.len() < PARAM_FLOATS || out.len() < n * 4 {
            return false;
        }
        let started = Instant::now();
        let mut block = [0f32; PARAM_FLOATS];
        block.copy_from_slice(&params[..PARAM_FLOATS]);
        block[16] = self.width as f32;
        block[17] = self.height as f32;
        block[SMALL_RADIUS] = self.small_radius as f32;
        block[EDGE_PLANE] = if self.edge_plane { 1.0 } else { 0.0 };
        self.queue.write_buffer(&self.uniform, 0, bytemuck::cast_slice(&block));
        let (gx, gy) = self.groups();
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("adjust") });
        if (block[DETAIL_ON] > 0.5 || block[EFFECTS_ON] > 0.5) && self.blur_stale {
            for (_, group) in &self.blur_binds {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("adjust blur"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipeline);
                pass.set_bind_group(0, group, &[]);
                pass.dispatch_workgroups(gx, gy, 1);
            }
            self.blur_stale = false;
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("adjust"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.bind, &[]);
            pass.dispatch_workgroups(gx, gy, 1);
        }
        let bytes = (n * 4) as u64;
        encoder.copy_buffer_to_buffer(&self.dst, 0, &self.staging, 0, bytes);
        self.queue.submit([encoder.finish()]);
        let slice = self.staging.slice(0..bytes);
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
        if rx.recv() != Ok(true) {
            return false;
        }
        {
            let Ok(view) = slice.get_mapped_range() else {
                self.staging.unmap();
                return false;
            };
            out[..n * 4].copy_from_slice(&view);
        }
        self.staging.unmap();
        self.last_nanos = started.elapsed().as_nanos() as u64;
        self.ok()
    }

    /// Wall time of the last [`Adjuster::render`]: upload of the parameters, the GPU passes and
    /// the readback, in nanoseconds.
    pub fn last_nanos(&self) -> u64 {
        self.last_nanos
    }

    /// Whether the edge plane is precomputed per upload (see [`Adjuster::read_blur`]).
    pub fn has_edge_plane(&self) -> bool {
        self.edge_plane
    }

    /// Turns the precomputed edge plane off (or back on, where it fits), so tests can check the
    /// shader's inline gradients against it.
    pub fn use_edge_plane(&mut self, on: bool) {
        self.edge_plane = on && self.edge_capacity;
    }

    /// The blurred luma planes (small, then large, then the edge plane when there is one), for
    /// tests.
    pub fn read_blur(&mut self) -> Option<Vec<f32>> {
        let planes = if self.edge_capacity { 12 } else { 8 };
        let bytes = (self.pixels() * planes) as u64;
        let staging = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("adjust blur readback"),
            size: bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(&self.blur, 0, &staging, 0, bytes);
        self.queue.submit([encoder.finish()]);
        let slice = staging.slice(..);
        slice.map_async(wgpu::MapMode::Read, |_| {});
        self.device.poll(wgpu::PollType::wait_indefinitely()).ok()?;
        let view = slice.get_mapped_range().ok()?;
        let out = bytemuck::cast_slice(&view).to_vec();
        Some(out)
    }
}

/// The identity curves table (every row maps x to x).
pub fn identity_curves() -> Vec<f32> {
    (0..CURVE_FLOATS).map(|i| (i % 256) as f32 / 255.0).collect()
}
