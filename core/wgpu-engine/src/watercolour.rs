//! Watercolour field passes on the GPU (`shaders/watercolour.wgsl`), spec/painterly.md phase P8.
//!
//! A WGSL port of Kotlin `WatercolourField`. [`run_on_gpu`] is a standalone entry (its own device) used
//! by the parity test against `tests/fixtures/watercolour_golden.txt`; adding the passes to the wet
//! simulation's ADVANCE family on the resident layer is part of the integration phase (P6b).

use crate::shared_gpu;

pub const WATERCOLOUR_WGSL: &str = include_str!("shaders/watercolour.wgsl");

#[derive(Clone, Debug, Default)]
pub struct WatercolourInput {
    pub width: i32,
    pub height: i32,
    pub wet: Vec<f32>,
    pub pigment: Vec<f32>,
    pub absorbency: Vec<f32>,
    pub tooth: Vec<f32>,
    pub deposit_amount: Vec<f32>,
    pub deposit_water: Vec<f32>,
    pub wick: f32,
    pub bleed: f32,
    pub edge: f32,
    pub dry: f32,
    pub granulation: f32,
    pub ticks: u32,
}

#[derive(Clone, Debug, Default)]
pub struct WatercolourOutput {
    pub wet: Vec<f32>,
    pub pigment: Vec<f32>,
}

pub fn run_on_gpu(backends: wgpu::Backends, input: &WatercolourInput) -> Option<WatercolourOutput> {
    let n = (input.width.max(0) * input.height.max(0)) as usize;
    if n == 0
        || [&input.wet, &input.pigment, &input.absorbency, &input.tooth, &input.deposit_amount, &input.deposit_water]
            .iter()
            .any(|v| v.len() != n)
    {
        return None;
    }
    let gpu = shared_gpu::shared(backends)?;
    let (device, queue) = (&gpu.device, &gpu.queue);
    let bytes = (n * 4) as u64;
    if !shared_gpu::fits(&gpu.limits, &[bytes], 8, 64) || !shared_gpu::groups_fit(&gpu.limits, n.div_ceil(64) as u64) {
        log::warn!("graffux-wgpu watercolour: {}x{} exceeds this adapter's limits", input.width, input.height);
        return None;
    }
    gpu.scoped("watercolour", || {
    let (bind_layout, deposit, step) = gpu.cached("watercolour", || {
    let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("watercolour"),
        source: wgpu::ShaderSource::Wgsl(WATERCOLOUR_WGSL.into()),
    });
    // Explicit layout: both entry points share one bind group, though each uses only some bindings.
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
    let mut layout_entries: Vec<wgpu::BindGroupLayoutEntry> = (0..8).map(|b| storage(b, b >= 4)).collect();
    layout_entries.push(wgpu::BindGroupLayoutEntry {
        binding: 8,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Uniform,
            has_dynamic_offset: false,
            min_binding_size: None,
        },
        count: None,
    });
    let bind_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
        label: Some("watercolour layout"),
        entries: &layout_entries,
    });
    let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some("watercolour pipeline layout"),
        bind_group_layouts: &[Some(&bind_layout)],
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
    let step = pipeline("step");
        (bind_layout, deposit, step)
    });
    let usage = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::COPY_SRC;
    let make = |label: &str, data: &[f32]| {
        let buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: (data.len() * 4) as u64,
            usage,
            mapped_at_creation: false,
        });
        queue.write_buffer(&buf, 0, bytemuck::cast_slice(data));
        buf
    };
    let wet_a = make("wet_a", &input.wet);
    let pig_a = make("pig_a", &input.pigment);
    let wet_b = make("wet_b", &input.wet);
    let pig_b = make("pig_b", &input.pigment);
    let absorb = make("absorb", &input.absorbency);
    let tooth = make("tooth", &input.tooth);
    let dep_amount = make("dep_amount", &input.deposit_amount);
    let dep_water = make("dep_water", &input.deposit_water);
    let params: [u32; 8] = [
        input.width as u32,
        input.height as u32,
        input.wick.to_bits(),
        input.bleed.to_bits(),
        input.edge.to_bits(),
        input.dry.to_bits(),
        input.granulation.to_bits(),
        0,
    ];
    let uniform = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("params"),
        size: 32,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    queue.write_buffer(&uniform, 0, bytemuck::cast_slice(&params));
    let bind = |layout: &wgpu::BindGroupLayout, win: &wgpu::Buffer, pin: &wgpu::Buffer, wout: &wgpu::Buffer, pout: &wgpu::Buffer| {
        device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("watercolour bind"),
            layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: win.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 1, resource: pin.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 2, resource: wout.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 3, resource: pout.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 4, resource: absorb.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 5, resource: tooth.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 6, resource: dep_amount.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 7, resource: dep_water.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 8, resource: uniform.as_entire_binding() },
            ],
        })
    };
    let a_to_b = bind(&bind_layout, &wet_a, &pig_a, &wet_b, &pig_b);
    let b_to_a = bind(&bind_layout, &wet_b, &pig_b, &wet_a, &pig_a);
    let groups = ((n + 63) / 64) as u32;
    let mut encoder = device.create_command_encoder(&Default::default());
    {
        let mut pass = encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&deposit);
        pass.set_bind_group(0, &a_to_b, &[]);
        pass.dispatch_workgroups(groups, 1, 1);
    }
    for t in 0..input.ticks {
        let mut pass = encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&step);
        pass.set_bind_group(0, if t % 2 == 0 { &a_to_b } else { &b_to_a }, &[]);
        pass.dispatch_workgroups(groups, 1, 1);
    }
    // the result lives in A after an even number of ticks, in B after an odd number
    let (wet_res, pig_res) = if input.ticks % 2 == 0 { (&wet_a, &pig_a) } else { (&wet_b, &pig_b) };
    let read = |buf: &wgpu::Buffer, encoder: &mut wgpu::CommandEncoder| {
        let staging = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("staging"),
            size: bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        encoder.copy_buffer_to_buffer(buf, 0, &staging, 0, bytes);
        staging
    };
    let s_wet = read(wet_res, &mut encoder);
    let s_pig = read(pig_res, &mut encoder);
    queue.submit([encoder.finish()]);
    let mut out = Vec::new();
    for staging in [&s_wet, &s_pig] {
        out.push(
            gpu.read(staging, 0..bytes, |v| {
                bytemuck::cast_slice::<u8, f32>(v).to_vec()
            })
            .ok()?,
        );
    }
    let mut it = out.into_iter();
    Some(WatercolourOutput { wet: it.next()?, pigment: it.next()? })
    })
}
