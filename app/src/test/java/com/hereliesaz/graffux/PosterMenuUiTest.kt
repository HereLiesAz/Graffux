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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.hereliesaz.graffitixr.feature.editor.export.PosterSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Poster PDF page in the open logo menu, as MainActivity builds it: a value row cycles in
 * place and keeps the menu open, Export hands over the spec. Set POSTER_SCREENSHOTS to a directory
 * to save the render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class PosterMenuUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the poster page cycles in place and exports`() {
        var choices by mutableStateOf(PosterChoices())
        var open by mutableStateOf(true)
        var exported: PosterSpec? = null
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    LogoMenu(
                        open = open,
                        onOpenChange = { open = it },
                        rows = buildMenu {
                            posterPage(choices, true, 5400, 7200, { choices = it }, {}, { exported = it })
                        },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
        rule.waitForIdle()
        System.getenv("POSTER_SCREENSHOTS")?.let { dir ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "app-PosterMenu.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.onNodeWithText("Paper · Letter").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Paper · A4").assertExists()
        assertEquals(true, open)
        rule.onNodeWithText("Export 16 pages (4 × 4)").performClick()
        rule.waitForIdle()
        assertEquals(24f * 72f, exported!!.posterWidthPt, 0.01f)
    }
}
