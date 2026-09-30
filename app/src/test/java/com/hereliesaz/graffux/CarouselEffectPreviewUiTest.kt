package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.azphalt.applyCubeLut
import com.hereliesaz.graffitixr.common.azphalt.parseCubeLut
import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Effect cards' hero preview: the active layer with the effect applied. The LUT here is a real
 * `.cube` inversion graded by `applyCubeLut`, the same call `EditorViewModel.applyInstalledLut`
 * commits through. Set CAROUSEL_EFFECT_SCREENSHOT to a .png path to save a render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselEffectPreviewUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val invert = parseCubeLut(
        buildString {
            appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("${1 - r} ${1 - g} ${1 - b}")
        },
    )

    private val lut = ExtensionEffect("inv", "Invert Pack", ExtensionEffectKind.LUT, null, "Invert")
    private val lutEntry = extensionEffectEntry(lut)
    private val filterEntry = extensionEffectEntry(
        ExtensionEffect("fx", "FX Pack", ExtensionEffectKind.FILTER, "glow", "Glow"),
    )

    /** A colourful "layer": a horizontal hue-ish gradient over a vertical one, 1024×640. */
    private fun layer(): Bitmap {
        val w = 1024
        val h = 640
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            android.graphics.Color.argb(255, 230 - x * 60 / w, 60 + y * 160 / h, 200 - x * 120 / w)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun inputs() = CarouselInputs(
        activeTool = Tool.NONE,
        activeBrushName = BuiltInBrushes.presets[0].name,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
    )

    private val seenParams = mutableListOf<CarouselItemSettings?>()

    private fun engine() = EffectPreviewEngine({ entry, source, params ->
        seenParams += params
        if (entry.action is CarouselAction.ExtensionLut) source.applyCubeLut(invert) else null
    }).also { runBlocking { it.updateSource(layer()) } }

    private var settings by mutableStateOf<CarouselItemSettings?>(null)

    private fun show(engine: EffectPreviewEngine, entries: List<CarouselEntry>) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF303030)), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(open = true, onOpenChange = {}) {
                        BottomCarousel(
                            ui = CarouselUi(CarouselCategory.EFFECTS, sheetOpen = true),
                            onUiChange = {},
                            content = CarouselContent(
                                entries = entries,
                                brushColor = Color.White,
                                secondaryColor = Color.Red,
                                extensionPreviews = emptyMap(),
                                itemSettings = { if (it.key == lutEntry.key) settings else null },
                                effectPreviews = engine,
                            ),
                            history = CarouselHistory(0, 0, {}, {}),
                            onEntryClick = {},
                            onToggleFavorite = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun waitForTag(tag: String) = rule.waitUntil(timeoutMillis = 10_000) {
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `a LUT card in the hero shows the layer thumbnail with the LUT applied`() {
        val engine = engine()
        show(engine, listOf(lutEntry, filterEntry) + carouselEntries(CarouselCategory.EFFECTS, inputs()))
        waitForTag("carousel.effect.${lutEntry.key}")
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        val snap = engine.snapshot!!
        assertEquals(256, maxOf(snap.bitmap.width, snap.bitmap.height))
        val key = engine.keyFor(lutEntry, null)!!
        val thumb = engine.cached(key)
        assertNotNull(thumb)
        thumb!!
        val sx = snap.bitmap.width / 2
        val sy = snap.bitmap.height / 2
        val src = snap.bitmap.getPixel(sx, sy)
        val out = thumb.getPixel(sx, sy)
        assertTrue("thumbnail pixels must differ from the source", src != out)
        assertTrue(abs(255 - android.graphics.Color.red(src) - android.graphics.Color.red(out)) <= 2)

        // And what is on screen is the graded thumbnail, not the source.
        val shot = rule.onNodeWithTag("carousel.effect.${lutEntry.key}", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val onScreen = shot.getPixel(shot.width / 2, shot.height / 2)
        assertTrue(abs(android.graphics.Color.red(onScreen) - android.graphics.Color.red(out)) < 24)
        assertTrue(abs(android.graphics.Color.red(onScreen) - android.graphics.Color.red(src)) > 40)

        System.getenv("CAROUSEL_EFFECT_SCREENSHOT")?.let { path ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun `changing the card's settings re-renders its thumbnail`() {
        val engine = engine()
        show(engine, listOf(lutEntry) + carouselEntries(CarouselCategory.EFFECTS, inputs()))
        waitForTag("carousel.effect.${lutEntry.key}")
        val before = engine.renderCount

        settings = CarouselItemSettings(size = 80f)
        rule.waitForIdle()
        rule.waitUntil(10_000) { engine.cached(engine.keyFor(lutEntry, settings)!!) != null }
        assertTrue(engine.renderCount > before)
        assertEquals(settings, seenParams.last())
        // The old parameters' thumbnail is still cached; a return to them is a hit, not a render.
        assertNotNull(engine.cached(engine.keyFor(lutEntry, null)!!))
    }

    @Test
    fun `an installed filter falls back to its icon, and a new snapshot invalidates thumbnails`() {
        val engine = engine()
        show(engine, listOf(filterEntry, lutEntry))
        waitForTag("carousel.effect.fallback.${filterEntry.key}")

        val oldGen = engine.snapshot!!.generation
        runBlocking { engine.updateSource(layer()) }
        assertTrue(engine.snapshot!!.generation > oldGen)
        assertTrue(engine.cache.keys.none { it.generation == oldGen })
    }

    @Test
    fun `brush cards still show strokes`() {
        val engine = engine()
        val entries = carouselEntries(CarouselCategory.BRUSHES, inputs())
        show(engine, entries)
        val hero = entries[selectedCarouselIndex(entries) ?: 0]
        waitForTag("carousel.preview.${hero.key}")
        assertTrue(
            rule.onAllNodesWithTag("carousel.effect.${hero.key}", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(0, engine.renderCount)
    }
}
