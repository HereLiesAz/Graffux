//! Impasto v1 on the GPU (`shaders/impasto.wgsl`): a per-pixel paint height deposited by a stroke's
//! dabs and an in-place relief shade of the layer from it. The CPU reference is the editor's
//! `ImpastoEngine.deposit` / `ImpastoRegionShader.shade`; [`crate::reference`] mirrors both for the
//! tests. The height lives in its own storage buffer, one f32 per layer pixel, and the caller keeps
//! the canonical copy: it uploads the layer's height before a stroke and reads it back after.

use bytemuck::{Pod, Zeroable};

use super::Engine;
use crate::resident::Rect;

pub const IMPASTO_WGSL: &str = include_str!("shaders/impasto.wgsl");

/// One dab's height contribution, binary-identical to the shader's `HeightDab` (16 bytes).
#[repr(C)]
#[derive(Clone, Copy, Debug, Pod, Zeroable, PartialEq)]
pub struct HeightDab {
    pub x: f32,
    pub y: f32,
    pub radius: f32,
    /// `alpha.clamp(0, 1) * flow_multiplier.max(0)`.
    pub strength: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct Params {
    dab_count: u32,
    hardness: f32,
    thickness: f32,
    origin_x: i32,
    origin_y: i32,
    region_w: i32,
    region_h: i32,
    width: i32,
    height: i32,
    light_x: f32,
    light_y: f32,
    light_z: f32,
    strength: f32,
    _pad: [f32; 3],
}

/// The impasto pipelines and buffers, created on first use.
pub(super) struct ImpastoGpu {
    layout: wgpu::BindGroupLayout,
    deposit: wgpu::ComputePipeline,
    shade: wgpu::ComputePipeline,
    uniform: wgpu::Buffer,
    dabs: wgpu::Buffer,
    dab_capacity: u64,
    heights: wgpu::Buffer,
}

const WORKGROUP: u32 = 8;

impl ImpastoGpu {
    fn new(device: &wgpu::Device, layer_bytes: u64) -> Self {
        let storage = |binding: u32, read_only: bool| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Buffer {
                ty: wgpu::BufferBindingType::Storage { read_only },
                has_dynamic_offset: false,
                min_binding_size: None,
            },
            count: None,
        };
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("impasto"),
            entries: &[
                storage(0, true),
                storage(1, false),
                storage(2, false),
                wgpu::BindGroupLayoutEntry {
                    binding: 3,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
            ],
        });
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("impasto"),
            source: wgpu::ShaderSource::Wgsl(IMPASTO_WGSL.into()),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("impasto"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = |entry: &str| {
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some(entry),
                layout: Some(&pipeline_layout),
                module: &module,
                entry_point: Some(entry),
                compilation_options: Default::default(),
                cache: None,
            })
        };
        let deposit = pipeline("deposit");
        let shade = pipeline("shade");
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("impasto params"),
            size: std::mem::size_of::<Params>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let dab_capacity = 64 * std::mem::size_of::<HeightDab>() as u64;
        let dabs = Self::dab_buffer(device, dab_capacity);
        let heights = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("impasto height"),
            size: layer_bytes,
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        });
        ImpastoGpu {
            layout,
            deposit,
            shade,
            uniform,
            dabs,
            dab_capacity,
            heights,
        }
    }

    fn dab_buffer(device: &wgpu::Device, size: u64) -> wgpu::Buffer {
        device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("impasto dabs"),
            size,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        })
    }
}

impl Engine {
    fn take_impasto(&mut self) -> ImpastoGpu {
        let bytes = self.layer_bytes();
        self.impasto
            .take()
            .unwrap_or_else(|| ImpastoGpu::new(&self.device, bytes))
    }

    /// Sets the impasto height, one value per layer pixel, row-major (`ImpastoEngine`'s height map).
    pub fn upload_height(&mut self, heights: &[f32]) -> bool {
        let n = (self.width * self.height) as usize;
        if heights.len() < n {
            return false;
        }
        let gpu = self.take_impasto();
        self.queue
            .write_buffer(&gpu.heights, 0, bytemuck::cast_slice(&heights[..n]));
        self.impasto = Some(gpu);
        self.ok()
    }

    /// Reads the impasto height back into `out` (one value per layer pixel).
    pub fn read_height(&mut self, out: &mut [f32]) -> bool {
        let n = (self.width * self.height) as usize;
        if out.len() < n {
            return false;
        }
        let gpu = self.take_impasto();
        let heights = gpu.heights.clone();
        self.impasto = Some(gpu);
        let (w, h) = (self.width, self.height);
        let bytes: &mut [u8] = bytemuck::cast_slice_mut(&mut out[..n]);
        self.read_region_into(&heights, Rect::new(0, 0, w, h), bytes, w as usize * 4, 0)
            && self.ok()
    }

    /// `ImpastoEngine.depositStroke`: builds up the height under `dabs`, in order.
    pub fn deposit_height(&mut self, dabs: &[HeightDab], hardness: f32, thickness: f32) -> bool {
        if dabs.is_empty() || thickness <= 0.0 {
            return self.ok();
        }
        let region = height_region(dabs, self.width, self.height);
        if region.is_empty() {
            return self.ok();
        }
        let mut gpu = self.take_impasto();
        let bytes: &[u8] = bytemuck::cast_slice(dabs);
        if bytes.len() as u64 > gpu.dab_capacity {
            gpu.dab_capacity = (bytes.len() as u64).next_power_of_two();
            gpu.dabs = ImpastoGpu::dab_buffer(&self.device, gpu.dab_capacity);
        }
        self.queue.write_buffer(&gpu.dabs, 0, bytes);
        let params = Params {
            dab_count: dabs.len() as u32,
            hardness,
            thickness,
            ..self.params(region)
        };
        self.dispatch(&gpu, &gpu.deposit, params, region);
        self.impasto = Some(gpu);
        self.ok()
    }

    /// `ImpastoRegionShader.shade`, in place on the layer over `[x, x + w) x [y, y + h)`: each
    /// pixel's straight RGB scaled by the relief light from the height's local slope.
    #[allow(clippy::too_many_arguments)]
    pub fn shade_relief(
        &mut self,
        x: i32,
        y: i32,
        w: i32,
        h: i32,
        light_azimuth_deg: f32,
        light_elevation_deg: f32,
        strength: f32,
    ) -> bool {
        let region = Rect::new(x, y, w, h).clamp(self.width, self.height);
        if region.is_empty() || strength <= 0.0 {
            return self.ok();
        }
        // Reads and writes the layer itself: queued multipass work lands first.
        self.mp_drain();
        let gpu = self.take_impasto();
        let azimuth = light_azimuth_deg.to_radians();
        let elevation = light_elevation_deg.to_radians();
        let params = Params {
            light_x: azimuth.cos() * elevation.cos(),
            light_y: azimuth.sin() * elevation.cos(),
            light_z: elevation.sin(),
            strength,
            ..self.params(region)
        };
        self.dispatch(&gpu, &gpu.shade, params, region);
        self.impasto = Some(gpu);
        self.note_write(region);
        self.ok()
    }

    fn params(&self, region: Rect) -> Params {
        Params {
            dab_count: 0,
            hardness: 0.0,
            thickness: 0.0,
            origin_x: region.x,
            origin_y: region.y,
            region_w: region.w,
            region_h: region.h,
            width: self.width,
            height: self.height,
            light_x: 0.0,
            light_y: 0.0,
            light_z: 0.0,
            strength: 0.0,
            _pad: [0.0; 3],
        }
    }

    fn dispatch(
        &mut self,
        gpu: &ImpastoGpu,
        pipeline: &wgpu::ComputePipeline,
        params: Params,
        region: Rect,
    ) {
        self.queue
            .write_buffer(&gpu.uniform, 0, bytemuck::bytes_of(&params));
        let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("impasto"),
            layout: &gpu.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: gpu.dabs.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: gpu.heights.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: self.layer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: gpu.uniform.as_entire_binding(),
                },
            ],
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(pipeline);
            pass.set_bind_group(0, &bind, &[]);
            pass.dispatch_workgroups(
                (region.w as u32).div_ceil(WORKGROUP),
                (region.h as u32).div_ceil(WORKGROUP),
                1,
            );
        }
        self.queue.submit([encoder.finish()]);
    }
}

/// The pixels any of `dabs` can touch: the union of the CPU's per-dab boxes
/// `floor(c - r)..=ceil(c + r)`, clamped to the layer.
pub fn height_region(dabs: &[HeightDab], width: i32, height: i32) -> Rect {
    let mut out: Option<Rect> = None;
    for d in dabs {
        if d.radius <= 0.0 {
            continue;
        }
        let x0 = (d.x - d.radius).floor() as i32;
        let y0 = (d.y - d.radius).floor() as i32;
        let x1 = (d.x + d.radius).ceil() as i32;
        let y1 = (d.y + d.radius).ceil() as i32;
        let r = Rect::new(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        out = Some(out.map_or(r, |o| o.union(r)));
    }
    out.map_or(Rect::default(), |r| r.clamp(width, height))
}
