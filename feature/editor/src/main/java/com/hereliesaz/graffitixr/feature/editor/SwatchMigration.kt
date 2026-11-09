package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipes
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull

/**
 * Once per install the old saved swatches become mixes (nearest tube mix, original colour kept, approximate flagged).
 * The old list is left where it is, so it can be undone, and a mix deleted later is not brought back (a flag, not a
 * re-run on every launch). The flag is set ONLY after the swatches were really read and really added: a failed read
 * (not "no swatches") or a failed write leaves it unset so the next launch tries again (adding is idempotent).
 */
internal object SwatchMigration {
    /** True if the migration is (now) complete. */
    suspend fun run(settings: SettingsRepository, library: RecipeLibrary, board: PaletteBoard): Boolean {
        val done = settings.swatchesMigrated.firstOrNull() == false && import(settings, library, board)
        if (done) settings.setSwatchesMigrated()
        return done || settings.swatchesMigrated.firstOrNull() != false
    }

    private suspend fun import(settings: SettingsRepository, library: RecipeLibrary, board: PaletteBoard): Boolean {
        val swatches = settings.readSavedPalette() // null: the read failed, so try again next launch
        val recipes = swatches?.let { PaletteRecipes.migrateSwatches(it, MIXER_TUBES, board) }
        return recipes != null && library.addMissing(RecipeLibrary.Target.App, recipes)
    }
}
