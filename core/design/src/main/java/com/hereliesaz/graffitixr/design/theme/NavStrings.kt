package com.hereliesaz.graffitixr.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.R

@Composable
fun rememberNavStrings(): NavStrings {
    return NavStrings(
        open = stringResource(R.string.nav_open),
        adjust = stringResource(R.string.nav_adjust),
        invert = stringResource(R.string.nav_invert),
        new = stringResource(R.string.nav_new),
        save = stringResource(R.string.nav_save),
        export = stringResource(R.string.nav_export),
        brush = stringResource(R.string.nav_brush),
        share = stringResource(R.string.nav_share),
        undo = stringResource(R.string.adj_undo),
        redo = stringResource(R.string.adj_redo),
        dodge = stringResource(R.string.nav_dodge),
        burn = stringResource(R.string.nav_burn),
    )
}

data class NavStrings(
    val open: String,
    val adjust: String,
    val invert: String,
    val new: String,
    val save: String,
    val export: String,
    val brush: String,
    val share: String,
    val undo: String,
    val redo: String,
    val dodge: String,
    val burn: String,
)
