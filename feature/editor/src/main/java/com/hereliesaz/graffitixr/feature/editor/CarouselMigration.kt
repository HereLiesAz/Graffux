package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.BrushAliasTable
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Moves favourites and per-item settings that name a removed built-in brush to its successor (or Round), once. The rule
 * that keeps it race-free: **nothing reads carousel state before the migration has completed.** Every reader of the
 * stored keys (the favourites flow, the item-settings loader, and the debounced whole-map write) calls [ensure] first,
 * which runs the migration if it has not run, waits for it if it is running, and returns at once when it is done; so a
 * reader never sees the old keys, and a later write can never put them back.
 */
internal class CarouselMigration(
    private val settings: SettingsRepository,
    private val builtInNames: () -> Set<String>,
) {
    private val lock = Mutex()
    private var done = false

    /** Completes only when the migration has finished (or failed once: it is retried by the next call, not looped). */
    suspend fun ensure() {
        if (done) return
        lock.withLock {
            if (done) return
            runCatching { migrate() }
                .onSuccess { done = true }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun migrate() {
        // a store that has not emitted is left alone: nothing is renamed
        val favourites = settings.carouselFavorites.firstOrNull() ?: return
        val itemKeys = settings.carouselItemSettings.firstOrNull().orEmpty().keys
        val names = builtInNames()
        val renames = BrushAliasTable.carouselKeyRenames(favourites + itemKeys) { it in names }
        if (renames.isNotEmpty()) settings.renameCarouselKeys(renames)
    }
}
