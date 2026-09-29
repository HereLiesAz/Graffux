@file:Suppress("MaxLineLength")

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The carousel sheet as composed: it drops out of the way the moment a stroke starts and comes back
 * when it ends (unless the user had shut it), and a hero slider edits only its own item. Set
 * CAROUSEL_SCRIM_SCREENSHOT to a .png path to save a render showing the scrim.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselSheetStrokeUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val selectedName = BuiltInBrushes.presets[2].name
    private val heroKey = "builtin.$selectedName"
    private val otherKey = "builtin.${BuiltInBrushes.presets[1].name}"

    private var stroke by mutableStateOf(false)
    private var userOpen by mutableStateOf(true)
    private val stored = mutableStateMapOf(
        heroKey to CarouselItemSettings(size = 20f),
        otherKey to CarouselItemSettings(size = 150f, flow = 0.3f),
    )

    private val inputs = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = selectedName,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
        toolOptionsOpen = false,
    )

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF8A8A8A)), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(
                        open = userOpen,
                        onOpenChange = { userOpen = it },
                        strokeActive = { stroke },
                        bottomInset = 32.dp,
                    ) {
                        BottomCarousel(
                            ui = CarouselUi(CarouselCategory.BRUSHES, sheetOpen = userOpen),
                            onUiChange = {},
                            content = CarouselContent(
                                entries = carouselEntries(CarouselCategory.BRUSHES, inputs),
                                brushColor = Color.White,
                                secondaryColor = Color.Red,
                                extensionPreviews = emptyMap(),
                                heroState = HeroAdjustmentState(
                                    brushSize = 50f, brushFlow = 1f, brushOpacity = 1f, brushFeathering = 0f,
                                    smudgeRate = 0.5f, stabilizerLevel = 0, magicWandTolerance = 32,
                                ),
                                itemSettings = { e -> carouselSettingsKey(e)?.let { carouselItemSettingsFor(it, stored) } },
                                // As MainActivity does for an item: store the adjusted settings under its key.
                                onAdjust = { e, setter, v ->
                                    carouselSettingsKey(e)?.let { key ->
                                        carouselItemSettingsFor(key, stored).adjusted(setter, v)?.let { stored[key] = it }
                                    }
                                },
                            ),
                            history = CarouselHistory(undoCount = 1, redoCount = 1, onUndo = {}, onRedo = {}),
                            onEntryClick = {},
                            onToggleFavorite = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    // Unclipped: the sheet clips its content as it slides away.
    private fun rowTop() = rule.onNodeWithTag("carousel.row", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `the sheet shuts the moment a stroke starts and comes back when it ends`() {
        compose()
        val openTop = rowTop()
        // Where the row sits when the sheet is fully shut.
        userOpen = false
        rule.waitForIdle()
        val shutTop = rowTop()
        userOpen = true
        rule.waitForIdle()
        assertEquals(openTop, rowTop(), 1f)

        rule.mainClock.autoAdvance = false
        stroke = true
        // Reading the tree each frame syncs it, as a real frame would be drawn.
        repeat(STROKE_FRAMES) { rule.mainClock.advanceTimeByFrame(); rowTop() }
        // Snapped, not animated: a few frames after the stroke began, the sheet is all the way shut.
        assertTrue(shutTop > openTop + 200f)
        assertEquals("fully shut within $STROKE_FRAMES frames", shutTop, rowTop(), 1f)
        assertTrue("the user's choice is untouched", userOpen)
        rule.mainClock.autoAdvance = true

        stroke = false
        rule.waitForIdle()
        assertEquals("reopened where it was", openTop, rowTop(), 1f)
    }

    private companion object {
        const val STROKE_FRAMES = 3
    }

    @Test
    fun `a sheet the user shut stays shut after a stroke`() {
        compose()
        val openTop = rowTop()
        userOpen = false
        rule.waitForIdle()
        val shutTop = rowTop()
        assertTrue(shutTop > openTop + 200f)

        stroke = true
        rule.waitForIdle()
        stroke = false
        rule.waitForIdle()
        assertEquals(shutTop, rowTop(), 1f)
    }

    @Test
    fun `a hero slider changes only its own item's settings`() {
        compose()
        val before = stored.getValue(otherKey)
        rule.onNodeWithTag("carousel.hero.slider.size", useUnmergedTree = true).performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.9f, centerY), durationMillis = 600)
        }
        rule.waitForIdle()
        assertTrue("hero size rose from 20, was ${stored.getValue(heroKey).size}", stored.getValue(heroKey).size > 100f)
        assertEquals("the other brush is untouched", before, stored.getValue(otherKey))
    }

    @Test
    fun `a full-width scrim sits behind the sheet`() {
        compose()
        val scrim = rule.onNodeWithTag("carousel.scrim", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("full width", root.width, scrim.width, 1f)
        assertTrue("reaches the bottom edge", scrim.bottom >= root.bottom - 1f)
        assertTrue("starts above the stroke preview", scrim.top <= rowTop())

        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        // Mid-grey behind; the scrim darkens it at the bottom corners, outside any card.
        val top = bitmap.getPixel(bitmap.width / 2, 4)
        val corner = bitmap.getPixel(2, bitmap.height - 4)
        assertTrue("scrim darkens", android.graphics.Color.red(corner) < android.graphics.Color.red(top) - 20)
        assertTrue("but stays see-through", android.graphics.Color.red(corner) > 40)
        System.getenv("CAROUSEL_SCRIM_SCREENSHOT")?.let { path ->
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
