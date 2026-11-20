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

/// How long the FIRST wait on a freshly created device may take. The wait covers every queued piece of GPU work,
/// and a cold device compiles its compute pipelines lazily in the driver on the first dispatch (seconds on a
/// phone GPU with a cold shader cache), so the steady-state [`READBACK_TIMEOUT`] would turn a slow-but-healthy
/// first dispatch into a "hang". Still bounded: a device that has not answered in this long is not warming up. It
/// stays under Android's input-dispatch ANR window only because the editor keeps this wait off the UI thread.
pub const FIRST_READBACK_TIMEOUT: Duration = Duration::from_secs(30);

/// A wait slower than this is logged with its elapsed time (the tag the owner greps is `graffux-wgpu`).
pub const SLOW_WAIT_LOG: Duration = Duration::from_secs(1);

/// How long a stalled engine's recovery probe waits for the device to drain before it decides the GPU is really
/// hung (and leaves the engine failed so the caller recreates it).
pub const RECOVERY_PROBE: Duration = Duration::from_millis(250);

/// Clock and pause used by the readback driver. The real one is wall time; tests inject a fake so a "6 second"
/// first dispatch costs no real time.
pub trait WaitClock {
    /// Time since the wait began.
    fn elapsed(&self) -> Duration;
    /// Gives other threads (or the GPU driver) a moment between two looks at the map callback.
    fn pause(&self);
}

/// Wall-clock [`WaitClock`].
pub struct SystemClock(std::time::Instant);

impl SystemClock {
    pub fn start() -> Self {
        SystemClock(std::time::Instant::now())
    }
}

impl WaitClock for SystemClock {
    fn elapsed(&self) -> Duration {
        self.0.elapsed()
    }
    fn pause(&self) {
        std::thread::sleep(Duration::from_micros(200));
    }
}

/// Per-engine readback policy and state: which timeout applies now, and whether the last wait stalled.
///
/// A timeout used to set the engine's permanent error flag. It now sets `stalled`, which fails the rest of the
/// current stroke fast but is cleared by the next stroke's [`ReadbackWatch::recover`] if the device is merely slow
/// and not hung; a genuinely hung device stays failed and the caller (Kotlin) recreates the engine.
pub struct ReadbackWatch {
    timeout: Duration,
    first_use: Duration,
    warm: bool,
    stalled: bool,
}

impl ReadbackWatch {
    pub fn new() -> Self {
        Self::with_timeouts(READBACK_TIMEOUT, FIRST_READBACK_TIMEOUT)
    }

    pub fn with_timeouts(timeout: Duration, first_use: Duration) -> Self {
        ReadbackWatch { timeout, first_use, warm: false, stalled: false }
    }

    /// Sets both timeouts (tests shorten them together).
    pub fn set_timeouts(&mut self, timeout: Duration, first_use: Duration) {
        self.timeout = timeout;
        self.first_use = first_use;
    }

    /// The budget for the next wait: the long first-use allowance until one wait has succeeded.
    pub fn budget(&self) -> Duration {
        if self.warm {
            self.timeout
        } else {
            self.first_use.max(self.timeout)
        }
    }

    pub fn is_stalled(&self) -> bool {
        self.stalled
    }

    /// Records one wait's outcome; true when the call may proceed. `real_work`: the wait covered a real readback of
    /// dispatched work (see the warm-up rule below). A timeout stalls the engine for this stroke
    /// only. Slow waits and timeouts are logged with their elapsed time.
    pub fn settle(&mut self, result: Result<(), ReadFailure>, elapsed: Duration, real_work: bool) -> bool {
        match result {
            Ok(()) => {
                if elapsed >= SLOW_WAIT_LOG {
                    log::warn!(
                        "graffux-wgpu: slow GPU wait, {} ms (first use: {})",
                        elapsed.as_millis(),
                        !self.warm
                    );
                }
                // Only a wait on real dispatched work ends the first-use allowance: a trivial wait (an empty
                // submission, a timer probe) says nothing about the cold pipeline compile still to come.
                if real_work {
                    self.warm = true;
                }
                true
            }
            Err(ReadFailure::TimedOut) => {
                log::error!(
                    "graffux-wgpu: readback timed out after {} ms (budget {:?}, first use: {}); \
                     failing this stroke, the engine is probed again on the next one",
                    elapsed.as_millis(),
                    self.budget(),
                    !self.warm
                );
                self.stalled = true;
                false
            }
            Err(ReadFailure::Failed) => {
                log::warn!("graffux-wgpu: readback failed after {} ms", elapsed.as_millis());
                false
            }
        }
    }

    /// Called when a new stroke begins. A stalled engine is given a fresh chance when `drained` (a short bounded
    /// wait for the device to go idle) succeeds; otherwise it stays stalled (a real hang).
    pub fn recover(&mut self, drained: impl FnOnce(Duration) -> bool) {
        if !self.stalled {
            return;
        }
        if drained(RECOVERY_PROBE) {
            log::warn!("graffux-wgpu: the GPU answered again; the engine is usable");
            self.stalled = false;
        } else {
            log::error!("graffux-wgpu: the GPU is still not answering; the engine stays failed");
        }
    }
}

/// The one path every engine wait goes through (readbacks, direct readback, wet readback, frame-timer waits): takes
/// the watch's current budget, runs `run` with it, and settles the outcome with the clock's elapsed time. Free of any
/// device, so the engine's use of the budget, the first-use rule and the stall is testable with a fake clock.
pub fn watched<C: WaitClock>(
    watch: &std::cell::RefCell<ReadbackWatch>,
    clock: &C,
    real_work: bool,
    run: impl FnOnce(Duration) -> Result<(), ReadFailure>,
) -> bool {
    let budget = watch.borrow().budget();
    let result = run(budget);
    watch.borrow_mut().settle(result, clock.elapsed(), real_work)
}

impl Default for ReadbackWatch {
    fn default() -> Self {
        Self::new()
    }
}

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
    map_read_within(
        device,
        buffer,
        range,
        timeout,
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
/// software rasteriser (it runs the work inside `submit`) cannot be made to be. The wait and the map callback's
/// arrival together are bounded by [`READBACK_TIMEOUT`] (one clock, started before `wait`).
pub fn map_read_with<R>(
    device: &wgpu::Device,
    buffer: &wgpu::Buffer,
    range: Range<u64>,
    wait: impl FnOnce(&wgpu::Device) -> Result<wgpu::PollStatus, wgpu::PollError>,
    read: impl FnOnce(&[u8]) -> R,
) -> Result<R, ReadFailure> {
    map_read_within(device, buffer, range, READBACK_TIMEOUT, wait, read)
}

/// [`map_read_with`] with the budget stated: it bounds `wait` and the map callback's arrival together.
pub fn map_read_within<R>(
    device: &wgpu::Device,
    buffer: &wgpu::Buffer,
    range: Range<u64>,
    budget: Duration,
    wait: impl FnOnce(&wgpu::Device) -> Result<wgpu::PollStatus, wgpu::PollError>,
    read: impl FnOnce(&[u8]) -> R,
) -> Result<R, ReadFailure> {
    let slice = buffer.slice(range);
    let (tx, rx) = std::sync::mpsc::channel();
    slice.map_async(wgpu::MapMode::Read, move |res| {
        let _ = tx.send(res.is_ok());
    });
    let clock = SystemClock::start();
    let settled = drive_readback(
        &clock,
        budget,
        || {
            wait(device).map(|_| ()).map_err(|err| match err {
                wgpu::PollError::Timeout => ReadFailure::TimedOut,
                _ => ReadFailure::Failed,
            })
        },
        || settle(rx.try_recv()),
        || {
            let _ = device.poll(wgpu::PollType::Poll);
        },
    );
    match settled {
        Ok(MapState::Mapped) => {}
        Ok(MapState::Refused) => return Err(ReadFailure::Failed),
        // `Pending` never comes back from the driver; a failed or timed-out wait leaves the map in flight, which
        // `unmap` cancels (legal) so that the next `map_async` on the buffer does not find one.
        Ok(MapState::Pending) | Err(_) => {
            buffer.unmap();
            return Err(settled.err().unwrap_or(ReadFailure::Failed));
        }
    }
    let out = match slice.get_mapped_range() {
        Ok(mapped) => Ok(read(&mapped)),
        Err(_) => Err(ReadFailure::Failed),
    };
    buffer.unmap();
    out
}

/// The wait protocol, free of any device so it can be tested with a fake clock: run `wait` (it blocks for the GPU
/// and fails with `TimedOut` when its own bound passes), then look for the map callback's answer. The callback may
/// not have run when the wait returns (another thread's poll can hold it), so keep looking, re-polling between
/// looks, until `budget` is spent: only then is the map considered lost (`TimedOut`, not a silent drop).
pub(crate) fn drive_readback<C: WaitClock>(
    clock: &C,
    budget: Duration,
    wait: impl FnOnce() -> Result<(), ReadFailure>,
    mut try_map: impl FnMut() -> MapState,
    mut repoll: impl FnMut(),
) -> Result<MapState, ReadFailure> {
    wait()?;
    loop {
        match try_map() {
            MapState::Pending => {}
            done => return Ok(done),
        }
        if clock.elapsed() >= budget {
            return Err(ReadFailure::TimedOut);
        }
        repoll();
        clock.pause();
    }
}

/// What the map callback's channel said after the wait.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum MapState {
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

pub(crate) fn settle(r: Result<bool, std::sync::mpsc::TryRecvError>) -> MapState {
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

    use std::cell::Cell;

    /// A clock that only moves when the fake GPU says time passed.
    struct FakeClock {
        now: Cell<Duration>,
        pauses: Cell<u32>,
    }
    impl FakeClock {
        fn new() -> Self {
            FakeClock { now: Cell::new(Duration::ZERO), pauses: Cell::new(0) }
        }
        fn advance(&self, d: Duration) {
            self.now.set(self.now.get() + d);
        }
    }
    impl WaitClock for FakeClock {
        fn elapsed(&self) -> Duration {
            self.now.get()
        }
        fn pause(&self) {
            self.pauses.set(self.pauses.get() + 1);
            self.advance(Duration::from_millis(1));
        }
    }

    /// The fake GPU's `device.poll(Wait { timeout })`: the work takes `takes`; it times out when that exceeds the
    /// bound (and then the bound is what passed), otherwise it succeeds after `takes`.
    fn fake_wait(clock: &FakeClock, takes: Duration, bound: Duration) -> Result<(), ReadFailure> {
        if takes > bound {
            clock.advance(bound);
            Err(ReadFailure::TimedOut)
        } else {
            clock.advance(takes);
            Ok(())
        }
    }

    /// One whole fake readback as the engine runs it: watch picks the budget, the wait runs inside it, the watch
    /// settles the result. Returns whether the stroke's readback succeeded.
    fn fake_readback(watch: &mut ReadbackWatch, takes: Duration, callback_after: Duration) -> bool {
        let clock = FakeClock::new();
        let budget = watch.budget();
        let result = drive_readback(
            &clock,
            budget,
            || fake_wait(&clock, takes, budget),
            || if clock.elapsed() >= takes + callback_after { MapState::Mapped } else { MapState::Pending },
            || {},
        )
        .map(|_| ());
        watch.settle(result, clock.elapsed(), true)
    }

    #[test]
    fn a_slow_but_succeeding_first_readback_does_not_stall_the_engine() {
        // Lazy driver pipeline compilation: the very first wait takes 6 s of (fake) time, past the steady-state
        // 5 s. It must succeed and must not stall the engine.
        let mut watch = ReadbackWatch::new();
        assert!(fake_readback(&mut watch, Duration::from_secs(6), Duration::ZERO));
        assert!(!watch.is_stalled());
        // After that the first-use allowance is gone: the steady-state bound applies and a 6 s wait is a stall.
        assert_eq!(watch.budget(), READBACK_TIMEOUT);
        assert!(!fake_readback(&mut watch, Duration::from_secs(6), Duration::ZERO));
        assert!(watch.is_stalled());
    }

    #[test]
    fn a_map_callback_that_arrives_after_the_wait_returned_is_waited_for() {
        // The wait is over at once but the callback only runs 800 ms later (another thread held it): no drop.
        let mut watch = ReadbackWatch::new();
        assert!(fake_readback(&mut watch, Duration::ZERO, Duration::from_millis(800)));
        assert!(!watch.is_stalled());
    }

    #[test]
    fn a_callback_that_never_arrives_ends_at_the_deadline_as_a_timeout() {
        let clock = FakeClock::new();
        let r = drive_readback(&clock, Duration::from_secs(5), || Ok(()), || MapState::Pending, || {});
        assert_eq!(r, Err(ReadFailure::TimedOut));
        assert!(clock.elapsed() >= Duration::from_secs(5) && clock.elapsed() < Duration::from_secs(6));
    }

    #[test]
    fn a_genuine_hang_ends_and_the_next_stroke_gets_a_fresh_chance() {
        let mut watch = ReadbackWatch::new();
        watch.settle(Ok(()), Duration::ZERO, true); // warmed up: steady-state bound
        let hang = Duration::from_secs(3600);
        assert!(!fake_readback(&mut watch, hang, Duration::ZERO), "the hang guard still fires");
        assert!(watch.is_stalled(), "the rest of this stroke fails fast");
        // The next stroke: the device drained in time (it was slow, not hung) -> usable again, no permanent error.
        watch.recover(|probe| probe == RECOVERY_PROBE);
        assert!(!watch.is_stalled());
        assert!(fake_readback(&mut watch, Duration::from_millis(10), Duration::ZERO));
        // A device that is still hung does not recover: Kotlin will recreate the engine instead.
        assert!(!fake_readback(&mut watch, hang, Duration::ZERO));
        watch.recover(|_| false);
        assert!(watch.is_stalled());
    }

    #[test]
    fn a_refused_map_is_failed_not_a_timeout() {
        let clock = FakeClock::new();
        let r = drive_readback(&clock, Duration::from_secs(5), || Ok(()), || MapState::Refused, || {});
        assert_eq!(r, Ok(MapState::Refused));
        let mut watch = ReadbackWatch::new();
        assert!(!watch.settle(Err(ReadFailure::Failed), Duration::ZERO, true));
        assert!(!watch.is_stalled(), "a refused map does not stall the engine");
    }

    #[test]
    fn a_trivial_wait_before_the_first_real_readback_does_not_spend_the_first_use_allowance() {
        // Engine path: `watched` with a fake clock. A frame-timer style wait (not real work) succeeds first; the
        // first real readback then takes 6 s and must still get the long first-use budget.
        let watch = std::cell::RefCell::new(ReadbackWatch::new());
        let clock = FakeClock::new();
        assert!(watched(&watch, &clock, false, |_| Ok(())));
        assert_eq!(watch.borrow().budget(), FIRST_READBACK_TIMEOUT, "a trivial wait must not warm the engine");
        let ok = watched(&watch, &clock, true, |budget| fake_wait(&clock, Duration::from_secs(6), budget));
        assert!(ok, "the slow first real readback must succeed");
        assert!(!watch.borrow().is_stalled());
        assert_eq!(watch.borrow().budget(), READBACK_TIMEOUT, "a real readback ends the allowance");
    }

    #[test]
    fn the_engine_wrappers_hand_the_watch_budget_to_the_wait_and_record_a_timeout() {
        let watch = std::cell::RefCell::new(ReadbackWatch::with_timeouts(
            Duration::from_secs(5),
            Duration::from_secs(30),
        ));
        let clock = FakeClock::new();
        let mut seen = Vec::new();
        assert!(watched(&watch, &clock, true, |b| {
            seen.push(b);
            Ok(())
        }));
        assert!(!watched(&watch, &clock, true, |b| {
            seen.push(b);
            fake_wait(&clock, Duration::from_secs(60), b)
        }));
        assert_eq!(seen, vec![Duration::from_secs(30), Duration::from_secs(5)]);
        assert!(watch.borrow().is_stalled());
        watch.borrow_mut().recover(|_| true);
        assert!(!watch.borrow().is_stalled());
    }
}
