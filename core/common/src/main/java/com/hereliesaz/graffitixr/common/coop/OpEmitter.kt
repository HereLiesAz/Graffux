package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Op

/**
 * Single point through which editor layer mutations flow into the co-op session.
 * Editor code calls emit(op) unconditionally; impls handle the active-vs-inactive
 * branching internally. NoOpOpEmitter is the default when not hosting.
 *
 * [isActive] exists so a caller can skip *building* an [Op] it knows will be dropped — some ops
 * (whole-layer replaces after undo/redo, a fill, a merge) carry a full-resolution PNG encode of the
 * layer, which is real CPU/allocation work done for nothing every time nothing is listening.
 */
fun interface OpEmitter {
    fun emit(op: Op)

    /** True when this emitter has a live session to send to. Defaults to true for real emitters. */
    val isActive: Boolean get() = true
}
