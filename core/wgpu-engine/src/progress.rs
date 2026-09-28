//! Display-side progress for multipass rendering: when will a tile's full-quality result land, and
//! what should be on screen until then. Pure logic, unit-tested without an adapter.
//!
//! The engine renders a small number of *real* quality levels (a draft, then the final dab). The
//! display in between is continuous: every frame it shows the draft with its feather revealed a
//! little further, parameterized by a per-tile quality `q` that runs from 0 toward a cap as the
//! estimated arrival of the final result approaches ([`TileAnim::advance`]). When the final result
//! lands, the tile eases from exactly what was on screen to it ([`TileAnim::land`]), so there is
//! no step at a level arrival.
//!
//! # ETA
//!
//! [`EtaEstimator`] keeps two exponential moving averages measured on the device:
//!
//! * refinement throughput while the GPU is refining, in work units per millisecond of GPU time;
//! * the duty cycle: the fraction of wall time the engine actually spends refining (what is left
//!   of each frame after drafts and present).
//!
//! The expected wall time until `backlog` units have been refined is then
//! `backlog / (throughput * duty)`. The engine computes the backlog of a tile as the work queued in
//! the final FIFO up to and including the last item that touches it.

/// Exponential moving average with a warm-up: the first sample is taken as is.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Ema {
    value: f64,
    samples: u32,
}

impl Ema {
    pub fn add(&mut self, sample: f64, alpha: f64) {
        if !sample.is_finite() {
            return;
        }
        self.value = if self.samples == 0 {
            sample
        } else {
            self.value + (sample - self.value) * alpha
        };
        self.samples = self.samples.saturating_add(1);
    }

    pub fn get(&self) -> Option<f64> {
        (self.samples > 0).then_some(self.value)
    }
}

/// Refinement ETA from measured throughput and duty cycle (module doc).
#[derive(Clone, Debug)]
pub struct EtaEstimator {
    throughput: Ema,
    duty: Ema,
    /// Smoothing factor of both averages.
    pub alpha: f64,
    /// Assumed while nothing has been measured yet.
    pub default_eta_ms: f64,
}

impl Default for EtaEstimator {
    fn default() -> Self {
        EtaEstimator {
            throughput: Ema::default(),
            duty: Ema::default(),
            alpha: 0.2,
            default_eta_ms: 200.0,
        }
    }
}

impl EtaEstimator {
    /// `units` of refinement took `busy_ms` of GPU time.
    pub fn observe_work(&mut self, units: f64, busy_ms: f64) {
        if units > 0.0 && busy_ms > 0.0 {
            self.throughput.add(units / busy_ms, self.alpha);
        }
    }

    /// Over the last `period_ms` of wall time, refinement ran for `busy_ms`.
    pub fn observe_duty(&mut self, busy_ms: f64, period_ms: f64) {
        if period_ms > 0.0 {
            self.duty.add((busy_ms / period_ms).clamp(0.0, 1.0), self.alpha);
        }
    }

    /// Work units per millisecond of GPU time, once measured.
    pub fn throughput(&self) -> Option<f64> {
        self.throughput.get()
    }

    pub fn duty(&self) -> Option<f64> {
        self.duty.get()
    }

    /// Wall milliseconds until `backlog_units` more units have been refined.
    pub fn eta_ms(&self, backlog_units: f64) -> f64 {
        if backlog_units <= 0.0 {
            return 0.0;
        }
        match self.throughput.get() {
            Some(rate) if rate > 0.0 => {
                // No duty measured yet: assume refinement gets a quarter of the time.
                let duty = self.duty.get().unwrap_or(0.25).max(0.02);
                backlog_units / (rate * duty)
            }
            _ => self.default_eta_ms,
        }
    }

    /// [`EtaEstimator::eta_ms`] in frames of `frame_ms`.
    pub fn eta_frames(&self, backlog_units: f64, frame_ms: f64) -> f64 {
        self.eta_ms(backlog_units) / frame_ms.max(1.0)
    }
}

/// How the display animates (see the module doc).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AnimParams {
    /// The furthest the draft's feather is revealed before the real result lands (0..1).
    pub q_cap: f32,
    /// Ease from what is on screen to the landed result, in ms. 0 = snap.
    pub landing_ms: f32,
}

impl Default for AnimParams {
    fn default() -> Self {
        AnimParams {
            q_cap: 0.8,
            landing_ms: 150.0,
        }
    }
}

/// A tile's display state. `q` only ever grows within an epoch (from an empty tile to the end of
/// its landing).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct TileAnim {
    /// Feather reveal of the draft content (0 = trimmed edge, 1 = full feather).
    pub q: f32,
    /// The tile holds draft content that has not landed as final yet.
    pub has_draft: bool,
    /// An ease toward a landed result is running: (start ms, `q` at the start).
    pub landing: Option<(f64, f32)>,
    last_ms: Option<f64>,
}

impl TileAnim {
    /// True while something about the tile changes from frame to frame.
    pub fn active(&self) -> bool {
        self.has_draft || self.landing.is_some()
    }

    /// New draft content was drawn into the tile. A new epoch starts only on an idle tile, so
    /// content already on screen never regresses to an earlier reveal.
    pub fn on_draft(&mut self, now_ms: f64) {
        if !self.active() {
            self.q = 0.0;
            self.last_ms = Some(now_ms);
        }
        self.has_draft = true;
    }

    /// Moves `q` toward the cap so it arrives there at the estimated landing time. Re-estimating
    /// every frame retimes smoothly: a later ETA slows the remaining progress, never reverses it.
    pub fn advance(&mut self, now_ms: f64, eta_ms: f64, p: &AnimParams) {
        let dt = self.last_ms.map_or(0.0, |t| (now_ms - t).max(0.0));
        self.last_ms = Some(now_ms);
        if !self.has_draft || self.landing.is_some() {
            return;
        }
        let cap = p.q_cap.clamp(0.0, 1.0);
        if self.q >= cap {
            return;
        }
        // Never faster than the landing ease itself (at least 50 ms end to end): a result due within
        // a frame must not flash the whole feather in one step. If it lands first, the landing
        // continues from wherever the reveal got to.
        let pace = eta_ms.max(p.landing_ms as f64).max(50.0);
        let step = if pace <= dt { 1.0 } else { dt / pace };
        self.q = (self.q + (cap - self.q) * step as f32).min(cap);
    }

    /// The final result for the tile landed: start the ease from the current display.
    pub fn land(&mut self, now_ms: f64, p: &AnimParams) {
        // Start from the quality on screen right now (mid-way through an earlier landing, that is
        // further than `q`), so the parameter never steps.
        let q0 = if self.landing.is_some() || self.has_draft {
            self.quality(now_ms, p)
        } else {
            1.0
        };
        self.q = self.q.max(q0);
        self.landing = Some((now_ms, q0));
        self.has_draft = false;
        self.last_ms = Some(now_ms);
    }

    /// Weight of the landed result on screen, 0..1 (1 when no landing is running).
    pub fn landing_weight(&self, now_ms: f64, p: &AnimParams) -> f32 {
        match self.landing {
            None => 1.0,
            Some(_) if p.landing_ms <= 0.0 => 1.0,
            Some((start, _)) => ((now_ms - start) / p.landing_ms as f64).clamp(0.0, 1.0) as f32,
        }
    }

    /// The displayed quality: `q` while drafting; during a landing, from its starting `q` up to 1
    /// with the landing weight; 1 once landed.
    pub fn quality(&self, now_ms: f64, p: &AnimParams) -> f32 {
        match self.landing {
            Some((_, q0)) => {
                let w = self.landing_weight(now_ms, p);
                (q0 + (1.0 - q0) * w).max(self.q)
            }
            None if self.has_draft => self.q,
            None => 1.0,
        }
    }

    /// Ends a finished landing. Returns true when it did (the slot holding the "from" image can be
    /// freed). New draft content that arrived during the landing continues at full reveal.
    pub fn finish_landing(&mut self, now_ms: f64, p: &AnimParams) -> bool {
        if self.landing.is_some() && self.landing_weight(now_ms, p) >= 1.0 {
            self.q = self.q.max(1.0);
            self.landing = None;
            return true;
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn eta_from_throughput_and_duty() {
        let mut e = EtaEstimator::default();
        assert_eq!(e.eta_ms(100.0), 200.0);
        assert_eq!(e.eta_ms(0.0), 0.0);
        e.observe_work(1000.0, 10.0); // 100 units per GPU ms
        e.observe_duty(4.0, 16.0); // a quarter of each frame
        assert!((e.eta_ms(2500.0) - 100.0).abs() < 1e-9);
        assert!((e.eta_frames(2500.0, 16.0) - 6.25).abs() < 1e-9);
    }

    #[test]
    fn eta_tracks_a_throughput_change() {
        let mut e = EtaEstimator::default();
        for _ in 0..30 {
            e.observe_work(100.0, 1.0);
            e.observe_duty(8.0, 16.0);
        }
        assert!((e.eta_ms(1000.0) - 20.0).abs() < 0.5);
        // The device throttles to a quarter of the speed: the estimate follows within ~20 samples.
        for _ in 0..20 {
            e.observe_work(25.0, 1.0);
            e.observe_duty(8.0, 16.0);
        }
        // 0.8^20 of the old rate is left in the average: within 5% of the new truth.
        assert!((e.eta_ms(1000.0) - 80.0).abs() < 4.0, "{}", e.eta_ms(1000.0));
    }

    /// A steady load on a synthetic device: the ETA predicted for each submission when it is queued
    /// stays within a bounded error of when its work actually completes.
    #[test]
    fn eta_error_stays_bounded_under_steady_load() {
        let frame_ms = 16.0;
        let refine_ms_per_frame = 6.0;
        let units_per_gpu_ms = 50.0; // true device speed
        let per_submission = 200.0; // units
        let mut e = EtaEstimator::default();
        let mut queue: std::collections::VecDeque<(f64, f64, f64)> = Default::default(); // (left, t0, predicted)
        let mut errors = Vec::new();
        for frame in 0..400 {
            let now = frame as f64 * frame_ms;
            if frame % 2 == 0 {
                let backlog: f64 = queue.iter().map(|q| q.0).sum::<f64>() + per_submission;
                queue.push_back((per_submission, now, now + e.eta_ms(backlog)));
            }
            // Refine for the frame's leftover time.
            let mut capacity = units_per_gpu_ms * refine_ms_per_frame;
            let mut done = 0.0;
            while capacity > 0.0 {
                let Some(head) = queue.front_mut() else { break };
                let take = head.0.min(capacity);
                head.0 -= take;
                capacity -= take;
                done += take;
                if head.0 <= 0.0 {
                    let (_, _, predicted) = queue.pop_front().unwrap();
                    let actual = now + frame_ms; // lands by the end of this frame
                    if frame > 60 {
                        errors.push((actual - predicted).abs());
                    }
                }
            }
            if done > 0.0 {
                e.observe_work(done, done / units_per_gpu_ms);
            }
            e.observe_duty(done / units_per_gpu_ms, frame_ms);
        }
        let max_err = errors.iter().cloned().fold(0.0, f64::max);
        assert!(!errors.is_empty());
        // Within two frames of the truth, whatever the queue depth.
        assert!(max_err <= 2.0 * frame_ms, "max ETA error {max_err} ms");
    }

    fn params() -> AnimParams {
        AnimParams {
            q_cap: 0.8,
            landing_ms: 150.0,
        }
    }

    /// Frames at irregular timestamps, a moving ETA (early and late arrivals), new drafts arriving
    /// mid-way: the displayed quality never decreases, never jumps at a landing, and ends at 1.
    #[test]
    fn displayed_quality_is_monotonic_and_continuous_across_landings() {
        let p = params();
        for (eta_bias, first_land) in [(1.0, 500.0), (0.3, 500.0), (3.0, 120.0), (1.0, 40.0)] {
            let mut land_at = first_land;
            let mut t = TileAnim::default();
            t.on_draft(0.0);
            let mut now = 0.0;
            let mut prev = t.quality(now, &p);
            let mut landed = false;
            let mut step = 0;
            while now < 1500.0 {
                step += 1;
                now += 7.0 + (step % 5) as f64 * 4.0; // 7..23 ms frames
                if !landed && now >= land_at {
                    let before = t.quality(now, &p);
                    t.land(now, &p);
                    let after = t.quality(now, &p);
                    assert!((after - before).abs() < 1e-6, "step at landing: {before} -> {after}");
                    landed = true;
                }
                if step == 20 {
                    // More paint into the tile mid-way. On a tile that is still showing earlier
                    // paint this joins the running epoch (no regression); on an idle tile it is new
                    // paint and starts its own epoch from the trimmed draft.
                    let idle = !t.active();
                    t.on_draft(now);
                    if idle {
                        prev = t.quality(now, &p);
                        landed = false;
                        land_at = now + 200.0;
                    }
                }
                let eta = ((land_at - now) * eta_bias).max(0.0);
                t.advance(now, eta, &p);
                t.finish_landing(now, &p);
                let q = t.quality(now, &p);
                assert!(q + 1e-6 >= prev, "quality fell {prev} -> {q} at {now}");
                assert!(q - prev <= 0.2, "jump {prev} -> {q}");
                prev = q;
            }
            assert_eq!(t.quality(now, &p), 1.0);
            assert!(!t.active() || t.has_draft);
        }
    }

    #[test]
    fn late_results_slow_the_reveal_near_its_end_instead_of_overshooting() {
        let p = params();
        let mut t = TileAnim::default();
        t.on_draft(0.0);
        // ETA keeps saying "16 ms more" for two seconds: q approaches the cap, never reaches 1.
        let mut now = 0.0;
        for _ in 0..120 {
            now += 16.0;
            t.advance(now, 16.0, &p);
        }
        assert!(t.q <= p.q_cap && t.q > p.q_cap - 1e-3);
        t.land(now, &p);
        assert!(t.landing_weight(now + 75.0, &p) > 0.49 && t.landing_weight(now + 75.0, &p) < 0.51);
        assert!(t.finish_landing(now + 150.0, &p));
        assert_eq!(t.quality(now + 150.0, &p), 1.0);
    }

    #[test]
    fn zero_landing_time_snaps() {
        let p = AnimParams {
            q_cap: 0.8,
            landing_ms: 0.0,
        };
        let mut t = TileAnim::default();
        t.on_draft(0.0);
        t.land(10.0, &p);
        assert_eq!(t.landing_weight(10.0, &p), 1.0);
        assert!(t.finish_landing(10.0, &p));
    }
}
