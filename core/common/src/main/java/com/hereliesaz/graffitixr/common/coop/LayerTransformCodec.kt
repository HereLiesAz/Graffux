package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Layer

/**
 * The `Op.LayerTransform` wire encoding, in one place for every sender and the receiver.
 *
 * Sixteen floats: `[scale, offsetX, offsetY, rotX, rotY, rotZ, positionZ, 0 x 9]`. Slots 0-5 are
 * what every build has always sent; slot 6, the plane's depth in the 3D space (spec/3d-space.md),
 * was always written as 0, which is exactly the depth of a layer from a build that predates depth.
 * So an old peer's message decodes to depth 0, and an old peer reading ours ignores slot 6.
 * Together slots 0-6 are a layer's whole plane placement.
 */
object LayerTransformCodec {
    const val SIZE = 16
    private const val SLOT_DEPTH = 6
    private const val LEGACY_SIZE = 6

    data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val rotationX: Float,
        val rotationY: Float,
        val rotationZ: Float,
        val positionZ: Float,
    )

    fun encode(layer: Layer): List<Float> = List(SIZE) { i ->
        when (i) {
            0 -> layer.scale
            1 -> layer.offset.x
            2 -> layer.offset.y
            3 -> layer.rotationX
            4 -> layer.rotationY
            5 -> layer.rotationZ
            SLOT_DEPTH -> layer.positionZ
            else -> 0f
        }
    }

    /** Null for a message too short to carry a transform at all. */
    fun decode(matrix: List<Float>): Transform? {
        if (matrix.size < LEGACY_SIZE) return null
        val depth = matrix.getOrNull(SLOT_DEPTH)?.takeIf { it.isFinite() } ?: 0f
        return Transform(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5], depth)
    }
}
