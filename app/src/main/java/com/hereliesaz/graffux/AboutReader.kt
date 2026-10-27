@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.currentComposer
import com.hereliesaz.aznavrail.AzNavRailScopeImpl

/** The repository the About reader reads its documents from. */
internal const val GRAFFUX_REPOSITORY_URL = "https://github.com/HereLiesAz/Graffux"

/**
 * AzNavRail's own About reader, full-screen: this repository's documents, More from Az and the
 * author footer. The one thing Graffux still takes from AzNavRail.
 *
 * The library has no public standalone About entry point, so this calls the two overlays its own
 * drop-down menu calls (`AboutOverlay`, and `MoreFromAzOverlay` for the "More from Az" page) through
 * [AzAboutBridge], each given a fresh, otherwise-empty rail scope — the overlays read only their
 * theme defaults from it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun AboutReader(onDismiss: () -> Unit) {
    val scope = remember { AzNavRailScopeImpl() }
    var moreFromAz by remember { mutableStateOf(false) }
    BackHandler { if (moreFromAz) moreFromAz = false else onDismiss() }
    if (moreFromAz) {
        AzAboutBridge.moreFromAz(scope, { moreFromAz = false }, currentComposer)
    } else {
        AzAboutBridge.about(GRAFFUX_REPOSITORY_URL, scope, { moreFromAz = true }, onDismiss, currentComposer)
    }
}
