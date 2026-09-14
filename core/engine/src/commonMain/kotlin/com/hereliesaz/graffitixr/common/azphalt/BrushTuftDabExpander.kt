package com.hereliesaz.graffitixr.common.azphalt

/**
 * Converts one already-resolved parent contact dab into stable bundle dabs.
 *
 * This function is renderer-independent: CPU and Vulkan continue consuming ordinary [Dab]
 * geometry. Empty/disabled tuft geometry is an exact identity operation so the historical
 * single-contact path remains untouched.
 *
 * Physical-population contacts carry [BrushTuftContact.physicalRadiusPx]. That absolute radius is
 * intentionally independent of the parent brush radius: larger brushes add more bundle contacts
 * instead of enlarging the represented hairs. Legacy topology keeps the old parent-relative
 * [BrushTuftContact.radiusScale] behavior exactly.
 */
object BrushTuftDabExpander {
    fun expand(
        parent: Dab,
        contactDiameterPx: Float,
        tufts: List<BrushTuftContact>,
    ): List<Dab> {
        if (tufts.isEmpty()) return listOf(parent)
        val diameter = contactDiameterPx.coerceAtLeast(0f)
        return tufts.map { tuft ->
            val dx = tuft.offsetXFraction * diameter
            val dy = tuft.offsetYFraction * diameter
            val physical = tuft.physicalRadiusPx > 0f
            val radius = if (physical) {
                tuft.physicalRadiusPx
            } else {
                parent.radius * tuft.radiusScale
            }.coerceAtLeast(0f)
            val alphaScale = (tuft.alphaScale * tuft.contactWeight).coerceIn(0f, 1f)

            parent.copy(
                x = parent.x + dx,
                y = parent.y + dy,
                radius = radius,
                alpha = (parent.alpha * alphaScale).coerceIn(0f, 1f),
                angleDeg = parent.angleDeg + tuft.angleOffsetDeg,
                contactDepth = (parent.contactDepth * tuft.contactWeight).coerceIn(0f, 1f),
                // A physical bundle is a cluster of fixed-diameter hairs. Do not inherit the
                // parent's broad/chisel aspect and silently turn every hair group into a scaled nib.
                tipRatio = if (physical) 1f else parent.tipRatio,
                mask = parent.mask?.let { mask ->
                    mask.copy(
                        x = mask.x + dx,
                        y = mask.y + dy,
                        radius = if (physical) radius else (mask.radius * tuft.radiusScale).coerceAtLeast(0f),
                        tipRatio = if (physical) 1f else mask.tipRatio,
                        angleDeg = mask.angleDeg + tuft.angleOffsetDeg,
                        alpha = (mask.alpha * alphaScale).coerceIn(0f, 1f),
                    )
                },
            )
        }
    }

    /** Compatibility-aware entry point used by the canonical and incremental generators. */
    fun expandIfEnabled(
        parent: Dab,
        contactDiameterPx: Float,
        contact: BrushContactState,
        config: BrushTuftConfig,
    ): List<Dab> = if (config.sanitized().emitsDabs()) {
        expand(parent, contactDiameterPx, contact.tufts)
    } else {
        listOf(parent)
    }
}
