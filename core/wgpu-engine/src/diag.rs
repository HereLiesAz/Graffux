//! Why an engine could not start, in words a person can read.
//!
//! [`Engine::new`](crate::Engine::new) used to answer `None` and nothing else: no adapter, no
//! compute support, a layer over the device's buffer limits and a refused device were all the same
//! silent failure, and on Android neither this crate's `log` calls nor wgpu's own went anywhere (no
//! logger was installed), so even "check logcat" showed nothing. This module keeps a [`Report`] of
//! every adapter considered and the reason each was passed over, captures the wgpu/naga log records
//! that explain an adapter wgpu-hal hid (a non-conformant Vulkan driver, an unusable GL context),
//! and publishes the lot for the C ABI (`gfx_wgpu_last_init_report`) so the app can show and log
//! the real reason.

use std::collections::VecDeque;
use std::sync::{Mutex, Once};

/// How many captured log lines a report carries (newest kept).
const LOG_CAPACITY: usize = 24;

static LAST: Mutex<String> = Mutex::new(String::new());
static CAPTURED: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static INSTALL: Once = Once::new();
static INSTALLED: Mutex<bool> = Mutex::new(false);

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// The report of the most recent engine creation (empty before the first one).
pub fn last_report() -> String {
    lock(&LAST).clone()
}

/// One engine creation's findings: free-text lines, in the order they happened.
#[derive(Default, Debug)]
pub(crate) struct Report {
    lines: Vec<String>,
}

impl Report {
    pub(crate) fn note(&mut self, line: impl Into<String>) {
        self.lines.push(line.into());
    }

    /// The report as text, with `logs` (wgpu's own explanation) appended under their own heading.
    pub(crate) fn text(&self, logs: &[String]) -> String {
        let mut out = self.lines.join("\n");
        if !logs.is_empty() {
            out.push_str("\nwgpu log:");
            for l in logs {
                out.push_str("\n  ");
                out.push_str(l);
            }
        }
        out
    }

    /// Publishes this report as [`last_report`], with whatever wgpu logged since [`begin`].
    pub(crate) fn publish(&self) {
        let logs: Vec<String> = lock(&CAPTURED).iter().cloned().collect();
        *lock(&LAST) = self.text(&logs);
    }
}

/// One adapter, one line: what it is and whether compute (the stamp engine's only requirement)
/// is there. Plain strings so it is testable without a GPU.
pub(crate) fn adapter_line(
    index: usize,
    backend: &str,
    name: &str,
    device_type: &str,
    driver: &str,
    driver_info: &str,
    compute: bool,
) -> String {
    format!(
        "adapter[{index}] backend={backend} name=\"{}\" type={device_type} driver=\"{} {}\" compute_shaders={}",
        name.trim(),
        driver.trim(),
        driver_info.trim(),
        if compute { "yes" } else { "NO" },
    )
}

/// The limits the engine checks, as `required<=available` pairs so a shortfall is obvious.
pub(crate) fn limits_line(
    layer_state_bytes: u64,
    max_storage_binding: u64,
    max_buffer: u64,
    storage_buffers_per_stage: u32,
    invocations: u32,
    workgroup_x: u32,
    workgroup_y: u32,
    tile: u32,
) -> String {
    format!(
        "limits: layer_state_bytes={layer_state_bytes} (max_storage_buffer_binding_size={max_storage_binding}, \
         max_buffer_size={max_buffer}); storage_buffers_per_stage={storage_buffers_per_stage} (need>=3); \
         workgroup tile {tile}x{tile} (max_invocations={invocations}, max_x={workgroup_x}, max_y={workgroup_y})"
    )
}

// ---- capturing wgpu's own log records ----------------------------------------------------

struct Capture;

impl Capture {
    /// Records worth keeping: every warning and error from wgpu, naga and this crate, and the debug
    /// records of the adapter/instance code, where wgpu-hal says why it hid an adapter.
    fn wanted(level: log::Level, target: &str) -> bool {
        let ours = target.starts_with("wgpu")
            || target.starts_with("naga")
            || target.starts_with("graffux");
        if !ours {
            return false;
        }
        match level {
            log::Level::Error | log::Level::Warn => true,
            log::Level::Info | log::Level::Debug => {
                target.contains("adapter") || target.contains("instance")
            }
            log::Level::Trace => false,
        }
    }
}

impl log::Log for Capture {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        Self::wanted(metadata.level(), metadata.target())
    }

    fn log(&self, record: &log::Record) {
        if !Self::wanted(record.level(), record.target()) {
            return;
        }
        let line = format!("{} {}: {}", record.level(), record.target(), record.args());
        #[cfg(target_os = "android")]
        android_log(record.level(), &line);
        let mut q = lock(&CAPTURED);
        if q.len() == LOG_CAPACITY {
            q.pop_front();
        }
        q.push_back(line);
    }

    fn flush(&self) {}
}

#[cfg(target_os = "android")]
fn android_log(level: log::Level, line: &str) {
    use std::ffi::{c_char, c_int, CString};
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
    let prio = match level {
        log::Level::Error => 6,
        log::Level::Warn => 5,
        log::Level::Info => 4,
        _ => 3,
    };
    if let (Ok(tag), Ok(text)) = (CString::new("graffux-wgpu"), CString::new(line.replace('\0', " ")))
    {
        // SAFETY: both pointers are NUL-terminated strings that outlive the call.
        unsafe { __android_log_write(prio, tag.as_ptr(), text.as_ptr()) };
    }
}

/// Starts capturing for one engine creation: installs the capturing logger once (a host that
/// already has its own is left alone and the report says so) and raises the log level so wgpu-hal's
/// debug records reach it. Pair with [`end`].
pub(crate) fn begin(report: &mut Report) {
    INSTALL.call_once(|| {
        let ok = log::set_logger(&Capture).is_ok();
        *lock(&INSTALLED) = ok;
    });
    lock(&CAPTURED).clear();
    if *lock(&INSTALLED) {
        log::set_max_level(log::LevelFilter::Debug);
    } else {
        report.note("(wgpu log capture unavailable: another logger is installed)");
    }
}

/// Puts the log level back to warnings only once creation is over.
pub(crate) fn end() {
    if *lock(&INSTALLED) {
        log::set_max_level(log::LevelFilter::Warn);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adapter_line_names_everything_needed_to_diagnose() {
        let l = adapter_line(0, "Vulkan", "Adreno (TM) 619", "IntegratedGpu", "Qualcomm", "512.530", false);
        assert_eq!(
            l,
            "adapter[0] backend=Vulkan name=\"Adreno (TM) 619\" type=IntegratedGpu \
             driver=\"Qualcomm 512.530\" compute_shaders=NO"
        );
    }

    #[test]
    fn limits_line_shows_required_against_available() {
        let l = limits_line(524288, 134217728, 268435456, 4, 1024, 1024, 1024, 16);
        assert!(l.contains("layer_state_bytes=524288 (max_storage_buffer_binding_size=134217728"));
        assert!(l.contains("storage_buffers_per_stage=4 (need>=3)"));
        assert!(l.contains("workgroup tile 16x16 (max_invocations=1024"));
    }

    #[test]
    fn report_text_appends_wgpu_logs_under_a_heading() {
        let mut r = Report::default();
        r.note("backends requested: Backends(VULKAN | GL)");
        r.note("no usable adapter");
        let t = r.text(&["DEBUG wgpu_hal::vulkan::adapter: Adapter is not Vulkan compliant, hiding adapter: X".into()]);
        assert_eq!(
            t,
            "backends requested: Backends(VULKAN | GL)\nno usable adapter\nwgpu log:\n  \
             DEBUG wgpu_hal::vulkan::adapter: Adapter is not Vulkan compliant, hiding adapter: X"
        );
        assert_eq!(Report::default().text(&[]), "");
    }

    #[test]
    fn capture_keeps_adapter_debug_and_all_warnings_but_not_noise() {
        use log::Level::*;
        assert!(Capture::wanted(Debug, "wgpu_hal::vulkan::adapter"));
        assert!(Capture::wanted(Debug, "wgpu_hal::gles::instance"));
        assert!(Capture::wanted(Warn, "wgpu_core::instance"));
        assert!(Capture::wanted(Error, "graffux_wgpu::engine"));
        assert!(!Capture::wanted(Debug, "wgpu_core::device::resource"));
        assert!(!Capture::wanted(Warn, "some_other_crate"));
        assert!(!Capture::wanted(Trace, "wgpu_hal::vulkan::adapter"));
    }
}
