package com.hereliesaz.graffitixr.common.azphalt

/**
 * Converts one already-resolved parent contact dab into stable bundle dabs.
 *
 * This function is renderer-independent: once the generators opt into it, CPU and Vulkan continue
 * consuming ordinary [Dab] geometry. Empty tuft geometry is an exact identity operation so the
 * historical single-contact path remains untouched.
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
            parent.copy(
                x = parent.x + dx,
                y = parent.y + dy,
                radius = (parent.radius * tuft.radiusScale).coerceAtLeast(0f),
                alpha = (parent.alpha * tuft.alphaScale).coerceIn(0f, 1f),
                angleDeg = parent.angleDeg + tuft.angleOffsetDeg,
                mask = parent.mask?.let { mask ->
                    mask.copy(
                        x = mask.x + dx,
                        y = mask.y + dy,
                        radius = (mask.radius * tuft.radiusScale).coerceAtLeast(0f),
                        angleDeg = mask.angleDeg + tuft.angleOffsetDeg,
                        alpha = (mask.alpha * tuft.alphaScale).coerceIn(0f, 1f),
                    )
                },
            )
        }
    }
}
