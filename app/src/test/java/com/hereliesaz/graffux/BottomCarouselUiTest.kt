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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
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
 * The carousel as composed: one hero card (the only card showing details), and a star that toggles
 * a favorite without selecting the item. Set CAROUSEL_SCREENSHOT to a .png path to also save a render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class BottomCarouselUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val selectedName = BuiltInBrushes.presets[2].name

    private fun inputs(favorites: List<String>) = CarouselInputs(
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
        favorites = favorites,
    )

    @Test
    fun `one hero card, centred on the selection, and the star toggles without selecting`() {
        val clicks = mutableListOf<String>()
        var favorites by mutableStateOf(emptyList<String>())
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui = remember { CarouselUi(CarouselCategory.BRUSHES, sheetOpen = true) }
                Box(Modifier.fillMaxSize().background(Color(0xFF303030)), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(open = ui.sheetOpen, onOpenChange = {}) {
                        BottomCarousel(
                            ui = ui,
                            onUiChange = {},
                            content = CarouselContent(
                                entries = carouselEntries(ui.category, inputs(favorites)),
                                brushColor = Color.White,
                                secondaryColor = Color.Red,
                                extensionPreviews = emptyMap(),
                            ),
                            history = CarouselHistory(undoCount = 1, redoCount = 0, onUndo = {}, onRedo = {}),
                            onEntryClick = { clicks += it.key },
                            onToggleFavorite = { favorites = toggleCarouselFavorite(favorites, it.key) },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        System.getenv("CAROUSEL_SCREENSHOT")?.let { path ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // Details render only on the hero card, so exactly one card carries a details line.
        assertEquals(1, rule.onAllNodesWithText("Built-in · round tip").fetchSemanticsNodes().size)
        // And it is the selected brush's card: its name is on screen.
        assertTrue(rule.onAllNodesWithText(selectedName).fetchSemanticsNodes().isNotEmpty())

        rule.onAllNodesWithContentDescription("Add to favorites")[0].performClick()
        rule.waitForIdle()
        assertEquals(1, favorites.size)
        assertTrue("the star must not select the item", clicks.isEmpty())
    }
}
