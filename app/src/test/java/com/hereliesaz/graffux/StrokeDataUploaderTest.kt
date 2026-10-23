package com.hereliesaz.graffux

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeDataUploaderTest {
    @Test fun modelIsSanitised() = assertEquals("Pixel-8-Pro", StrokeDataUploader.deviceLabel("Pixel 8 Pro"))
    @Test fun emptyModelIsUnknown() = assertEquals("unknown", StrokeDataUploader.deviceLabel("  "))
    @Test fun longModelIsCapped() = assertEquals(48, StrokeDataUploader.deviceLabel("a".repeat(80)).length)
    @Test fun nullModelIsUnknown() = assertEquals("unknown", StrokeDataUploader.deviceLabel(null))
}
