//! Direct display for the wgpu engine (docs/Native Rendering Engine Design.md §3, "wgpu direct
//! display"): the live stroke is drawn from the engine's own GPU buffers into a presentable
//! `wgpu::Surface` made from the overlay SurfaceView's `ANativeWindow`, so new paint reaches the
//! compositor without a readback, a bitmap upload or a Compose frame.
//!
//! Why a wgpu surface rather than importing the Vulkan overlay's AHardwareBuffer into wgpu through
//! wgpu-hal: see the design doc. In short, this uses only wgpu's public, backend-neutral API (it
//! works on the Vulkan and the GL backend alike), needs no device extensions wgpu does not enable,
//! and no foreign-queue ownership transfers wgpu cannot express. The price is a swapchain instead
//! of a front buffer.
//!
//! What the surface shows is the stroke's contribution only (`direct.wgsl`, the port of
//! live_overlay.comp) over a snapshot of the layer taken at stroke start, read from the layer or,
//! with multipass on, from the multipass display buffer (draft, then the refinement ease). Nothing
//! here writes the layer: the committed result is byte-identical with direct display on or off.
//!
//! The target is either a real surface (Android) or an offscreen texture of the same format rules
//! (tests: the whole present path runs on a host without a window).

use bytemuck::{Pod, Zeroable};

use super::Engine;

pub const DIRECT_WGSL: &str = include_str!("shaders/direct.wgsl");

/// Capability bits reported by [`Engine::direct_capabilities`] (and `gfx_wgpu_direct_capabilities`).
pub mod caps {
    /// The library was built with window support for this platform (Android).
    pub const WINDOW: u32 = 1;
    /// The adapter can run the present pass (fragment-stage storage buffers).
    pub const ADAPTER: u32 = 2;
    /// A target is attached and configured.
    pub const ATTACHED: u32 = 4;
    /// A stroke is being shown.
    pub const STROKE: u32 = 8;
    /// The attached target is a real swapchain (not offscreen).
    pub const SURFACE: u32 = 16;
}

/// Present mode: the lowest latency that does not tear. Mailbox replaces a queued image instead
/// of waiting behind it; FIFO is the one mode every surface supports.
pub fn pick_present_mode(modes: &[wgpu::PresentMode]) -> wgpu::PresentMode {
    if modes.contains(&wgpu::PresentMode::Mailbox) {
        wgpu::PresentMode::Mailbox
    } else {
        wgpu::PresentMode::Fifo
    }
}

/// How the compositor treats the surface's alpha, and whether the shader must un-premultiply.
/// `None` when the surface can only be opaque: it would hide the canvas, so no direct display.
pub fn pick_alpha_mode(
    modes: &[wgpu::CompositeAlphaMode],
) -> Option<(wgpu::CompositeAlphaMode, bool)> {
    use wgpu::CompositeAlphaMode as M;
    // Inherit on Android is the window's own format, and the SurfaceView is TRANSLUCENT
    // (premultiplied), so it is as good as PreMultiplied there.
    [M::PreMultiplied, M::Inherit]
        .into_iter()
        .find(|m| modes.contains(m))
        .map(|m| (m, false))
        .or_else(|| {
            modes
                .contains(&M::PostMultiplied)
                .then_some((M::PostMultiplied, true))
        })
}

/// Target format: an 8-bit RGBA/BGRA format, linear first. An sRGB format is usable too, the shader
/// then decodes so the stored bytes are unchanged (second value). `None`: nothing usable.
pub fn pick_format(formats: &[wgpu::TextureFormat]) -> Option<(wgpu::TextureFormat, bool)> {
    use wgpu::TextureFormat as F;
    [F::Rgba8Unorm, F::Bgra8Unorm]
        .into_iter()
        .find(|f| formats.contains(f))
        .map(|f| (f, false))
        .or_else(|| {
            [F::Rgba8UnormSrgb, F::Bgra8UnormSrgb]
                .into_iter()
                .find(|f| formats.contains(f))
                .map(|f| (f, true))
        })
}

/// Scalar mirror of `direct.wgsl`'s `contribution` (and live_overlay.comp's
/// `strokeContribution`), on unorm8 inputs; tests compare the shader against it.
pub fn stroke_contribution(painted: [u8; 4], base: [u8; 4]) -> [f32; 4] {
    if painted == base {
        return [0.0; 4];
    }
    let p = painted.map(|v| v as f32 / 255.0);
    let b = base.map(|v| v as f32 / 255.0);
    if b[3] >= 0.999 {
        return [p[0], p[1], p[2], 1.0];
    }
    let sa = (1.0 - (1.0 - p[3]) / (1.0 - b[3])).clamp(0.0, 1.0);
    let s = |i: usize| (p[i] - b[i] * (1.0 - sa)).clamp(0.0, sa);
    [s(0), s(1), s(2), sa]
}

/// A 2x3 affine (overlay pixel -> layer pixel) is usable when finite and invertible.
pub fn affine_usable(m: &[f32; 6]) -> bool {
    let det = m[0] * m[4] - m[1] * m[3];
    m.iter().all(|v| v.is_finite()) && det.is_finite() && det.abs() > 1e-12
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct DirectUniform {
    row0: [f32; 4],
    row1: [f32; 4],
    layer_width: i32,
    layer_height: i32,
    mode: i32,
    flags: i32,
}

const MODE_CONTRIBUTION: i32 = 0;
const MODE_CLEAR: i32 = 1;
const FLAG_SRGB: i32 = 1;
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const FLAG_STRAIGHT: i32 = 2;

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
enum Target {
    Surface {
        surface: wgpu::Surface<'static>,
        config: wgpu::SurfaceConfiguration,
    },
    Offscreen {
        texture: wgpu::Texture,
    },
}

/// Direct-display state of one engine: the target, the present pipeline and the stroke's base.
pub(crate) struct Direct {
    target: Target,
    flags: i32,
    pipeline: wgpu::RenderPipeline,
    layout: wgpu::BindGroupLayout,
    uniform: wgpu::Buffer,
    /// The layer as it was at stroke start (what the canvas under the surface keeps showing).
    base: wgpu::Buffer,
    /// Layer size `base` was made for; a different layer size re-allocates it.
    base_size: (i32, i32),
    stroke: bool,
    matrix: [f32; 6],
    presents: u64,
}

fn fragment_storage_entry(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::FRAGMENT,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Storage { read_only: true },
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    }
}

impl Direct {
    fn new(e: &Engine, target: Target, format: wgpu::TextureFormat, flags: i32) -> Direct {
        let device = &e.device;
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("direct display"),
            entries: &[
                fragment_storage_entry(0),
                fragment_storage_entry(1),
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::FRAGMENT,
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
            label: Some("direct display"),
            source: wgpu::ShaderSource::Wgsl(DIRECT_WGSL.into()),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("direct display"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("direct display"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &module,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[],
            },
            primitive: wgpu::PrimitiveState::default(),
            depth_stencil: None,
            multisample: wgpu::MultisampleState::default(),
            fragment: Some(wgpu::FragmentState {
                module: &module,
                entry_point: Some("fs"),
                compilation_options: Default::default(),
                // No blending: every frame replaces the whole image.
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            multiview_mask: None,
            cache: None,
        });
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("direct display params"),
            size: std::mem::size_of::<DirectUniform>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Direct {
            target,
            flags,
            pipeline,
            layout,
            uniform,
            base: Self::base_buffer(device, e.layer_bytes()),
            base_size: (e.width, e.height),
            stroke: false,
            matrix: [1.0, 0.0, 0.0, 0.0, 1.0, 0.0],
            presents: 0,
        }
    }

    fn base_buffer(device: &wgpu::Device, bytes: u64) -> wgpu::Buffer {
        device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("direct display base"),
            size: bytes.max(16),
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        })
    }
}

impl Engine {
    /// Which parts of direct display this engine can do now ([`caps`] bits). Capability detection
    /// for the Kotlin side: `WINDOW | ADAPTER` means attaching a window is worth trying (it can
    /// still fail, e.g. a surface format or alpha mode the window lacks, and then says so).
    pub fn direct_capabilities(&self) -> u32 {
        let mut bits = 0;
        if cfg!(target_os = "android") {
            bits |= caps::WINDOW;
        }
        if self.direct_adapter_ok() {
            bits |= caps::ADAPTER;
        }
        if let Some(d) = self.direct.as_ref() {
            bits |= caps::ATTACHED;
            if d.stroke {
                bits |= caps::STROKE;
            }
            if matches!(d.target, Target::Surface { .. }) {
                bits |= caps::SURFACE;
            }
        }
        bits
    }

    fn direct_adapter_ok(&self) -> bool {
        let Some(adapter) = self.adapter.as_ref() else {
            return false;
        };
        let downlevel = adapter.get_downlevel_capabilities();
        downlevel
            .flags
            .contains(wgpu::DownlevelFlags::FRAGMENT_STORAGE)
            && self.device.limits().max_storage_buffers_per_shader_stage >= 2
    }

    /// Attaches direct display to an Android `ANativeWindow` of `width`x`height` pixels (the
    /// overlay SurfaceView's). Idempotent for the same window and size. False when this platform,
    /// adapter or window cannot: the caller keeps the readback display.
    ///
    /// # Safety
    /// `window` must be a valid `ANativeWindow*` that stays valid (the caller holds a reference)
    /// until [`Engine::direct_detach`] or the engine is dropped. At most one engine may be attached
    /// to a window at a time (a window has one producer).
    #[allow(unused_variables)]
    pub unsafe fn direct_attach_window(
        &mut self,
        window: *mut std::ffi::c_void,
        width: i32,
        height: i32,
    ) -> bool {
        #[cfg(target_os = "android")]
        {
            use wgpu::rwh::{
                AndroidDisplayHandle, AndroidNdkWindowHandle, RawDisplayHandle, RawWindowHandle,
            };
            if width <= 0 || height <= 0 || !self.direct_adapter_ok() {
                return false;
            }
            let Some(ptr) = std::ptr::NonNull::new(window) else {
                return false;
            };
            if let Some(Direct {
                target: Target::Surface { config, .. },
                ..
            }) = self.direct.as_deref()
            {
                if self.direct_window == Some(window as usize)
                    && config.width == width as u32
                    && config.height == height as u32
                {
                    return true;
                }
            }
            self.direct_detach();
            let Some(instance) = self.instance.as_ref() else {
                return false;
            };
            let target = wgpu::SurfaceTargetUnsafe::RawHandle {
                raw_display_handle: Some(RawDisplayHandle::Android(AndroidDisplayHandle::new())),
                raw_window_handle: RawWindowHandle::AndroidNdk(AndroidNdkWindowHandle::new(ptr)),
            };
            // SAFETY: the caller guarantees the window outlives the surface (see the doc).
            let surface = match unsafe { instance.create_surface_unsafe(target) } {
                Ok(s) => s,
                Err(err) => {
                    log::warn!("graffux-wgpu: direct display surface: {err}");
                    return false;
                }
            };
            if self.direct_configure(surface, width as u32, height as u32) {
                self.direct_window = Some(window as usize);
                true
            } else {
                false
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            false
        }
    }

    #[cfg(target_os = "android")]
    fn direct_configure(
        &mut self,
        surface: wgpu::Surface<'static>,
        width: u32,
        height: u32,
    ) -> bool {
        let Some(adapter) = self.adapter.as_ref() else {
            return false;
        };
        if !adapter.is_surface_supported(&surface) {
            log::warn!("graffux-wgpu: direct display: surface unsupported by this adapter");
            return false;
        }
        let surface_caps = surface.get_capabilities(adapter);
        let (Some((format, srgb)), Some((alpha, straight))) = (
            pick_format(&surface_caps.formats),
            pick_alpha_mode(&surface_caps.alpha_modes),
        ) else {
            log::warn!(
                "graffux-wgpu: direct display: no usable format/alpha ({:?} / {:?})",
                surface_caps.formats,
                surface_caps.alpha_modes
            );
            return false;
        };
        if !surface_caps
            .usages
            .contains(wgpu::TextureUsages::RENDER_ATTACHMENT)
        {
            return false;
        }
        let Some(mut config) = surface.get_default_config(adapter, width, height) else {
            return false;
        };
        config.usage = wgpu::TextureUsages::RENDER_ATTACHMENT;
        config.format = format;
        config.present_mode = pick_present_mode(&surface_caps.present_modes);
        config.alpha_mode = alpha;
        config.view_formats = vec![];
        config.desired_maximum_frame_latency = 1;
        surface.configure(&self.device, &config);
        let flags = (if srgb { FLAG_SRGB } else { 0 }) | (if straight { FLAG_STRAIGHT } else { 0 });
        let direct = Direct::new(self, Target::Surface { surface, config }, format, flags);
        self.direct = Some(Box::new(direct));
        // Start transparent: the SurfaceView sits over the whole canvas.
        let ok = self.direct_draw(MODE_CLEAR, None) && self.ok();
        if !ok {
            self.direct_detach();
        }
        ok
    }

    /// Attaches an offscreen `width`x`height` target in place of a window (host tests, and any
    /// platform without window support). `format` must be one [`pick_format`] accepts.
    pub fn direct_attach_offscreen(
        &mut self,
        width: i32,
        height: i32,
        format: wgpu::TextureFormat,
    ) -> bool {
        if width <= 0 || height <= 0 || !self.direct_adapter_ok() {
            return false;
        }
        let Some((format, srgb)) = pick_format(&[format]) else {
            return false;
        };
        self.direct_detach();
        let texture = self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("direct display offscreen"),
            size: wgpu::Extent3d {
                width: width as u32,
                height: height as u32,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let direct = Direct::new(
            self,
            Target::Offscreen { texture },
            format,
            if srgb { FLAG_SRGB } else { 0 },
        );
        self.direct = Some(Box::new(direct));
        self.direct_draw(MODE_CLEAR, None) && self.ok()
    }

    /// Drops the target (and with it the swapchain, disconnecting from the window).
    pub fn direct_detach(&mut self) {
        self.direct = None;
        self.direct_window = None;
    }

    /// Stroke start: snapshots the layer as the stroke's base and clears the target. Call after the
    /// layer is seeded (upload / bind) and before the first dab. Queued multipass work, if any, lands
    /// first so the base is the layer the canvas shows.
    pub fn direct_begin_stroke(&mut self) -> bool {
        if self.direct.is_none() {
            return false;
        }
        if self.mp_on() {
            self.mp_drain();
        }
        let (w, h, bytes) = (self.width, self.height, self.layer_bytes());
        let d = self.direct.as_mut().expect("checked above");
        if d.base_size != (w, h) {
            d.base = Direct::base_buffer(&self.device, bytes);
            d.base_size = (w, h);
        }
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(&self.layer, 0, &d.base, 0, bytes);
        self.queue.submit([encoder.finish()]);
        d.stroke = true;
        d.presents = 0;
        self.direct_draw(MODE_CLEAR, None) && self.ok()
    }

    /// Shows the stroke so far. `matrix` maps a target pixel to layer pixels
    /// (`(m0*x + m1*y + m2, m3*x + m4*y + m5)`); `None` re-presents with the previous one (the
    /// refinement ease while the pen rests). `new_batch`: a stamp call preceded this present (feeds
    /// the multipass frame cadence). With multipass on, pending drafts run first -- never
    /// refinement -- and the display composite advances; the surface then shows the display.
    pub fn direct_present(&mut self, matrix: Option<[f32; 6]>, new_batch: bool) -> bool {
        match self.direct.as_mut() {
            Some(d) if d.stroke => {
                if let Some(m) = matrix {
                    if !affine_usable(&m) {
                        return false;
                    }
                    d.matrix = m;
                }
            }
            _ => return false,
        }
        let source = self.mp_present_source(new_batch);
        self.direct_draw(MODE_CONTRIBUTION, source) && self.ok()
    }

    /// Stroke end: clears the target (the committed layer is on screen by now) and forgets the base.
    pub fn direct_end_stroke(&mut self) -> bool {
        match self.direct.as_mut() {
            Some(d) => d.stroke = false,
            None => return false,
        }
        self.direct_draw(MODE_CLEAR, None) && self.ok()
    }

    /// Presents so far in this stroke (tests, telemetry).
    pub fn direct_present_count(&self) -> u64 {
        self.direct.as_ref().map_or(0, |d| d.presents)
    }

    /// Tests: the offscreen target's pixels, tightly packed RGBA8 (BGRA targets are swizzled to
    /// RGBA). None for a surface target or none attached.
    pub fn direct_read_offscreen(&mut self) -> Option<Vec<u8>> {
        let (texture, bgra) = match self.direct.as_ref().map(|d| &d.target) {
            Some(Target::Offscreen { texture }) => (
                texture.clone(),
                matches!(
                    texture.format(),
                    wgpu::TextureFormat::Bgra8Unorm | wgpu::TextureFormat::Bgra8UnormSrgb
                ),
            ),
            _ => return None,
        };
        let (w, h) = (texture.width(), texture.height());
        let row = w * 4;
        let padded =
            row.div_ceil(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT) * wgpu::COPY_BYTES_PER_ROW_ALIGNMENT;
        let staging = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("direct display offscreen readback"),
            size: padded as u64 * h as u64,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_buffer(
            texture.as_image_copy(),
            wgpu::TexelCopyBufferInfo {
                buffer: &staging,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(padded),
                    rows_per_image: Some(h),
                },
            },
            texture.size(),
        );
        self.queue.submit([encoder.finish()]);
        let slice = staging.slice(..);
        let (tx, rx) = std::sync::mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r.is_ok());
        });
        self.device.poll(wgpu::PollType::wait_indefinitely()).ok()?;
        if !matches!(rx.recv(), Ok(true)) {
            return None;
        }
        let mut out = vec![0u8; (row * h) as usize];
        {
            let mapped = slice.get_mapped_range().ok()?;
            for y in 0..h as usize {
                let src = &mapped[y * padded as usize..y * padded as usize + row as usize];
                out[y * row as usize..(y + 1) * row as usize].copy_from_slice(src);
            }
        }
        staging.unmap();
        if bgra {
            out.chunks_exact_mut(4).for_each(|p| p.swap(0, 2));
        }
        Some(out)
    }

    /// One full-target pass: the contribution of `source` (default: the layer) over the base, or
    /// transparent. Acquires, draws and presents a swapchain image, or draws the offscreen texture.
    fn direct_draw(&mut self, mode: i32, source: Option<wgpu::Buffer>) -> bool {
        let src = source.unwrap_or_else(|| self.layer.clone());
        let (width, height) = (self.width, self.height);
        let Some(d) = self.direct.as_mut() else {
            return false;
        };
        let m = d.matrix;
        let u = DirectUniform {
            row0: [m[0], m[1], m[2], 0.0],
            row1: [m[3], m[4], m[5], 0.0],
            layer_width: width,
            layer_height: height,
            mode,
            flags: d.flags,
        };
        self.queue
            .write_buffer(&d.uniform, 0, bytemuck::bytes_of(&u));
        let bind_group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("direct display"),
            layout: &d.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: src.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: d.base.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: d.uniform.as_entire_binding(),
                },
            ],
        });
        let (frame, view) = match &d.target {
            Target::Offscreen { texture } => (None, texture.create_view(&Default::default())),
            Target::Surface { surface, config } => {
                let acquired = match surface.get_current_texture() {
                    wgpu::CurrentSurfaceTexture::Success(t) => Some(t),
                    wgpu::CurrentSurfaceTexture::Suboptimal(t) => Some(t),
                    wgpu::CurrentSurfaceTexture::Outdated | wgpu::CurrentSurfaceTexture::Lost => {
                        // The window changed under us (rotation, resize): reconfigure once.
                        surface.configure(&self.device, config);
                        match surface.get_current_texture() {
                            wgpu::CurrentSurfaceTexture::Success(t)
                            | wgpu::CurrentSurfaceTexture::Suboptimal(t) => Some(t),
                            _ => None,
                        }
                    }
                    _ => None,
                };
                let Some(frame) = acquired else {
                    return false;
                };
                let view = frame.texture.create_view(&Default::default());
                (Some(frame), view)
            }
        };
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("direct display"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            pass.set_pipeline(&d.pipeline);
            pass.set_bind_group(0, &bind_group, &[]);
            pass.draw(0..3, 0..1);
        }
        self.queue.submit([encoder.finish()]);
        if let Some(frame) = frame {
            self.queue.present(frame);
        }
        if mode == MODE_CONTRIBUTION {
            d.presents += 1;
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wgpu::CompositeAlphaMode as A;
    use wgpu::PresentMode as P;
    use wgpu::TextureFormat as F;

    #[test]
    fn present_mode_prefers_mailbox_then_fifo() {
        assert_eq!(
            pick_present_mode(&[P::Fifo, P::Mailbox, P::Immediate]),
            P::Mailbox
        );
        assert_eq!(pick_present_mode(&[P::Immediate, P::Fifo]), P::Fifo);
        // Immediate tears; never chosen. FIFO is mandatory, so it is the fallback even if unlisted.
        assert_eq!(pick_present_mode(&[P::Immediate]), P::Fifo);
    }

    #[test]
    fn alpha_mode_needs_a_translucent_mode() {
        assert_eq!(
            pick_alpha_mode(&[A::Opaque, A::PreMultiplied]),
            Some((A::PreMultiplied, false))
        );
        assert_eq!(
            pick_alpha_mode(&[A::Inherit, A::Opaque]),
            Some((A::Inherit, false))
        );
        assert_eq!(
            pick_alpha_mode(&[A::PostMultiplied]),
            Some((A::PostMultiplied, true))
        );
        assert_eq!(pick_alpha_mode(&[A::Opaque]), None);
        assert_eq!(pick_alpha_mode(&[]), None);
    }

    #[test]
    fn format_prefers_linear_rgba8() {
        assert_eq!(
            pick_format(&[F::Rgba8UnormSrgb, F::Rgba8Unorm]),
            Some((F::Rgba8Unorm, false))
        );
        assert_eq!(
            pick_format(&[F::Bgra8UnormSrgb, F::Bgra8Unorm]),
            Some((F::Bgra8Unorm, false))
        );
        assert_eq!(
            pick_format(&[F::Rgba8UnormSrgb]),
            Some((F::Rgba8UnormSrgb, true))
        );
        assert_eq!(pick_format(&[F::Rgb10a2Unorm, F::Rgba16Float]), None);
    }

    #[test]
    fn contribution_recomposes_to_the_painted_pixel() {
        // stroke OVER base must give back the painted pixel (within 8-bit rounding).
        let cases: [([u8; 4], [u8; 4]); 5] = [
            ([200, 40, 40, 255], [0, 0, 0, 0]),
            ([120, 60, 30, 200], [40, 40, 40, 80]),
            ([10, 200, 90, 255], [100, 100, 100, 255]),
            ([60, 60, 60, 128], [60, 60, 60, 128]),
            ([30, 90, 150, 180], [0, 50, 100, 120]),
        ];
        for (painted, base) in cases {
            let s = stroke_contribution(painted, base);
            let b = base.map(|v| v as f32 / 255.0);
            for i in 0..4 {
                let back = s[i] + b[i] * (1.0 - s[3]);
                assert!(
                    (back * 255.0 - painted[i] as f32).abs() <= 1.0,
                    "{painted:?} over {base:?}: channel {i} -> {back}"
                );
            }
        }
        assert_eq!(stroke_contribution([1, 2, 3, 4], [1, 2, 3, 4]), [0.0; 4]);
    }

    #[test]
    fn affine_rejects_singular_and_non_finite() {
        assert!(affine_usable(&[1.0, 0.0, 0.0, 0.0, 1.0, 0.0]));
        assert!(affine_usable(&[0.5, -0.2, 10.0, 0.2, 0.5, -3.0]));
        assert!(!affine_usable(&[1.0, 2.0, 0.0, 2.0, 4.0, 0.0]));
        assert!(!affine_usable(&[f32::NAN, 0.0, 0.0, 0.0, 1.0, 0.0]));
    }

    #[test]
    fn shader_parses() {
        naga::front::wgsl::parse_str(DIRECT_WGSL).expect("direct.wgsl parses");
    }
}
