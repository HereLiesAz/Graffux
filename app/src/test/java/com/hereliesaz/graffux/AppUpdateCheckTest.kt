package com.hereliesaz.graffux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckTest {
    @Test fun newerPatchIsAnUpdate() = assertTrue(AppUpdateCheck.isNewer("1.50.27", "1.50.26.1"))
    @Test fun newerMinorBeatsHigherPatch() = assertTrue(AppUpdateCheck.isNewer("1.51.0", "1.50.99.1"))
    @Test fun sameReleaseIgnoresBuildField() = assertFalse(AppUpdateCheck.isNewer("1.50.26", "1.50.26.3"))
    @Test fun olderTagIsNotAnUpdate() = assertFalse(AppUpdateCheck.isNewer("1.50.25", "1.50.26.1"))
    @Test fun vPrefixIsAccepted() = assertTrue(AppUpdateCheck.isNewer("v1.50.27", "1.50.26.1"))
    @Test fun unparseableTagIsNotAnUpdate() = assertFalse(AppUpdateCheck.isNewer("", "1.50.26.1"))
}
