@file:Suppress("MagicNumber") // The paper-length preset tables and their default indices.

package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import com.hereliesaz.graffitixr.feature.editor.export.PosterLayout
import com.hereliesaz.graffitixr.feature.editor.export.PosterPaper
import com.hereliesaz.graffitixr.feature.editor.export.PosterSpec
import kotlin.math.roundToInt

/**
 * The logo menu's Poster PDF page: what the next poster prints like. Each value row cycles to its
 * next choice and keeps the menu open; Export closes it and writes the PDF. Lengths are chosen
 * from short lists in inches or centimetres (the Settings units), never typed.
 */
internal data class PosterChoices(
    val paper: PosterPaper = PosterPaper.LETTER,
    val landscape: Boolean = false,
    val width: Int = 2,
    val margin: Int = 1,
    val overlap: Int = 1,
    val cropMarks: Boolean = true,
    val registrationMarks: Boolean = true,
    val pageLabels: Boolean = true,
) {
    fun spec(imperial: Boolean): PosterSpec {
        val unit = if (imperial) PosterLayout.POINTS_PER_INCH else PosterLayout.POINTS_PER_CM
        val u = units(imperial)
        return PosterSpec(
            paper = paper,
            landscape = landscape,
            posterWidthPt = u.widths[width] * unit,
            marginPt = u.margins[margin] * unit,
            overlapPt = u.overlaps[overlap] * unit,
            cropMarks = cropMarks,
            registrationMarks = registrationMarks,
            pageLabels = pageLabels,
        )
    }

    companion object {
        class Units(val suffix: String, val widths: List<Float>, val margins: List<Float>, val overlaps: List<Float>)

        val INCHES = Units(
            "in",
            listOf(12f, 18f, 24f, 36f, 48f, 60f, 72f, 96f),
            listOf(0.25f, 0.5f, 0.75f, 1f),
            listOf(0f, 0.25f, 0.5f, 1f),
        )
        val CENTIMETRES = Units(
            "cm",
            listOf(30f, 45f, 60f, 90f, 120f, 150f, 200f, 250f),
            listOf(0.5f, 1f, 1.5f, 2f),
            listOf(0f, 0.5f, 1f, 2f),
        )

        fun units(imperial: Boolean) = if (imperial) INCHES else CENTIMETRES
    }
}

/** A length as the row shows it: "24 in", "0.25 in", "1.5 cm". */
internal fun posterLength(value: Float, suffix: String): String {
    val hundredths = (value * 100f).roundToInt()
    val text = when {
        hundredths % 100 == 0 -> "${hundredths / 100}"
        hundredths % 10 == 0 -> "${hundredths / 100}.${(hundredths % 100) / 10}"
        else -> "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
    return "$text $suffix"
}

/** The artwork's pixel size after the document crop, as ExportManager produces it. */
internal fun posterArtSize(docW: Int, docH: Int, crop: DocumentCrop): Pair<Int, Int> =
    if (crop.isIdentity) {
        docW to docH
    } else {
        (docW * (crop.right - crop.left)).roundToInt().coerceAtLeast(1) to
            (docH * (crop.bottom - crop.top)).roundToInt().coerceAtLeast(1)
    }

private fun <T> List<T>.after(index: Int) = (index + 1) % size

/** The Poster PDF page's rows. [artW]×[artH] is the cropped artwork, for the page count. */
@Suppress("LongParameterList")
internal fun MenuBuilder.posterPage(
    choices: PosterChoices,
    imperial: Boolean,
    artW: Int,
    artH: Int,
    onChange: (PosterChoices) -> Unit,
    onBack: () -> Unit,
    onExport: (PosterSpec) -> Unit,
) {
    val u = PosterChoices.units(imperial)
    val spec = choices.spec(imperial)
    val plan = PosterLayout.plan(spec, artW.coerceAtLeast(1), artH.coerceAtLeast(1))
    val unit = if (imperial) PosterLayout.POINTS_PER_INCH else PosterLayout.POINTS_PER_CM
    page("‹ Back", onBack)
    page("Paper · ${choices.paper.label}") {
        onChange(choices.copy(paper = PosterPaper.entries[PosterPaper.entries.after(choices.paper.ordinal)]))
    }
    page("Orientation · ${if (choices.landscape) "Landscape" else "Portrait"}") {
        onChange(choices.copy(landscape = !choices.landscape))
    }
    val height = posterLength((plan.posterHeightPt / unit * 10f).roundToInt() / 10f, u.suffix)
    page("Size · ${posterLength(u.widths[choices.width], u.suffix)} × $height") {
        onChange(choices.copy(width = u.widths.after(choices.width)))
    }
    page("Margins · ${posterLength(u.margins[choices.margin], u.suffix)}") {
        onChange(choices.copy(margin = u.margins.after(choices.margin)))
    }
    page("Overlap · ${posterLength(u.overlaps[choices.overlap], u.suffix)}") {
        onChange(choices.copy(overlap = u.overlaps.after(choices.overlap)))
    }
    toggle("Crop marks", choices.cropMarks) { onChange(choices.copy(cropMarks = it)) }
    toggle("Registration marks", choices.registrationMarks) { onChange(choices.copy(registrationMarks = it)) }
    toggle("Page labels", choices.pageLabels) { onChange(choices.copy(pageLabels = it)) }
    divider()
    val pages = if (plan.pageCount == 1) "1 page" else "${plan.pageCount} pages (${plan.rows} × ${plan.columns})"
    listItem("Export $pages") { onExport(spec) }
}

/** Keeps the Poster PDF choices across rotation and process death (rememberSaveable). */
internal val PosterChoicesSaver: androidx.compose.runtime.saveable.Saver<PosterChoices, Any> =
    androidx.compose.runtime.saveable.listSaver(
        save = {
            listOf(
                it.paper.name, it.landscape, it.width, it.margin, it.overlap,
                it.cropMarks, it.registrationMarks, it.pageLabels,
            )
        },
        restore = { v ->
            PosterChoices(
                paper = PosterPaper.entries.firstOrNull { it.name == v[0] } ?: PosterPaper.LETTER,
                landscape = v[1] as Boolean,
                width = v[2] as Int,
                margin = v[3] as Int,
                overlap = v[4] as Int,
                cropMarks = v[5] as Boolean,
                registrationMarks = v[6] as Boolean,
                pageLabels = v[7] as Boolean,
            )
        },
    )
