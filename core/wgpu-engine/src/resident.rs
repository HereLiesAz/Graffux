//! Bookkeeping for layers kept resident on the GPU across strokes.
//!
//! The engine keeps one storage buffer per recently painted layer instead of re-uploading the
//! whole layer at the start of every stroke. Each entry is keyed by the caller's layer key and
//! tagged with the content generation it currently holds:
//!
//! * [`Tag::Valid`]`(gen)` -- the buffer holds exactly the CPU layer at generation `gen`. A stroke
//!   that binds `(key, gen)` can start painting without an upload.
//! * [`Tag::Tainted`]`(session)` -- a stroke (bind session `session`) has painted into it. The
//!   buffer no longer matches any CPU generation until the caller either retags it (the GPU result
//!   *is* the committed layer: [`Tag`] becomes `Valid` again with no upload) or refreshes the rows
//!   the stroke and the CPU commit touched from the committed pixels.
//! * [`Tag::Invalid`] -- the caller said the CPU layer changed behind the engine's back; the
//!   buffer is kept only because it is the active one, and the next bind always misses.
//!
//! Generations are opaque to the engine. Callers make them unique across layers (one global
//! counter), so a bind can never match another layer's content even if two layer keys collide.
//!
//! Memory is bounded by a byte budget with least-recently-used eviction; the active layer is never
//! evicted. Nothing in here touches wgpu, so it is unit-tested without an adapter.

/// What a resident buffer currently holds. See the module doc.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tag {
    Valid(u64),
    Tainted(u64),
    Invalid,
}

/// A pixel rectangle, `x`/`y` inclusive, `w`/`h` extents. Empty when either extent is <= 0.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

impl Rect {
    pub fn new(x: i32, y: i32, w: i32, h: i32) -> Rect {
        Rect { x, y, w, h }
    }

    pub fn is_empty(&self) -> bool {
        self.w <= 0 || self.h <= 0
    }

    pub fn union(self, other: Rect) -> Rect {
        if other.is_empty() {
            return self;
        }
        if self.is_empty() {
            return other;
        }
        let x0 = self.x.min(other.x);
        let y0 = self.y.min(other.y);
        let x1 = (self.x + self.w).max(other.x + other.w);
        let y1 = (self.y + self.h).max(other.y + other.h);
        Rect {
            x: x0,
            y: y0,
            w: x1 - x0,
            h: y1 - y0,
        }
    }

    /// Intersection with `[0, width) x [0, height)`; empty if nothing is left.
    pub fn clamp(self, width: i32, height: i32) -> Rect {
        if self.is_empty() {
            return Rect::default();
        }
        let x0 = self.x.clamp(0, width);
        let y0 = self.y.clamp(0, height);
        let x1 = self.x.saturating_add(self.w).clamp(0, width);
        let y1 = self.y.saturating_add(self.h).clamp(0, height);
        if x1 <= x0 || y1 <= y0 {
            Rect::default()
        } else {
            Rect {
                x: x0,
                y: y0,
                w: x1 - x0,
                h: y1 - y0,
            }
        }
    }
}

/// One resident layer.
#[derive(Debug)]
pub struct Entry<B> {
    pub key: u64,
    pub buffer: B,
    pub bytes: u64,
    pub tag: Tag,
    /// The bind session that last activated this entry (0 = never bound since upload).
    pub session: u64,
    /// Pixels written by strokes since that session began: what a refresh has to re-upload.
    pub touched: Rect,
    last_used: u64,
}

/// The set of resident layers of one engine, generic over the buffer type so it can be tested
/// without a GPU.
#[derive(Debug)]
pub struct ResidentSet<B> {
    entries: Vec<Entry<B>>,
    budget: u64,
    clock: u64,
}

/// Default budget: 256 MiB -- four 4096x4096 layers or sixty-four 1024x1024 ones per engine.
pub const DEFAULT_BUDGET_BYTES: u64 = 256 * 1024 * 1024;

impl<B> ResidentSet<B> {
    pub fn new(budget: u64) -> Self {
        ResidentSet {
            entries: Vec::new(),
            budget,
            clock: 0,
        }
    }

    pub fn budget(&self) -> u64 {
        self.budget
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn total_bytes(&self) -> u64 {
        self.entries.iter().map(|e| e.bytes).sum()
    }

    pub fn keys(&self) -> Vec<u64> {
        self.entries.iter().map(|e| e.key).collect()
    }

    pub fn get(&self, key: u64) -> Option<&Entry<B>> {
        self.entries.iter().find(|e| e.key == key)
    }

    pub fn get_mut(&mut self, key: u64) -> Option<&mut Entry<B>> {
        self.entries.iter_mut().find(|e| e.key == key)
    }

    /// Marks `key` most recently used.
    pub fn touch(&mut self, key: u64) {
        self.clock += 1;
        let now = self.clock;
        if let Some(e) = self.get_mut(key) {
            e.last_used = now;
        }
    }

    /// Adds a new entry (the caller checked `key` is absent), evicting least-recently-used entries
    /// other than `protect` until it fits the budget. Returns the evicted buffers so the caller can
    /// drop them. A single entry larger than the whole budget is still admitted, alone.
    pub fn insert(&mut self, key: u64, buffer: B, bytes: u64, tag: Tag, protect: Option<u64>) -> Vec<B> {
        debug_assert!(self.get(key).is_none());
        let evicted = self.evict_until(bytes, protect.or(Some(key)));
        self.clock += 1;
        self.entries.push(Entry {
            key,
            buffer,
            bytes,
            tag,
            session: 0,
            touched: Rect::default(),
            last_used: self.clock,
        });
        evicted
    }

    /// Bytes that must still fit after eviction: evicts LRU entries (never `protect`) until
    /// `total + incoming <= budget` or nothing evictable is left.
    fn evict_until(&mut self, incoming: u64, protect: Option<u64>) -> Vec<B> {
        let mut out = Vec::new();
        while self.total_bytes() + incoming > self.budget {
            let victim = self
                .entries
                .iter()
                .enumerate()
                .filter(|(_, e)| Some(e.key) != protect)
                .min_by_key(|(_, e)| e.last_used)
                .map(|(i, _)| i);
            match victim {
                Some(i) => out.push(self.entries.swap_remove(i).buffer),
                None => break,
            }
        }
        out
    }

    /// Changes the budget and evicts down to it (never `protect`).
    pub fn set_budget(&mut self, budget: u64, protect: Option<u64>) -> Vec<B> {
        self.budget = budget;
        self.evict_until(0, protect)
    }

    pub fn remove(&mut self, key: u64) -> Option<B> {
        let i = self.entries.iter().position(|e| e.key == key)?;
        Some(self.entries.swap_remove(i).buffer)
    }

    /// Removes every entry except `keep`, which is tagged [`Tag::Invalid`] instead.
    pub fn invalidate_all(&mut self, keep: Option<u64>) -> Vec<B> {
        let mut out = Vec::new();
        let mut i = 0;
        while i < self.entries.len() {
            if Some(self.entries[i].key) == keep {
                self.entries[i].tag = Tag::Invalid;
                i += 1;
            } else {
                out.push(self.entries.swap_remove(i).buffer);
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lru_eviction_respects_budget_and_protection() {
        let mut s: ResidentSet<&str> = ResidentSet::new(300);
        assert!(s.insert(1, "a", 100, Tag::Valid(1), None).is_empty());
        assert!(s.insert(2, "b", 100, Tag::Valid(2), None).is_empty());
        assert!(s.insert(3, "c", 100, Tag::Valid(3), None).is_empty());
        s.touch(1); // 2 is now least recently used
        let evicted = s.insert(4, "d", 100, Tag::Valid(4), None);
        assert_eq!(evicted, vec!["b"]);
        assert_eq!(s.total_bytes(), 300);
        // Protecting the LRU entry (1 is now LRU after 3) evicts the next one instead.
        let evicted = s.insert(5, "e", 100, Tag::Valid(5), Some(3));
        assert_eq!(evicted, vec!["a"]);
        assert!(s.get(3).is_some());
    }

    #[test]
    fn oversize_entry_is_admitted_alone() {
        let mut s: ResidentSet<u8> = ResidentSet::new(100);
        s.insert(1, 1, 60, Tag::Valid(1), None);
        let evicted = s.insert(2, 2, 500, Tag::Valid(2), None);
        assert_eq!(evicted, vec![1]);
        assert_eq!(s.len(), 1);
        assert_eq!(s.total_bytes(), 500);
    }

    #[test]
    fn shrinking_the_budget_evicts_all_but_the_protected() {
        let mut s: ResidentSet<u8> = ResidentSet::new(1000);
        for k in 1..=4 {
            s.insert(k, k as u8, 100, Tag::Valid(k), None);
        }
        let mut evicted = s.set_budget(0, Some(2));
        evicted.sort();
        assert_eq!(evicted, vec![1, 3, 4]);
        assert_eq!(s.keys(), vec![2]);
    }

    #[test]
    fn invalidate_all_keeps_only_the_active_entry_as_invalid() {
        let mut s: ResidentSet<u8> = ResidentSet::new(1000);
        s.insert(1, 1, 10, Tag::Valid(1), None);
        s.insert(2, 2, 10, Tag::Tainted(7), None);
        let evicted = s.invalidate_all(Some(2));
        assert_eq!(evicted, vec![1]);
        assert_eq!(s.get(2).unwrap().tag, Tag::Invalid);
    }

    #[test]
    fn rect_union_and_clamp() {
        let a = Rect::new(5, 5, 10, 10);
        let b = Rect::new(-3, 12, 4, 20);
        assert_eq!(a.union(b), Rect::new(-3, 5, 18, 27));
        assert_eq!(a.union(b).clamp(16, 20), Rect::new(0, 5, 15, 15));
        assert!(Rect::new(30, 30, 5, 5).clamp(16, 20).is_empty());
        assert_eq!(Rect::default().union(a), a);
    }
}
