# Stencil

An azphalt effect: turns a design into stackable stencil sheets. A port of GraffitiXR's stencil
tool, without OpenCV, as a sandboxed WASM guest.

## What it does

The value channel is simplified (9x9 median) and clustered into three tones. Whether the image is
dark or light (by area) picks the **base** sheet, which covers the whole subject: black for a dark
image, white for a light one. The midtone and the extreme tone are laid over it, sequentially, as
paint is. Each edge is closed 5x5 so the cuts are smooth. Transparent pixels stay transparent;
background removal is not part of this effect, it uses the layer's own alpha.

Parameters: `view` (`all`, `base`, `midtone`, `secondary`), `base` (`auto`, `dark`, `light`).

The guest works at no more than 256 px on the long side (the sandbox is an interpreter with a 15 s
limit); the host scales the layer down for it and the result back up.

## Layout

~~~
guest/                Rust crate: stencil.rs (algorithm, native tests), lib.rs (WASM shell)
ui/panel.json         the control panel
manifest.template.json, LICENSE, build.py
~~~

## Build

~~~
cargo test --manifest-path extensions/stencil/guest/Cargo.toml
python3 extensions/stencil/build.py [--refresh-fixture]
~~~

`build.py` writes `dist/com.hereliesaz.stencil-<version>.azp` (unsigned, reproducible).
`--refresh-fixture` also copies the WASM to `core/data/src/test/resources/azphalt/stencil/`, which
`StencilGuestTest` and `StencilPackageTest` run. Signing and publishing to the azphalt store need
the publisher's key and are not done here.

Requires the `bitmap` capability: `spec/package-format.md` § 5.1.2.
