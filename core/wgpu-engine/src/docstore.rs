//! The document store: an open document's layers kept on the GPU as their only canonical copy.
//!
//! The store reuses the engine's resident-layer set ([`crate::resident`]); what changes is who is
//! authoritative. Outside document mode a resident buffer is a cache of a CPU layer, tagged with the
//! caller's content generation and dropped under memory pressure. In document mode
//! ([`Engine::doc_open`]) every entry *is* its layer:
//!
//! * nothing is evicted behind the caller's back -- the budget is unbounded and memory policy is
//!   the caller's (it reads a layer back before dropping it);
//! * generations are the engine's own: every write ([`Engine::doc_layer_put`],
//!   [`Engine::doc_layer_write`], a committed stroke) bumps the layer's generation from one
//!   engine-wide counter, so a caller holding a CPU mirror knows exactly when it is stale;
//! * a stroke binds its layer ([`Engine::doc_layer_bind`]), paints with the ordinary stamp / smudge
//!   / wet calls, and commits in place ([`Engine::doc_layer_commit`]), which reports the rectangle
//!   the stroke changed. No upload starts a stroke and no readback ends one.
//!
//! CPU pixels exist only as explicit reads ([`Engine::doc_layer_read`]) for save, export and the
//! tools that genuinely work on the CPU, which hand their results back with
//! [`Engine::doc_layer_write`].

use super::Engine;
use crate::resident::{Rect, Tag};

impl Engine {
    /// Enters document mode: forgets any cached layers and lifts the residency budget, so no layer
    /// is ever evicted. Idempotent.
    pub fn doc_open(&mut self) -> bool {
        if !self.doc_mode {
            self.invalidate_all_layers();
            let evicted = self.residents.set_budget(u64::MAX, self.active_key);
            drop(evicted);
            self.doc_mode = true;
        }
        self.ok()
    }

    /// Whether [`Engine::doc_open`] has been called.
    pub fn doc_is_open(&self) -> bool {
        self.doc_mode
    }

    fn doc_next_gen(&mut self) -> u64 {
        self.doc_gen += 1;
        self.doc_gen
    }

    fn doc_ensure(&mut self, key: u64) -> wgpu::Buffer {
        if self.residents.get(key).is_none() {
            let bytes = self.layer_bytes();
            let buffer = self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("document layer"),
                size: bytes,
                usage: wgpu::BufferUsages::STORAGE
                    | wgpu::BufferUsages::COPY_DST
                    | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            });
            let evicted = self
                .residents
                .insert(key, buffer, bytes, Tag::Invalid, self.active_key);
            debug_assert!(evicted.is_empty(), "document mode never evicts");
            drop(evicted);
        }
        self.residents.get(key).expect("ensured").buffer.clone()
    }

    pub(super) fn doc_retag(&mut self, key: u64) -> u64 {
        let generation = self.doc_next_gen();
        if let Some(e) = self.residents.get_mut(key) {
            e.tag = Tag::Valid(generation);
        }
        generation
    }

    /// Writes a whole layer (premultiplied RGBA8, `width*height*4` bytes), creating it if new.
    /// Returns its new generation, 0 on failure. If it is the bound layer, its stroke restarts
    /// from these pixels.
    pub fn doc_layer_put(&mut self, key: u64, rgba: &[u8]) -> u64 {
        let bytes = self.layer_bytes() as usize;
        if !self.doc_mode || rgba.len() < bytes {
            return 0;
        }
        let active = self.active_key == Some(key);
        if active {
            self.mp_drain();
        }
        let buffer = self.doc_ensure(key);
        self.queue.write_buffer(&buffer, 0, &rgba[..bytes]);
        if active {
            self.stroke_state_dirty = true;
            self.stroke_written = Rect::default();
            self.mark_fully_dirty();
        }
        let generation = self.doc_retag(key);
        if self.ok() {
            generation
        } else {
            0
        }
    }

    /// A transparent layer `key` (created if new). Returns its new generation, 0 on failure.
    pub fn doc_layer_clear(&mut self, key: u64) -> u64 {
        if !self.doc_mode {
            return 0;
        }
        if self.active_key == Some(key) {
            self.mp_drain();
            self.stroke_state_dirty = true;
            self.stroke_written = Rect::default();
            self.mark_fully_dirty();
        }
        let buffer = self.doc_ensure(key);
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.clear_buffer(&buffer, 0, None);
        self.queue.submit([encoder.finish()]);
        let generation = self.doc_retag(key);
        if self.ok() {
            generation
        } else {
            0
        }
    }

    /// Replaces `[x, x + w) x [y, y + h)` of layer `key` with `rgba` (`w*h*4` bytes, row-major,
    /// premultiplied). Returns the new generation, 0 if the layer is absent or on failure.
    pub fn doc_layer_write(
        &mut self,
        key: u64,
        x: i32,
        y: i32,
        w: i32,
        h: i32,
        rgba: &[u8],
    ) -> u64 {
        let r = Rect::new(x, y, w, h);
        if !self.doc_mode
            || self.residents.get(key).is_none()
            || r.is_empty()
            || r != r.clamp(self.width, self.height)
            || rgba.len() < (w * h * 4) as usize
        {
            return 0;
        }
        let active = self.active_key == Some(key);
        if active {
            self.mp_drain();
        }
        let buffer = self.residents.get(key).expect("checked").buffer.clone();
        let row_bytes = self.width as u64 * 4;
        let rect_row = w as usize * 4;
        for row in 0..h as usize {
            let offset = (y as u64 + row as u64) * row_bytes + x as u64 * 4;
            self.queue
                .write_buffer(&buffer, offset, &rgba[row * rect_row..(row + 1) * rect_row]);
        }
        if active {
            self.stroke_state_dirty = true;
            self.stroke_written = Rect::default();
            self.note_write(r);
        }
        let generation = self.doc_retag(key);
        if self.ok() {
            generation
        } else {
            0
        }
    }

    /// Duplicates layer `src` into `dst` (created if new) on the GPU. Returns `dst`'s generation.
    pub fn doc_layer_copy(&mut self, src: u64, dst: u64) -> u64 {
        if !self.doc_mode || src == dst || self.residents.get(src).is_none() {
            return 0;
        }
        if self.active_key == Some(src) || self.active_key == Some(dst) {
            self.mp_drain();
        }
        let from = self.residents.get(src).expect("checked").buffer.clone();
        let to = self.doc_ensure(dst);
        let mut encoder = self.device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(&from, 0, &to, 0, self.layer_bytes());
        self.queue.submit([encoder.finish()]);
        if self.active_key == Some(dst) {
            self.stroke_state_dirty = true;
            self.stroke_written = Rect::default();
            self.mark_fully_dirty();
        }
        let generation = self.doc_retag(dst);
        if self.ok() {
            generation
        } else {
            0
        }
    }

    /// Forgets layer `key`. True if the store held it.
    pub fn doc_layer_drop(&mut self, key: u64) -> bool {
        if self.active_key == Some(key) {
            self.mp_discard_pending();
            self.detach();
        }
        self.snapshots.drop_layer(key);
        self.residents.remove(key).is_some()
    }

    /// The layer's current generation, 0 if absent.
    pub fn doc_layer_gen(&self, key: u64) -> u64 {
        match self.residents.get(key).map(|e| e.tag) {
            Some(Tag::Valid(g)) => g,
            Some(Tag::Tainted(_)) => self.doc_gen,
            _ => 0,
        }
    }

    /// Starts a stroke on layer `key`: the stamp, smudge and wet calls paint into it from here,
    /// with fresh stroke-max state. Returns the stroke's session (> 0), 0 if the layer is absent.
    pub fn doc_layer_bind(&mut self, key: u64) -> u64 {
        if !self.doc_mode || self.residents.get(key).is_none() {
            return 0;
        }
        self.mp_session_ending();
        let session = self.activate(key);
        // The stroke's rectangle starts empty; there is no CPU mirror to reconcile with.
        self.mp_session_started();
        // The layer as it is now: what the selection confines back to and undo snapshots keep.
        if self.doc_take_shadow(key, session) {
            session
        } else {
            0
        }
    }

    /// Ends the stroke bound in `session` on layer `key`: queued work lands, the layer takes a new
    /// generation, and the rectangle the stroke painted is returned as `(generation, x, y, w, h)`.
    /// None if `session` is no longer the layer's stroke.
    pub fn doc_layer_commit(&mut self, key: u64, session: u64) -> Option<(u64, Rect)> {
        let current = self
            .residents
            .get(key)
            .is_some_and(|e| e.session == session && session != 0);
        if !self.doc_mode || !current {
            return None;
        }
        if self.active_key == Some(key) {
            self.mp_drain();
        }
        let (width, height) = (self.width, self.height);
        let touched = {
            let e = self.residents.get_mut(key).expect("checked");
            let t = e.touched.clamp(width, height);
            e.touched = Rect::default();
            t
        };
        let generation = self.doc_retag(key);
        self.ok().then_some((generation, touched))
    }

    /// Copies `[x, x + w) x [y, y + h)` of layer `key` into `out` (premultiplied RGBA8, row-major,
    /// `w*4` bytes per row). False if the layer is absent or the rectangle is outside the layer.
    #[allow(clippy::too_many_arguments)]
    pub fn doc_layer_read(
        &mut self,
        key: u64,
        x: i32,
        y: i32,
        w: i32,
        h: i32,
        out: &mut [u8],
    ) -> bool {
        let r = Rect::new(x, y, w, h);
        if self.residents.get(key).is_none()
            || r.is_empty()
            || r != r.clamp(self.width, self.height)
            || out.len() < (w * h * 4) as usize
        {
            return false;
        }
        if self.active_key == Some(key) {
            self.mp_drain();
        }
        let buffer = self.residents.get(key).expect("checked").buffer.clone();
        self.read_region_into(&buffer, r, out, w as usize * 4, 0) && self.ok()
    }

    /// `(layer count, bytes held)`.
    pub fn doc_stats(&self) -> (usize, u64) {
        (self.residents.len(), self.residents.total_bytes())
    }
}
