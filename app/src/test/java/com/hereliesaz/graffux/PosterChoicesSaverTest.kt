package com.hereliesaz.graffux

import androidx.compose.runtime.saveable.SaverScope
import com.hereliesaz.graffitixr.feature.editor.export.PosterPaper
import org.junit.Assert.assertEquals
import org.junit.Test

/** Round-2 audit: the Poster PDF choices were plain `remember` and reset on rotation. */
class PosterChoicesSaverTest {
    @Test
    fun `poster choices round-trip through the saver`() {
        val choices = PosterChoices(
            paper = PosterPaper.entries.last(), landscape = true, width = 4, margin = 0, overlap = 2,
            cropMarks = false, registrationMarks = false, pageLabels = false,
        )
        val scope = SaverScope { true }
        val saved = with(PosterChoicesSaver) { scope.save(choices) }!!
        assertEquals(choices, PosterChoicesSaver.restore(saved))
    }
}
