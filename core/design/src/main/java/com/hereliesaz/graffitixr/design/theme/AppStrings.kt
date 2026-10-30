package com.hereliesaz.graffitixr.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.R

@Composable
fun rememberAppStrings(): AppStrings {
    return AppStrings(
        nav = rememberNavStrings(),
        common = AppStrings.Common(
            cancel = stringResource(R.string.cancel_button),
            close = stringResource(R.string.close_button),
        ),
        editor = AppStrings.Editor(
            duplicate = stringResource(R.string.duplicate),
            hideLayer = stringResource(R.string.hide_layer),
            showLayer = stringResource(R.string.show_layer),
            delete = stringResource(R.string.delete),
        ),
        adj = AppStrings.Adjustments(
            detail = stringResource(R.string.adj_detail),
            opacity = stringResource(R.string.adj_opacity),
            brightness = stringResource(R.string.adj_brightness),
            contrast = stringResource(R.string.adj_contrast),
            saturation = stringResource(R.string.adj_saturation),
            red = stringResource(R.string.adj_red),
            green = stringResource(R.string.adj_green),
            blue = stringResource(R.string.adj_blue),
            undo = stringResource(R.string.adj_undo),
            redo = stringResource(R.string.adj_redo),
        ),
        layerMenu = rememberLayerMenuStrings(),
        mainMenu = rememberMainMenuStrings(),
        rail = rememberRailStrings(),
        messages = rememberMessageStrings(),
        gestureHelp = rememberGestureHelpStrings(),
    )
}

data class AppStrings(
    val nav: NavStrings,
    val common: Common,
    val editor: Editor,
    val adj: Adjustments,
    val layerMenu: LayerMenuStrings,
    val mainMenu: MainMenuStrings,
    val rail: RailStrings,
    val messages: MessageStrings,
    val gestureHelp: GestureHelpStrings,
) {
    data class Common(
        val cancel: String,
        val close: String,
    )
    data class Editor(
        val duplicate: String,
        val hideLayer: String,
        val showLayer: String,
        val delete: String,
    )
    data class Adjustments(
        val detail: String,
        val opacity: String,
        val brightness: String,
        val contrast: String,
        val saturation: String,
        val red: String,
        val green: String,
        val blue: String,
        val undo: String,
        val redo: String,
    )
}
