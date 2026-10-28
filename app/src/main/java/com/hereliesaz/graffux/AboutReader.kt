package com.hereliesaz.graffux

import androidx.compose.runtime.Composable
import com.hereliesaz.aznavrail.AzAboutPage

/** The repository the About reader reads its documents from. */
internal const val GRAFFUX_REPOSITORY_URL = "https://github.com/HereLiesAz/Graffux"

/**
 * AzNavRail's own About reader, full-screen: this repository's documents, More from Az and the
 * author footer. The one thing Graffux still takes from AzNavRail.
 */
@Suppress("FunctionNaming")
@Composable
internal fun AboutReader(onDismiss: () -> Unit) {
    AzAboutPage(onDismiss = onDismiss, repoUrl = GRAFFUX_REPOSITORY_URL)
}
