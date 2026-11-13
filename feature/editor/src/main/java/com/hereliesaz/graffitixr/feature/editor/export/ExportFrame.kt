@file:Suppress("MagicNumber") // the [left, top, width, height] layout artboardRect returns

package com.hereliesaz.graffitixr.feature.editor.export

import com.hereliesaz.graffitixr.common.photo.DocumentCrop

/**
 * The rectangle a document export keeps, as `[left, top, width, height]` in the exporter's [worldW] x
 * [worldH] world pixels: the artboard ([artboardRect]), or, with a non-identity [crop], the crop's fractions
 * of it (the rectangle `DocumentCropRender.render` and `VectorScene.documentPage` cut). The viewport (pan,
 * zoom, rotation) is not a parameter: the exporter ignores it. This is what the export-frame preview draws.
 */
fun exportFrameRect(worldW: Int, worldH: Int, docW: Int, docH: Int, crop: DocumentCrop?): FloatArray {
    val art = artboardRect(worldW, worldH, docW, docH)
    val c = crop?.takeIf { !it.isIdentity && docW > 0 && docH > 0 } ?: return art
    return floatArrayOf(
        art[0] + c.left * art[2],
        art[1] + c.top * art[3],
        (c.right - c.left) * art[2],
        (c.bottom - c.top) * art[3],
    )
}
