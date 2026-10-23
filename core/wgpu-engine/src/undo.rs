//! Undo on the GPU: a committed stroke's pre-stroke rectangle kept as a GPU buffer, swapped with
//! the layer to undo and swapped back to redo. No pixels cross to the CPU.
//!
//! [`Engine::doc_layer_bind`] keeps the layer as it was when the stroke started (the stroke
//! shadow). After [`Engine::doc_layer_commit`] reports the rectangle the stroke painted,
//! [`Engine::doc_snapshot_stroke`] copies that rectangle of the shadow into a snapshot. Undo and
//! redo are the same operation, [`Engine::doc_snapshot_swap`]: the snapshot and the layer exchange
//! the rectangle, so the snapshot always holds the state the other direction needs.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};

use super::Engine;
use crate::resident::{Rect, Tag};

/// Snapshot ids are unique across engines, so an id kept for a handle since destroyed can never
/// name another engine's snapshot.
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

struct Snapshot {
    key: u64,
    rect: Rect,
    buffer: wgpu::Buffer,
}

/// The engine's snapshots by id.
#[derive(Default)]
pub(super) struct Snapshots {
    map: HashMap<u64, Snapshot>,
    /// `(layer, session)` of the bind the stroke shadow was taken at.
    pub(super) shadow_of: Option<(u64, u64)>,
}

impl Snapshots {
    pub(super) fn drop_layer(&mut self, key: u64) {
        self.map.retain(|_, s| s.key != key);
        if self.shadow_of.is_some_and(|(k, _)| k == key) {
            self.shadow_of = None;
        }
    }

    fn bytes(&self) -> u64 {
        self.map.values().map(|s| s.buffer.size()).sum()
    }
}

impl Engine {
    fn rect_buffer(&self, label: &str, r: Rect) -> wgpu::Buffer {
        self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: (r.w as u64) * (r.h as u64) * 4,
            usage: wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        })
    }

    /// Row-by-row copies of rectangle `r` between a layer-shaped buffer and a packed one.
    fn copy_rect(
        &self,
        enc: &mut wgpu::CommandEncoder,
        layer: &wgpu::Buffer,
        packed: &wgpu::Buffer,
        r: Rect,
        to_packed: bool,
    ) {
        let row_bytes = self.width as u64 * 4;
        let rect_row = r.w as u64 * 4;
        for row in 0..r.h as u64 {
            let lo = (r.y as u64 + row) * row_bytes + r.x as u64 * 4;
            let po = row * rect_row;
            if to_packed {
                enc.copy_buffer_to_buffer(layer, lo, packed, po, rect_row);
            } else {
                enc.copy_buffer_to_buffer(packed, po, layer, lo, rect_row);
            }
        }
    }

    /// Keeps the layer as it is now as the stroke shadow of bind `session` on layer `key`, for
    /// [`Engine::confine_stroke`] and [`Engine::doc_snapshot_stroke`]. Only with undo shadows on ([`Engine::undo_open`], or document mode).
    pub(super) fn doc_take_shadow(&mut self, key: u64, session: u64) -> bool {
        if !self.undo_shadows || session == 0 {
            return true;
        }
        let ok = self.take_stroke_shadow();
        self.snapshots.shadow_of = ok.then_some((key, session));
        ok
    }

    /// Keeps `[x, x + w) x [y, y + h)` of layer `key` as it was before the stroke bound in
    /// `session` (committed by now). Returns the snapshot's id (> 0), 0 for an empty rectangle, an
    /// absent layer, or when a later bind has replaced that stroke's shadow.
    #[allow(clippy::too_many_arguments)]
    pub fn doc_snapshot_stroke(
        &mut self,
        key: u64,
        session: u64,
        x: i32,
        y: i32,
        w: i32,
        h: i32,
    ) -> u64 {
        let r = Rect::new(x, y, w, h).clamp(self.width, self.height);
        if r.is_empty()
            || self.snapshots.shadow_of != Some((key, session))
            || self.residents.get(key).is_none()
        {
            return 0;
        }
        self.mp_drain();
        let shadow = self.stroke_shadow();
        let buffer = self.rect_buffer("undo snapshot", r);
        let mut enc = self.device.create_command_encoder(&Default::default());
        self.copy_rect(&mut enc, &shadow, &buffer, r, true);
        self.queue.submit([enc.finish()]);
        if !self.ok() {
            return 0;
        }
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        self.snapshots.map.insert(
            id,
            Snapshot {
                key,
                rect: r,
                buffer,
            },
        );
        id
    }

    /// Exchanges snapshot `id` with its layer's rectangle: undo, and again to redo. With
    /// `expected` != 0 the layer must be tagged `expected` (its content is what the snapshot was
    /// taken against) and is tagged `generation` after; with 0 it takes the store's next
    /// generation. Returns the layer's generation and the rectangle, None if the snapshot or its
    /// layer is gone or the layer holds other content.
    pub fn doc_snapshot_swap(
        &mut self,
        id: u64,
        expected: u64,
        generation: u64,
    ) -> Option<(u64, Rect)> {
        let (key, r, packed) = {
            let s = self.snapshots.map.get(&id)?;
            (s.key, s.rect, s.buffer.clone())
        };
        let entry = self.residents.get(key)?;
        if expected != 0 && (entry.tag != Tag::Valid(expected) || generation == 0) {
            return None;
        }
        let layer = entry.buffer.clone();
        let active = self.active_key == Some(key);
        if active {
            self.mp_drain();
        }
        let temp = self.rect_buffer("undo swap", r);
        let mut enc = self.device.create_command_encoder(&Default::default());
        self.copy_rect(&mut enc, &layer, &temp, r, true);
        self.copy_rect(&mut enc, &layer, &packed, r, false);
        enc.copy_buffer_to_buffer(&temp, 0, &packed, 0, temp.size());
        self.queue.submit([enc.finish()]);
        if active {
            self.stroke_state_dirty = true;
            self.stroke_written = Rect::default();
            self.note_write(r);
        }
        let tagged = if expected == 0 {
            self.doc_retag(key)
        } else {
            if let Some(e) = self.residents.get_mut(key) {
                e.tag = Tag::Valid(generation);
            }
            generation
        };
        self.ok().then_some((tagged, r))
    }

    /// Forgets snapshot `id`. True if it existed.
    pub fn doc_snapshot_drop(&mut self, id: u64) -> bool {
        self.snapshots.map.remove(&id).is_some()
    }

    /// `(snapshot count, bytes held)`.
    pub fn doc_snapshot_stats(&self) -> (usize, u64) {
        (self.snapshots.map.len(), self.snapshots.bytes())
    }
}
