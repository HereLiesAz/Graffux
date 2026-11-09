//! Bounded waits on the GPU.
//!
//! Every readback maps a staging buffer and then blocks until the queued work that fills it is done. With
//! `PollType::wait_indefinitely()` a GPU that never finishes (a driver hang that is not reported as a lost device)
//! blocks the calling thread forever, and on Android the editor's stroke thread holds the Kotlin engine's lock while
//! it waits, so the UI thread stalls behind it too. [`map_read`] gives up after a timeout instead and says so, so the
//! caller can fail the call (and the engine can stop using the device) rather than hang.

use std::ops::Range;
use std::time::Duration;

/// How long a readback may wait for the GPU. A frame of dabs or a whole-layer read finishes in milliseconds, even
/// on software Vulkan; five seconds is far beyond any queued backlog a healthy device has, and well inside the
/// time after which Android declares the app unresponsive. These waits run on the caller's thread: callers keep them
/// off the UI and input threads (nothing calls the standalone entries from the app yet).
pub const READBACK_TIMEOUT: Duration = Duration::from_secs(5);

/// The wait for work submitted so far (`submission: None`, the device's most recent submission at the time of the poll,
/// which includes anything other threads queue after this caller's own) or for exactly one submission (`Some`, the
/// index `Queue::submit` returned). Callers that share a device wait on their own index.
pub fn wait_poll(
    submission: Option<wgpu::SubmissionIndex>,
    timeout: Duration,
) -> wgpu::PollType {
    wgpu::PollType::Wait {
        submission_index: submission,
        timeout: Some(timeout),
    }
}

/// Why [`map_read`] returned no data.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReadFailure {
    /// The GPU did not finish within the timeout. The device may be hung: the caller should stop using it.
    TimedOut,
    /// The device was lost, the map was refused, or the mapped range was unavailable.
    Failed,
}

/// Blocks until the work submitted so far is done, or `timeout` passes. False on timeout or device error.
pub fn wait_bounded(device: &wgpu::Device, timeout: Duration) -> bool {
    device
        .poll(wgpu::PollType::Wait {
            submission_index: None,
            timeout: Some(timeout),
        })
        .is_ok()
}

/// [`wait_bounded`] that says whether it timed out or failed.
pub fn wait_for(device: &wgpu::Device, timeout: Duration) -> Result<(), ReadFailure> {
    match device.poll(wgpu::PollType::Wait {
        submission_index: None,
        timeout: Some(timeout),
    }) {
        Ok(_) => Ok(()),
        Err(wgpu::PollError::Timeout) => Err(ReadFailure::TimedOut),
        Err(_) => Err(ReadFailure::Failed),
    }
}

/// Waits for one submission (the index `Queue::submit` returned) for at most `timeout`. Only that submission's own
/// work counts, so a device shared with other users does not make it look stalled.
pub fn wait_submission(
    device: &wgpu::Device,
    submission: wgpu::SubmissionIndex,
    timeout: Duration,
) -> Result<(), ReadFailure> {
    match device.poll(wgpu::PollType::Wait {
        submission_index: Some(submission),
        timeout: Some(timeout),
    }) {
        Ok(_) => Ok(()),
        Err(wgpu::PollError::Timeout) => Err(ReadFailure::TimedOut),
        Err(_) => Err(ReadFailure::Failed),
    }
}

/// Maps `range` of `buffer` (a `MAP_READ` staging buffer the caller has already filled by a submitted copy), waits
/// at most `timeout` for the GPU, runs `read` over the bytes and unmaps. The buffer is left unmapped on every path,
/// including a timeout (the pending map is cancelled, or the next `map_async` on the buffer would be a validation
/// error).
pub fn map_read<R>(
    device: &wgpu::Device,
    buffer: &wgpu::Buffer,
    range: Range<u64>,
    timeout: Duration,
    read: impl FnOnce(&[u8]) -> R,
) -> Result<R, ReadFailure> {
    map_read_with(
        device,
        buffer,
        range,
        |device| {
            device.poll(wgpu::PollType::Wait {
                submission_index: None,
                timeout: Some(timeout),
            })
        },
        read,
    )
}

/// [`map_read`] with the wait supplied by the caller: `wait` must block until the map has completed (or fail with
/// [`wgpu::PollError::Timeout`] / another error). Tests use it to stand in for a GPU that never finishes, which a
/// software rasteriser (it runs the work inside `submit`) cannot be made to be.
pub fn map_read_with<R>(
    device: &wgpu::Device,
    buffer: &wgpu::Buffer,
    range: Range<u64>,
    wait: impl FnOnce(&wgpu::Device) -> Result<wgpu::PollStatus, wgpu::PollError>,
    read: impl FnOnce(&[u8]) -> R,
) -> Result<R, ReadFailure> {
    let slice = buffer.slice(range);
    let (tx, rx) = std::sync::mpsc::channel();
    slice.map_async(wgpu::MapMode::Read, move |res| {
        let _ = tx.send(res.is_ok());
    });
    if let Err(err) = wait(device) {
        buffer.unmap();
        return Err(match err {
            wgpu::PollError::Timeout => ReadFailure::TimedOut,
            _ => ReadFailure::Failed,
        });
    }
    // The wait returns once the callbacks have run, so the answer is already in the channel.
    match settle(rx.try_recv()) {
        MapState::Mapped => {}
        MapState::Refused => return Err(ReadFailure::Failed),
        MapState::Pending => {
            buffer.unmap();
            return Err(ReadFailure::Failed);
        }
    }
    let out = match slice.get_mapped_range() {
        Ok(mapped) => Ok(read(&mapped)),
        Err(_) => Err(ReadFailure::Failed),
    };
    buffer.unmap();
    out
}

/// What the map callback's channel said after the wait.
#[derive(Debug, PartialEq, Eq)]
enum MapState {
    /// The buffer is mapped.
    Mapped,
    /// The map was refused. wgpu-core has already put the buffer back to idle before calling back, so there is
    /// nothing to unmap: unmapping an idle buffer is a validation error, which unscoped callers would see as an
    /// uncaptured error (a panic by default).
    Refused,
    /// The callback has not run: the map is still pending and must be cancelled by `unmap`, which is legal, so that
    /// the next `map_async` on the buffer does not find a map in flight.
    Pending,
}

fn settle(r: Result<bool, std::sync::mpsc::TryRecvError>) -> MapState {
    match r {
        Ok(true) => MapState::Mapped,
        Ok(false) => MapState::Refused,
        Err(_) => MapState::Pending,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::{channel, TryRecvError};

    #[test]
    fn only_a_callback_that_never_ran_needs_an_unmap() {
        assert_eq!(settle(Ok(true)), MapState::Mapped);
        assert_eq!(settle(Ok(false)), MapState::Refused);
        let (tx, rx) = channel::<bool>();
        assert_eq!(settle(rx.try_recv()), MapState::Pending);
        drop(tx);
        assert_eq!(settle(Err(TryRecvError::Disconnected)), MapState::Pending);
    }
}
