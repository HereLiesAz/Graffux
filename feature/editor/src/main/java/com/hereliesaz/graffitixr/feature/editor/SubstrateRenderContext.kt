package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import com.hereliesaz.graffitixr.common.azphalt.SubstrateDepositionModel
import com.hereliesaz.graffitixr.common.azphalt.SubstrateField
import com.hereliesaz.graffitixr.common.azphalt.SubstrateProfile
import kotlin.math.floor

/**
 * Immutable, renderer-local bridge from the Phase 3 material reference to CPU stamp rasterization.
 *
 * The profile/medium/load are resolved once per stroke context. Per-pixel calls therefore do no
 * data-class allocation and only sample the static substrate tile plus the already-owned layer
 * height map. A null context remains the exact historical stamp-rendering path.
 */
internal class SubstrateRenderContext(
    profile: SubstrateProfile,
    val field: SubstrateField? = null,
    medium: PaintMedium,
    reservoirLoad: Float = 1f,
    val paintHeight: FloatArray? = null,
) {
    val profile: SubstrateProfile = profile.sanitized()
    private val resolvedMedium = medium.sanitized()
    private val resolvedLoad = reservoirLoad.coerceIn(0f, 1f)

    fun depositionAt(
        canvasX: Float,
        canvasY: Float,
        dab: Dab,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Float {
        val localPaintHeight = paintHeightAt(canvasX, canvasY, canvasWidth, canvasHeight)
        val substrateHeight = field?.sampleHeight(canvasX, canvasY, profile)
            ?: profile.baseHeight
        return SubstrateDepositionModel.depositionMultiplier(
            contactDepth = dab.contactDepth,
            localPaintHeightContribution = localPaintHeight,
            reservoirLoad = resolvedLoad,
            depositionRate = resolvedMedium.depositionRate,
            substrateResponse = resolvedMedium.substrateResponse,
            substrateHeight = substrateHeight,
        )
    }

    private fun paintHeightAt(
        canvasX: Float,
        canvasY: Float,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Float {
        val height = paintHeight ?: return 0f
        if (canvasWidth <= 0 || canvasHeight <= 0 || height.size != canvasWidth * canvasHeight) return 0f
        val x = floor(canvasX).toInt().coerceIn(0, canvasWidth - 1)
        val y = floor(canvasY).toInt().coerceIn(0, canvasHeight - 1)
        return height[y * canvasWidth + x].coerceAtLeast(0f)
    }
}
