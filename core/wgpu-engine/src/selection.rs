//! A stroke confined to the selection at commit, on the GPU (`shaders/selection.wgsl`).
//!
//! The selection's coverage (one byte per pixel: the antialiased clip, or the feathered mask) is
//! uploaded once per selection change with [`Engine::set_selection`] and kept until replaced. A
//! stroke that paints under a selection keeps the layer as it was when it started
//! ([`Engine::take_stroke_shadow`], a GPU-side copy) and, before it commits, blends its paint back
//! over that through the coverage ([`Engine::confine_stroke`]): the same result the editor's CPU
//! commit produced with `keepInside` and `SelectionMask.feather`.

use bytemuck::{Pod, Zeroable};

use super::Engine;
use crate::resident::Rect;

pub const SELECTION_WGSL: &str = include_str!("shaders/selection.wgsl");

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct Params {
    rx: i32,
    ry: i32,
    rw: i32,
    rh: i32,
    width: i32,
    height: i32,
    feather: u32,
    _pad: u32,
}

/// The selection pipeline and its buffers, created on first use.
pub(super) struct SelectionGpu {
    pipeline: wgpu::ComputePipeline,
    uniform: wgpu::Buffer,
    mask: wgpu::Buffer,
    shadow: wgpu::Buffer,
    has_mask: bool,
}

impl SelectionGpu {
    fn new(device: &wgpu::Device, layer_bytes: u64, pixels: u64) -> Self {
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("selection"),
            source: wgpu::ShaderSource::Wgsl(SELECTION_WGSL.into()),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("selection confine"),
            layout: None,
            module: &module,
            entry_point: Some("confine"),
            compilation_options: Default::default(),
            cache: None,
        });
        let buffer = |label: &str, size: u64, usage: wgpu::BufferUsages| {
            device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size: size.max(4),
                usage,
                mapped_at_creation: false,
            })
        };
        let storage = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST;
        SelectionGpu {
            pipeline,
            uniform: buffer(
                "selection params",
                std::mem::size_of::<Params>() as u64,
                wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            ),
            mask: buffer("selection mask", pixels.div_ceil(4) * 4, storage),
            shadow: buffer("stroke shadow", layer_bytes, storage),
            has_mask: false,
        }
    }
}

impl Engine {
    fn selection_gpu(&mut self) -> &mut SelectionGpu {
        if self.selection.is_none() {
            let pixels = (self.width * self.height) as u64;
            self.selection = Some(SelectionGpu::new(&self.device, self.layer_bytes(), pixels));
        }
        self.selection.as_mut().expect("created")
    }

    /// Sets the selection's coverage: one byte per layer pixel, row-major (0 = outside, 255 =
    /// inside; the antialiased clip, or the feathered mask). Kept until replaced or cleared.
    pub fn set_selection(&mut self, coverage: &[u8]) -> bool {
        let n = (self.width * self.height) as usize;
        if coverage.len() < n {
            return false;
        }
        let mut words = vec![0u8; n.div_ceil(4) * 4];
        words[..n].copy_from_slice(&coverage[..n]);
        let queue = self.queue.clone();
        let gpu = self.selection_gpu();
        queue.write_buffer(&gpu.mask, 0, &words);
        gpu.has_mask = true;
        self.ok()
    }

    /// Forgets the selection.
    pub fn clear_selection(&mut self) {
        if let Some(gpu) = self.selection.as_mut() {
            gpu.has_mask = false;
        }
    }

    /// Keeps the layer as it is now, before the stroke's first dab, for [`Engine::confine_stroke`].
    pub fn take_stroke_shadow(&mut self) -> bool {
        self.mp_drain();
        let layer = self.layer.clone();
        let bytes = self.layer_bytes();
        let shadow = self.selection_gpu().shadow.clone();
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(&layer, 0, &shadow, 0, bytes);
        self.queue.submit([encoder.finish()]);
        self.ok()
    }

    /// Blends the stroke painted since [`Engine::take_stroke_shadow`] back over that shadow through
    /// the selection's coverage, over the stroke's rectangle `[x, x + w) x [y, y + h)`: `feather`
    /// picks the feathered composite, otherwise the hard clip. False without a selection.
    #[allow(clippy::too_many_arguments)]
    pub fn confine_stroke(&mut self, x: i32, y: i32, w: i32, h: i32, feather: bool) -> bool {
        let region = Rect::new(x, y, w, h).clamp(self.width, self.height);
        if !self.selection.as_ref().is_some_and(|g| g.has_mask) {
            return false;
        }
        if region.is_empty() {
            return self.ok();
        }
        self.mp_drain();
        let params = Params {
            rx: region.x,
            ry: region.y,
            rw: region.w,
            rh: region.h,
            width: self.width,
            height: self.height,
            feather: u32::from(feather),
            _pad: 0,
        };
        let layer = self.layer.clone();
        let device = self.device.clone();
        let queue = self.queue.clone();
        let gpu = self.selection_gpu();
        queue.write_buffer(&gpu.uniform, 0, bytemuck::bytes_of(&params));
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("selection"),
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
                    resource: gpu.shadow.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: gpu.mask.as_entire_binding(),
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

    /// The rectangle the current stroke has painted: the resident layer's touched rectangle, or
    /// the scratch layer's dirty one.
    pub fn stroke_rect(&self) -> Rect {
        let touched = self
            .active_key
            .and_then(|k| self.residents.get(k))
            .map_or(Rect::default(), |e| e.touched);
        touched.union(self.dirty).clamp(self.width, self.height)
    }
}
