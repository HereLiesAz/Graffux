//! The retouch tools that read their neighbourhood -- Blur, Sharpen, Clone -- on the GPU
//! (`shaders/retouch.wgsl`). A stroke takes its shadow first ([`Engine::take_stroke_shadow`]);
//! [`Engine::retouch`] then writes the tool's result over the stroke's dabs from that shadow.

use bytemuck::{Pod, Zeroable};

use super::Engine;
use crate::resident::Rect;

pub const RETOUCH_WGSL: &str = include_str!("shaders/retouch.wgsl");

/// Blur: the layer blurred under the stroke.
pub const RETOUCH_BLUR: u32 = 0;
/// Sharpen: an unsharp mask under the stroke.
pub const RETOUCH_SHARPEN: u32 = 1;
/// Clone: the layer copied from an offset under the stroke.
pub const RETOUCH_CLONE: u32 = 2;

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct Params {
    kind: u32,
    dab_count: u32,
    rx: i32,
    ry: i32,
    rw: i32,
    rh: i32,
    width: i32,
    height: i32,
    factor: i32,
    wrap: u32,
    amount: f32,
    offset_x: f32,
    offset_y: f32,
    _p: [u32; 3],
}

/// One retouch tool's run: what it does and its settings.
#[derive(Clone, Copy, Debug)]
pub struct Retouch {
    /// [`RETOUCH_BLUR`], [`RETOUCH_SHARPEN`] or [`RETOUCH_CLONE`].
    pub kind: u32,
    /// Blur: the downscale factor (2..=16).
    pub factor: i32,
    /// Sharpen: the unsharp amount.
    pub amount: f32,
    /// Clone: where each pixel copies from, relative to it.
    pub offset: (f32, f32),
    /// Clone: the source wraps around the layer.
    pub wrap: bool,
}

pub(super) struct RetouchGpu {
    pipeline: wgpu::ComputePipeline,
    uniform: wgpu::Buffer,
}

impl Engine {
    fn retouch_gpu(&mut self) -> &RetouchGpu {
        if self.retouch.is_none() {
            let module = self
                .device
                .create_shader_module(wgpu::ShaderModuleDescriptor {
                    label: Some("retouch"),
                    source: wgpu::ShaderSource::Wgsl(RETOUCH_WGSL.into()),
                });
            let pipeline = self
                .device
                .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some("retouch"),
                    layout: None,
                    module: &module,
                    entry_point: Some("main"),
                    compilation_options: Default::default(),
                    cache: None,
                });
            let uniform = self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("retouch params"),
                size: std::mem::size_of::<Params>() as u64,
                usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
            self.retouch = Some(RetouchGpu { pipeline, uniform });
        }
        self.retouch.as_ref().expect("created")
    }

    /// Runs `tool` over `dabs` (`[x, y, radius, hardness]` each: the stroke's round dabs) from the
    /// stroke shadow into the layer. False without a shadow ([`Engine::take_stroke_shadow`]).
    pub fn retouch(&mut self, tool: Retouch, dabs: &[[f32; 4]]) -> bool {
        if self.selection.is_none() || tool.kind > RETOUCH_CLONE {
            return false;
        }
        let mut region = Rect::default();
        for d in dabs {
            let r = d[2].max(0.5);
            let x0 = (d[0] - r).floor() as i32;
            let y0 = (d[1] - r).floor() as i32;
            let x1 = (d[0] + r).ceil() as i32 + 1;
            let y1 = (d[1] + r).ceil() as i32 + 1;
            region = region.union(Rect::new(x0, y0, x1 - x0, y1 - y0));
        }
        let region = region.clamp(self.width, self.height);
        if region.is_empty() {
            return self.ok();
        }
        self.mp_drain();
        let params = Params {
            kind: tool.kind,
            dab_count: dabs.len() as u32,
            rx: region.x,
            ry: region.y,
            rw: region.w,
            rh: region.h,
            width: self.width,
            height: self.height,
            factor: tool.factor.clamp(2, 16),
            wrap: u32::from(tool.wrap),
            amount: tool.amount,
            offset_x: tool.offset.0,
            offset_y: tool.offset.1,
            _p: [0; 3],
        };
        let dab_bytes: &[u8] = bytemuck::cast_slice(dabs);
        self.dab_buffer.ensure(&self.device, dab_bytes.len() as u64);
        self.queue
            .write_buffer(&self.dab_buffer.buffer, 0, dab_bytes);
        let shadow = self.stroke_shadow();
        let layer = self.layer.clone();
        let dab_buffer = self.dab_buffer.buffer.clone();
        let device = self.device.clone();
        let queue = self.queue.clone();
        let gpu = self.retouch_gpu();
        queue.write_buffer(&gpu.uniform, 0, bytemuck::bytes_of(&params));
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("retouch"),
            layout: &gpu.pipeline.get_bind_group_layout(0),
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: gpu.uniform.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: layer.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: shadow.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: dab_buffer.as_entire_binding(),
                },
            ],
        });
        let mut encoder = device.create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&gpu.pipeline);
            pass.set_bind_group(0, &bind, &[]);
            pass.dispatch_workgroups(
                (region.w as u32).div_ceil(8),
                (region.h as u32).div_ceil(8),
                1,
            );
        }
        queue.submit([encoder.finish()]);
        self.note_write(region);
        self.ok()
    }
}
