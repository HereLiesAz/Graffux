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
            done = stringResource(R.string.done_button),
            apply = stringResource(R.string.apply_button),
            close = stringResource(R.string.close_button),
            next = stringResource(R.string.next_button),
            save = stringResource(R.string.save_button),
            capture = stringResource(R.string.capture_button),
            reset = stringResource(R.string.reset_button),
        ),
        settings = AppStrings.Settings(
            title = stringResource(R.string.settings_title),
            preferences = stringResource(R.string.settings_preferences_section),
            on = stringResource(R.string.settings_on),
            version = stringResource(R.string.settings_version_label),
            updates = stringResource(R.string.settings_updates_section),
        ),
        editor = AppStrings.Editor(
            layers = stringResource(R.string.layers_title),
            brushSize = stringResource(R.string.brush_size_title),
            duplicate = stringResource(R.string.duplicate),
            hideLayer = stringResource(R.string.hide_layer),
            showLayer = stringResource(R.string.show_layer),
            delete = stringResource(R.string.delete),
            lock = stringResource(R.string.lock_button),
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
        )
    )
}

data class AppStrings(
    val nav: NavStrings,
    val common: Common,
    val settings: Settings,
    val editor: Editor,
    val adj: Adjustments
) {
    data class Common(
        val cancel: String,
        val done: String,
        val apply: String,
        val close: String,
        val next: String,
        val save: String,
        val capture: String,
        val reset: String,
    )
    data class Settings(
        val title: String,
        val preferences: String,
        val on: String,
        val version: String,
        val updates: String,
    )
    data class Editor(
        val layers: String,
        val brushSize: String,
        val duplicate: String,
        val hideLayer: String,
        val showLayer: String,
        val delete: String,
        val lock: String,
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
