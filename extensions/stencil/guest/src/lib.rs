//! azphalt stencil effect: the WASM guest. See `stencil.rs` for the algorithm.
//!
//! Host contract (spec/package-format.md § 5): capabilities `bitmap` and `params`. `run` reads the
//! layer's pixels, renders the stencil, and stages the result with `bitmapWrite`; the host swaps it
//! in only once `run` returns. Parameters: `view` (`all`, `base`, `midtone`, `secondary`) and
//! `base` (`auto`, `dark`, `light`).

pub mod stencil;

#[cfg(target_arch = "wasm32")]
mod guest {
    use crate::stencil::{stencil, BaseChoice, View, WORK_DIM};

    #[link(wasm_import_module = "env")]
    extern "C" {
        #[link_name = "bitmapWidth"]
        fn bitmap_width() -> i32;
        #[link_name = "bitmapHeight"]
        fn bitmap_height() -> i32;
        #[link_name = "bitmapRead"]
        fn bitmap_read(max_dim: i32, out_ptr: *mut u8, out_cap: i32, dims_ptr: *mut i32) -> i32;
        #[link_name = "bitmapWrite"]
        fn bitmap_write(in_ptr: *const u8, len: i32, width: i32, height: i32) -> i32;
        #[link_name = "paramString"]
        fn param_string(key_ptr: *const u8, key_len: i32, out_ptr: *mut u8, out_cap: i32) -> i32;
    }

    fn param(key: &str) -> Option<String> {
        let mut buf = [0u8; 32];
        let n = unsafe { param_string(key.as_ptr(), key.len() as i32, buf.as_mut_ptr(), buf.len() as i32) };
        if n < 0 || n as usize > buf.len() {
            return None;
        }
        String::from_utf8(buf[..n as usize].to_vec()).ok()
    }

    /// The per-invocation entry point the host calls. A failure traps: the layer is left untouched
    /// because the host applies a staged write only after `run` returns.
    #[no_mangle]
    pub extern "C" fn run() {
        let (nw, nh) = unsafe { (bitmap_width(), bitmap_height()) };
        if nw <= 0 || nh <= 0 {
            panic!("no bitmap to stencil");
        }
        // The host scales the layer down to WORK_DIM on the long side; the buffer is sized for
        // the largest result it can return.
        let cap = WORK_DIM * WORK_DIM * 4;
        let mut rgba = vec![0u8; cap];
        let mut dims = [0i32; 2];
        let got = unsafe { bitmap_read(WORK_DIM as i32, rgba.as_mut_ptr(), cap as i32, dims.as_mut_ptr()) };
        let (w, h) = (dims[0] as usize, dims[1] as usize);
        if got < 0 || w == 0 || h == 0 || got as usize != w * h * 4 || got as usize > cap {
            panic!("the layer is too large to stencil");
        }
        rgba.truncate(got as usize);
        let view = param("view").map(|s| View::parse(&s)).unwrap_or(View::All);
        let base = param("base").map(|s| BaseChoice::parse(&s)).unwrap_or(BaseChoice::Auto);
        let out = stencil(&rgba, w, h, view, base).unwrap_or_else(|| panic!("nothing to stencil"));
        drop(rgba);
        if unsafe { bitmap_write(out.rgba.as_ptr(), out.rgba.len() as i32, w as i32, h as i32) } != 1 {
            panic!("the host refused the stencil");
        }
    }
}
