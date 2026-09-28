//! The multipass scheduler: pure logic, no wgpu, unit-tested without an adapter.
//!
//! Every submission (one stamp call: a frame's batch of dabs) produces up to N pass items,
//! pass 0 .. pass N-1 (the docs number them 1..N). Pass 0 is the *draft*: a cheap, low-resolution
//! rendering of the same stamp. The last pass is the full-quality dab, exactly what the engine
//! writes with multipass off. Passes in between are *clarity* passes. There is one FIFO queue per
//! pass.
//!
//! # The goal, and the rule it implies
//!
//! Full-quality rendering must never hold the user back, on any hardware: whatever the device, the
//! paint under the finger shows up on the next frame, and full quality arrives whenever the
//! hardware gets to it. So pass 0 is not merely high priority, it is a *hard guarantee*:
//!
//! * [`Scheduler::take_drafts`] hands out every pending draft item, and every entry point that does
//!   refinement work ([`Scheduler::run_refinement`]) takes all pending drafts first. No refinement
//!   item is ever handed out while a draft item is pending.
//! * Refinement (passes 1..N-1) only spends what is left of the frame after drafts and present.
//!
//! # Refinement order: base weight plus aging
//!
//! Among the refinement queues' heads, the scheduler picks the highest *effective priority*:
//!
//! ~~~text
//! priority(k, age) = base_weight[k] + aging_rate[k] * max(0, age - age_threshold[k])
//! ~~~
//!
//! * `k` is the pass index (1..N-1), `age` the milliseconds since the submission was queued.
//! * `base_weight` is strictly decreasing with `k`: with equal ages, clarity before final.
//! * Aging makes a long-waiting later pass outrank a fresh earlier one. With the defaults
//!   ([`SchedulerConfig::new`]): `base_weight[k] = N - 1 - k`, one aging rate
//!   `1 / overtake_ms` per millisecond, thresholds 0. A pass-`k` item then overtakes a fresh pass-`j`
//!   item (`j < k`) once it has waited `(k - j) * overtake_ms` longer.
//! * Ties go to the lower pass, then the older submission.
//!
//! This is what rules out starvation: an item's priority grows without bound while it waits, so a
//! sustained stream of fresh earlier-pass work can delay it by at most the overtake time plus the
//! older work already ahead of it (see the `no_starvation` tests).
//!
//! # Ordering constraints
//!
//! * **Per dab.** Pass `k` of a submission is eligible only when every earlier pass of the same
//!   submission has finished. Queues are FIFO, so that is "no item of this submission is still
//!   queued at a lower pass".
//! * **The final queue runs strictly in submission order**, and a final item is never merged with
//!   another: that is what keeps the committed layer byte-identical to the multipass-off path (the
//!   engine executes a final item as today's dispatch, split only spatially, which is exact).
//! * Draft and clarity items of consecutive submissions are merged into one dispatch when their
//!   `compat` keys match ([`Batch`]).
//!
//! # Partial items
//!
//! A refinement item may be too big for one frame's leftover budget. The executor may do part of it
//! (a spatial chunk of a stamp dispatch) and report it unfinished; the item stays at the head of
//! its queue with its remaining work. Every [`Scheduler::run_refinement`] call makes at least one
//! executor call when refinement work is eligible, so refinement progresses even under a zero
//! budget.

use std::collections::VecDeque;

/// Parameters of the effective-priority formula (module doc). Index = pass (0 = draft; its entries
/// exist for symmetry but are never used: drafts are not prioritized, they always run).
#[derive(Clone, Debug, PartialEq)]
pub struct SchedulerConfig {
    pub passes: usize,
    pub base_weight: Vec<f64>,
    /// Priority gained per millisecond of waiting.
    pub aging_rate: Vec<f64>,
    /// Waiting time before aging starts, in milliseconds.
    pub age_threshold_ms: Vec<f64>,
}

impl SchedulerConfig {
    /// `passes` levels (at least 2). `base_weight[k] = passes - 1 - k`, `aging_rate = 1 /
    /// overtake_ms`, no thresholds.
    pub fn new(passes: usize, overtake_ms: f64) -> SchedulerConfig {
        let passes = passes.max(2);
        let rate = if overtake_ms > 0.0 { 1.0 / overtake_ms } else { 0.0 };
        SchedulerConfig {
            passes,
            base_weight: (0..passes).map(|k| (passes - 1 - k) as f64).collect(),
            aging_rate: vec![rate; passes],
            age_threshold_ms: vec![0.0; passes],
        }
    }

    pub fn effective_priority(&self, pass: usize, age_ms: f64) -> f64 {
        self.base_weight[pass]
            + self.aging_rate[pass] * (age_ms - self.age_threshold_ms[pass]).max(0.0)
    }

    /// How much longer a pass-`later` item must wait than a pass-`earlier` item to outrank it
    /// (thresholds 0, equal rates). `None` if it never does.
    pub fn overtake_ms(&self, earlier: usize, later: usize) -> Option<f64> {
        let rate = self.aging_rate[later];
        if rate <= 0.0 {
            return None;
        }
        Some((self.base_weight[earlier] - self.base_weight[later]) / rate)
    }
}

/// One pass of one submission.
#[derive(Debug)]
pub struct Item<P> {
    pub seq: u64,
    pub pass: usize,
    pub enqueued_ms: f64,
    /// Estimated work, in the executor's units. Updated by the executor as it does part of it.
    pub cost: f64,
    /// Items with equal `Some` keys may share a dispatch (never used for the final pass).
    pub compat: Option<u64>,
    /// True once the executor has done part of it.
    pub started: bool,
    pub payload: P,
}

/// What a submission asks for at one pass.
pub struct PassSpec<P> {
    pub pass: usize,
    pub cost: f64,
    pub compat: Option<u64>,
    pub payload: P,
}

/// Consecutive items of one queue that share a dispatch.
pub type Batch<P> = Vec<Item<P>>;

/// What one refinement run did (for tests and diagnostics).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct RefineReport {
    pub drafts_run: usize,
    pub executor_calls: usize,
    pub cost_spent: f64,
    pub items_finished: usize,
}

pub struct Scheduler<P> {
    cfg: SchedulerConfig,
    queues: Vec<VecDeque<Item<P>>>,
    next_seq: u64,
}

impl<P> Scheduler<P> {
    pub fn new(cfg: SchedulerConfig) -> Scheduler<P> {
        let queues = (0..cfg.passes).map(|_| VecDeque::new()).collect();
        Scheduler {
            cfg,
            queues,
            next_seq: 1,
        }
    }

    pub fn config(&self) -> &SchedulerConfig {
        &self.cfg
    }

    pub fn passes(&self) -> usize {
        self.cfg.passes
    }

    /// Queues one submission. `specs` must name distinct passes in increasing order; a submission
    /// may skip passes (e.g. no draft). Returns its sequence number.
    pub fn submit(&mut self, now_ms: f64, specs: Vec<PassSpec<P>>) -> u64 {
        let seq = self.next_seq;
        self.next_seq += 1;
        let mut last: Option<usize> = None;
        for s in specs {
            assert!(s.pass < self.cfg.passes, "pass {} out of range", s.pass);
            assert!(last.is_none_or(|l| s.pass > l), "passes must increase");
            last = Some(s.pass);
            let compat = if s.pass + 1 == self.cfg.passes {
                None
            } else {
                s.compat
            };
            self.queues[s.pass].push_back(Item {
                seq,
                pass: s.pass,
                enqueued_ms: now_ms,
                cost: s.cost.max(0.0),
                compat,
                started: false,
                payload: s.payload,
            });
        }
        seq
    }

    pub fn pending(&self) -> usize {
        self.queues.iter().map(VecDeque::len).sum()
    }

    pub fn pending_in(&self, pass: usize) -> usize {
        self.queues[pass].len()
    }

    pub fn pending_refinement(&self) -> usize {
        self.queues[1..].iter().map(VecDeque::len).sum()
    }

    /// Items still queued at `pass`, in order (the engine's ETA walks the final queue).
    pub fn queue(&self, pass: usize) -> impl Iterator<Item = &Item<P>> {
        self.queues[pass].iter()
    }

    /// True when no earlier pass of submission `seq` is still queued.
    pub fn eligible(&self, pass: usize, seq: u64) -> bool {
        self.queues[..pass]
            .iter()
            .all(|q| q.iter().take_while(|i| i.seq <= seq).all(|i| i.seq != seq))
    }

    /// Every pending draft item, in submission order, grouped into dispatches: consecutive items
    /// with equal `compat` keys share one. The hard guarantee: callers run all of them.
    pub fn take_drafts(&mut self) -> Vec<Batch<P>> {
        let mut out: Vec<Batch<P>> = Vec::new();
        while let Some(item) = self.queues[0].pop_front() {
            match out.last_mut() {
                Some(batch)
                    if item.compat.is_some() && batch.last().unwrap().compat == item.compat =>
                {
                    batch.push(item)
                }
                _ => out.push(vec![item]),
            }
        }
        out
    }

    /// The refinement pass whose eligible head has the highest effective priority at `now_ms`.
    pub fn pick_refinement(&self, now_ms: f64) -> Option<usize> {
        let mut best: Option<(usize, f64, u64)> = None;
        for pass in 1..self.cfg.passes {
            let Some(head) = self.queues[pass].front() else {
                continue;
            };
            if !self.eligible(pass, head.seq) {
                continue;
            }
            let p = self
                .cfg
                .effective_priority(pass, now_ms - head.enqueued_ms);
            let better = match best {
                None => true,
                // Strictly higher priority wins; ties keep the lower pass (seen first).
                Some((_, bp, _)) => p > bp,
            };
            if better {
                best = Some((pass, p, head.seq));
            }
        }
        best.map(|(pass, _, _)| pass)
    }

    pub fn head_mut(&mut self, pass: usize) -> Option<&mut Item<P>> {
        self.queues[pass].front_mut()
    }

    /// Removes the head of `pass` (the executor finished it).
    pub fn pop_head(&mut self, pass: usize) -> Option<Item<P>> {
        self.queues[pass].pop_front()
    }

    /// Head of `pass` plus the following eligible items with the same `compat` key while their
    /// summed cost stays within `max_cost` (the head is always included). Final-pass items are never
    /// merged. Only unstarted items are merged.
    pub fn take_batch(&mut self, pass: usize, max_cost: f64) -> Batch<P> {
        let Some(head) = self.queues[pass].pop_front() else {
            return Vec::new();
        };
        let mut total = head.cost;
        let compat = head.compat;
        let mut batch = vec![head];
        if compat.is_none() {
            return batch;
        }
        while let Some(next) = self.queues[pass].front() {
            if next.compat != compat || next.started || total + next.cost > max_cost {
                break;
            }
            if !self.eligible(pass, next.seq) {
                break;
            }
            total += next.cost;
            batch.push(self.queues[pass].pop_front().unwrap());
        }
        batch
    }

    /// Drops everything (the layer the work targets was replaced). Returns the dropped items.
    pub fn clear(&mut self) -> Vec<Item<P>> {
        let mut out = Vec::new();
        for q in &mut self.queues {
            out.extend(q.drain(..));
        }
        out
    }

    /// One refinement run, the policy in one place (the engine follows the same steps with GPU
    /// work; tests drive it with a simulated executor):
    ///
    /// 1. all pending drafts, through `draft` (never budgeted, never skipped);
    /// 2. refinement items by effective priority while `budget` lasts. `exec(item, left)` does as
    ///    much of `item` as fits in `left` (at least a minimal unit when `left <= 0` on the run's
    ///    first call) and returns `(cost_spent, finished)`. The run stops when the budget is spent,
    ///    nothing is eligible, or the executor makes no progress.
    pub fn run_refinement(
        &mut self,
        now_ms: f64,
        budget: f64,
        mut draft: impl FnMut(&mut Batch<P>),
        mut exec: impl FnMut(&mut Item<P>, f64) -> (f64, bool),
    ) -> RefineReport {
        let mut report = RefineReport::default();
        for mut batch in self.take_drafts() {
            report.drafts_run += batch.len();
            draft(&mut batch);
        }
        let mut left = budget;
        loop {
            if report.executor_calls > 0 && left <= 0.0 {
                break;
            }
            let Some(pass) = self.pick_refinement(now_ms) else {
                break;
            };
            let head = self.queues[pass].front_mut().unwrap();
            let (spent, finished) = exec(head, left);
            head.started = true;
            report.executor_calls += 1;
            report.cost_spent += spent;
            left -= spent;
            if finished {
                self.queues[pass].pop_front();
                report.items_finished += 1;
            } else if spent <= 0.0 {
                break;
            }
        }
        report
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One unit of cost per item; `payload` = remaining units.
    fn spec(pass: usize, cost: f64) -> PassSpec<f64> {
        PassSpec {
            pass,
            cost,
            compat: Some(7),
            payload: cost,
        }
    }

    fn submit_all(s: &mut Scheduler<f64>, now: f64, costs: &[f64]) -> u64 {
        let specs = costs
            .iter()
            .enumerate()
            .map(|(k, &c)| spec(k, c))
            .collect();
        s.submit(now, specs)
    }

    /// Executor that does whole units of the item's remaining payload within `left` (at least one
    /// unit), logging (seq, pass).
    fn unit_exec<'a>(
        log: &'a mut Vec<(u64, usize)>,
    ) -> impl FnMut(&mut Item<f64>, f64) -> (f64, bool) + 'a {
        move |item, left| {
            let units = left.floor().max(1.0).min(item.payload);
            item.payload -= units;
            item.cost = item.payload;
            log.push((item.seq, item.pass));
            (units, item.payload <= 0.0)
        }
    }

    #[test]
    fn default_formula_and_parameters() {
        let c = SchedulerConfig::new(4, 50.0);
        assert_eq!(c.base_weight, vec![3.0, 2.0, 1.0, 0.0]);
        assert!(c.base_weight.windows(2).all(|w| w[0] > w[1]));
        assert_eq!(c.effective_priority(2, 0.0), 1.0);
        assert!((c.effective_priority(2, 100.0) - 3.0).abs() < 1e-12);
        assert_eq!(c.overtake_ms(1, 3), Some(100.0));
        let mut t = c.clone();
        t.age_threshold_ms[3] = 40.0;
        assert_eq!(t.effective_priority(3, 30.0), 0.0);
        assert!((t.effective_priority(3, 90.0) - 1.0).abs() < 1e-12);
    }

    #[test]
    fn base_priority_order_when_ages_are_equal() {
        let mut s = Scheduler::new(SchedulerConfig::new(4, 50.0));
        submit_all(&mut s, 0.0, &[1.0, 1.0, 1.0, 1.0]);
        s.take_drafts();
        // Pass 1 first (the only eligible one), then 2, then 3: equal ages, base weights decide.
        let mut log = Vec::new();
        s.run_refinement(0.0, 10.0, |_| {}, unit_exec(&mut log));
        assert_eq!(log, vec![(1, 1), (1, 2), (1, 3)]);

        // Two submissions at the same time: every pass-1 item before any pass-2 item, etc.
        let mut s = Scheduler::new(SchedulerConfig::new(3, 50.0));
        submit_all(&mut s, 0.0, &[1.0, 1.0, 1.0]);
        submit_all(&mut s, 0.0, &[1.0, 1.0, 1.0]);
        let mut log = Vec::new();
        s.run_refinement(0.0, 10.0, |_| {}, unit_exec(&mut log));
        assert_eq!(log, vec![(1, 1), (2, 1), (1, 2), (2, 2)]);
    }

    #[test]
    fn aging_lets_a_waiting_later_pass_overtake_a_fresh_earlier_one() {
        let cfg = SchedulerConfig::new(3, 50.0);
        let mut s = Scheduler::new(cfg);
        // Submission 1 at t=0; its pass 1 runs, its pass 2 waits.
        submit_all(&mut s, 0.0, &[1.0, 1.0, 1.0]);
        let mut log = Vec::new();
        s.run_refinement(0.0, 1.0, |_| {}, unit_exec(&mut log));
        assert_eq!(log, vec![(1, 1)]);
        // A fresh submission at t=40: its pass 1 (weight 1) still beats the old pass 2
        // (0 + 40/50 = 0.8).
        submit_all(&mut s, 40.0, &[1.0, 1.0, 1.0]);
        let mut log = Vec::new();
        s.run_refinement(40.0, 1.0, |_| {}, unit_exec(&mut log));
        assert_eq!(log, vec![(2, 1)]);
        // Another fresh one at t=60: the old pass 2 is now at 1.2 and overtakes the fresh pass 1.
        submit_all(&mut s, 60.0, &[1.0, 1.0, 1.0]);
        let mut log = Vec::new();
        s.run_refinement(60.0, 1.0, |_| {}, unit_exec(&mut log));
        assert_eq!(log, vec![(1, 2)]);
    }

    #[test]
    fn drafts_are_never_delayed_behind_refinement() {
        let mut s = Scheduler::new(SchedulerConfig::new(3, 1.0));
        let events: std::cell::RefCell<Vec<(char, u64)>> = Default::default();
        for frame in 0..200u64 {
            let now = frame as f64 * 16.0;
            // Refinement is hopelessly behind (huge costs, starving budget) and heavily aged.
            submit_all(&mut s, now, &[1.0, 50.0, 50.0]);
            submit_all(&mut s, now, &[1.0, 50.0, 50.0]);
            let mut drafts_this_frame = 0;
            let report = s.run_refinement(
                now,
                0.0,
                |b| {
                    drafts_this_frame += b.len();
                    for i in b.iter() {
                        events.borrow_mut().push(('d', i.seq));
                    }
                },
                |item, _| {
                    events.borrow_mut().push(('r', item.seq));
                    item.payload -= 1.0;
                    (1.0, item.payload <= 0.0)
                },
            );
            // Every draft of the frame ran, all before any refinement of the frame.
            assert_eq!(drafts_this_frame, 2);
            assert_eq!(report.drafts_run, 2);
            assert_eq!(s.pending_in(0), 0);
            // Under the starving budget, refinement still made progress (one minimal unit).
            assert_eq!(report.executor_calls, 1);
        }
        // No refinement event ever precedes a draft that was pending at the time: within each
        // frame, drafts form a prefix. Check globally: a draft event never follows a refinement
        // event of the same frame, i.e. the sequence is (d d r)*.
        for chunk in events.into_inner().chunks(3) {
            assert_eq!(chunk.iter().map(|e| e.0).collect::<String>(), "ddr");
        }
    }

    #[test]
    fn refinement_makes_progress_under_a_starving_budget_and_finishes_after_the_load_stops() {
        let mut s = Scheduler::new(SchedulerConfig::new(2, 50.0));
        for frame in 0..50 {
            submit_all(&mut s, frame as f64 * 16.0, &[1.0, 3.0]);
            let mut log = Vec::new();
            let r = s.run_refinement(frame as f64 * 16.0, 0.0, |_| {}, unit_exec(&mut log));
            assert_eq!(r.executor_calls, 1);
            assert_eq!(r.cost_spent, 1.0);
        }
        let before = s.pending_refinement();
        assert!(before > 0);
        let mut frames = 0;
        while s.pending_refinement() > 0 {
            let mut log = Vec::new();
            s.run_refinement(1000.0 + frames as f64 * 16.0, 0.0, |_| {}, unit_exec(&mut log));
            frames += 1;
            assert!(frames < 1000);
        }
        // 50 submissions x 3 units, one unit per frame: 150 frames in all, 50 already done.
        assert_eq!(frames, 100);
    }

    #[test]
    fn per_dab_ordering_holds_under_random_interleavings() {
        let mut rng = 0x1234_5678_9abc_def0u64;
        let mut next = || {
            rng = rng.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            (rng >> 33) as f64 / (1u64 << 31) as f64
        };
        let mut s = Scheduler::new(SchedulerConfig::new(4, 30.0));
        let mut done: std::collections::HashMap<u64, usize> = Default::default();
        let check = |seq: u64, pass: usize, done: &mut std::collections::HashMap<u64, usize>| {
            let prev = done.get(&seq).copied();
            // Pass k runs only after pass k-1 (passes here are all present).
            assert_eq!(prev.map_or(0, |p| p + 1), pass, "seq {seq} pass {pass} after {prev:?}");
            done.insert(seq, pass);
        };
        for frame in 0..300 {
            let now = frame as f64 * 16.0 + next() * 10.0;
            for _ in 0..(next() * 3.0) as usize {
                let costs = [1.0, 1.0 + next() * 4.0, 1.0 + next() * 4.0, 1.0 + next() * 8.0];
                submit_all(&mut s, now, &costs);
            }
            let budget = next() * 12.0;
            let order = std::cell::RefCell::new(Vec::new());
            s.run_refinement(
                now,
                budget,
                |b| order.borrow_mut().extend(b.iter().map(|i| (i.seq, i.pass))),
                |item, left| {
                    let units = left.floor().max(1.0).min(item.payload);
                    item.payload -= units;
                    let fin = item.payload <= 0.0;
                    if fin {
                        order.borrow_mut().push((item.seq, item.pass));
                    }
                    (units, fin)
                },
            );
            for (seq, pass) in order.into_inner() {
                check(seq, pass, &mut done);
            }
        }
    }

    #[test]
    fn budget_is_respected() {
        let mut s = Scheduler::new(SchedulerConfig::new(3, 50.0));
        for i in 0..40 {
            submit_all(&mut s, i as f64, &[1.0, 2.0, 5.0]);
        }
        for frame in 0..30 {
            let budget = 7.0;
            let mut log = Vec::new();
            let r = s.run_refinement(100.0 + frame as f64, budget, |_| {}, unit_exec(&mut log));
            assert!(r.cost_spent <= budget, "spent {} > {budget}", r.cost_spent);
            assert!(r.cost_spent > 0.0);
        }
        // A merged draft/clarity batch never exceeds the cap given to take_batch.
        let mut s = Scheduler::new(SchedulerConfig::new(3, 50.0));
        for i in 0..10 {
            s.submit(
                i as f64,
                vec![PassSpec {
                    pass: 1,
                    cost: 2.0,
                    compat: Some(1),
                    payload: 0.0,
                }],
            );
        }
        let b = s.take_batch(1, 7.0);
        assert_eq!(b.len(), 3);
        assert_eq!(b.iter().map(|i| i.cost).sum::<f64>(), 6.0);
    }

    #[test]
    fn final_pass_items_are_never_merged_and_drafts_merge_only_when_compatible() {
        let mut s = Scheduler::new(SchedulerConfig::new(2, 50.0));
        for c in [1, 1, 2, 2, 2, 1] {
            s.submit(
                0.0,
                vec![
                    PassSpec {
                        pass: 0,
                        cost: 1.0,
                        compat: Some(c),
                        payload: 0.0,
                    },
                    PassSpec {
                        pass: 1,
                        cost: 1.0,
                        compat: Some(9),
                        payload: 0.0,
                    },
                ],
            );
        }
        let drafts = s.take_drafts();
        assert_eq!(drafts.iter().map(Vec::len).collect::<Vec<_>>(), vec![2, 3, 1]);
        assert_eq!(s.take_batch(1, 100.0).len(), 1);
    }

    /// Sustained load on pass 1 (enough to fill every frame's budget on its own) with pass-2 work
    /// behind it. Without aging, pass 2 waits for the whole burst. With aging, a pass-2 item never
    /// waits longer than the pass-1 items it competes with plus the overtake time (+ one frame):
    /// the burst is shared, nothing starves.
    #[test]
    fn no_starvation_under_sustained_earlier_pass_load() {
        let frame_ms = 16.0;
        let burst_frames = 120;
        let run = |overtake_ms: f64| -> (f64, f64, Option<usize>) {
            let mut s = Scheduler::new(SchedulerConfig::new(3, overtake_ms));
            let mut waits: [f64; 3] = [0.0; 3];
            let mut first_pass2: Option<usize> = None;
            let mut frame = 0;
            while frame < burst_frames || s.pending() > 0 {
                let now = frame as f64 * frame_ms;
                if frame < burst_frames {
                    // Pass-1 work alone fills the budget (2 units/frame against a budget of 2).
                    submit_all(&mut s, now, &[1.0, 1.0, 1.0]);
                    submit_all(&mut s, now, &[1.0, 1.0, 1.0]);
                }
                s.run_refinement(
                    now,
                    2.0,
                    |_| {},
                    |item, _| {
                        let w = now - item.enqueued_ms;
                        waits[item.pass] = waits[item.pass].max(w);
                        if item.pass == 2 && first_pass2.is_none() {
                            first_pass2 = Some(frame);
                        }
                        (1.0, true)
                    },
                );
                frame += 1;
                assert!(frame < 10_000);
            }
            (waits[1], waits[2], first_pass2)
        };
        // Aging off (a huge overtake time): pass 2 only runs after the burst ends.
        let (_, _, first) = run(1e12);
        assert!(first.unwrap() >= burst_frames, "first pass-2 at {first:?}");
        // Aging on: pass 2 starts within the overtake time, and its wait is bounded by pass 1's
        // wait plus the overtake time plus one frame.
        let overtake = 50.0;
        let (w1, w2, first) = run(overtake);
        assert!(first.unwrap() as f64 * frame_ms <= overtake + frame_ms, "first {first:?}");
        assert!(w2 <= w1 + overtake + frame_ms, "pass-2 wait {w2} vs pass-1 wait {w1}");
    }

    /// When capacity suffices on average, aging gives an absolute bound on any item's wait even
    /// though fresh pass-1 items arrive every frame.
    #[test]
    fn bounded_wait_when_the_load_is_sustainable() {
        let overtake = 40.0;
        let mut s = Scheduler::new(SchedulerConfig::new(3, overtake));
        let mut max_wait: f64 = 0.0;
        for frame in 0..600 {
            let now = frame as f64 * 16.0;
            // 3 units of work per submission, one submission per frame, budget 3.5 per frame.
            submit_all(&mut s, now, &[1.0, 1.0, 2.0]);
            s.run_refinement(
                now,
                3.5,
                |_| {},
                |item, left| {
                    max_wait = max_wait.max(now - item.enqueued_ms);
                    let units = left.floor().max(1.0).min(item.payload);
                    item.payload -= units;
                    (units, item.payload <= 0.0)
                },
            );
        }
        assert!(max_wait <= 2.0 * overtake + 32.0, "max wait {max_wait}");
    }

    #[test]
    fn clear_drops_everything() {
        let mut s = Scheduler::new(SchedulerConfig::new(2, 50.0));
        submit_all(&mut s, 0.0, &[1.0, 1.0]);
        assert_eq!(s.clear().len(), 2);
        assert_eq!(s.pending(), 0);
    }
}
