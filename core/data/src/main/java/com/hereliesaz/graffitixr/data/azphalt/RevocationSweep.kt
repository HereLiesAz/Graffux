package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.azphalt.RevocationEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consults the store's revocations feed (spec/repository-api.md § 5) and removes any installed
 * extension whose exact version its publisher or the store withdrew. Before this, the feed was
 * implemented and never read, so a yanked package stayed installed and runnable forever.
 *
 * Runs at most once a day, from launch; a failed fetch just tries again next launch.
 */
@Singleton
class RevocationSweep @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extensions: ExtensionRepository,
    private val client: RepositoryApiClient,
) {
    /** Returns the extensions removed, so the caller can tell the user. */
    fun run(nowMs: Long = System.currentTimeMillis()): List<RevocationEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = nowMs - prefs.getLong(KEY_LAST_RUN, 0L) >= DAY_MS
        val installed = extensions.installed.value
        // Nothing installed: nothing to sweep, and no request to make.
        val feed = if (due && installed.isNotEmpty()) {
            runCatching { client.revocations(prefs.getString(KEY_SINCE, null)) }.getOrNull()
        } else {
            null
        }
        val revoked = feed?.let { matching(installed.map { e -> e.id to e.manifest.version }, it) }.orEmpty()
        revoked.forEach { extensions.uninstall(it.id, nowMs) }
        if (due && (feed != null || installed.isEmpty())) {
            prefs.edit()
                .putLong(KEY_LAST_RUN, nowMs)
                .apply { feed?.maxOfOrNull { it.revokedAt }?.let { putString(KEY_SINCE, it) } }
                .apply()
        }
        return revoked
    }

    companion object {
        private const val PREFS = "azphalt_revocations"
        private const val KEY_LAST_RUN = "last_run"
        private const val KEY_SINCE = "since"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** The feed entries naming an installed (id, version) exactly. */
        fun matching(installed: List<Pair<String, String>>, feed: List<RevocationEntry>): List<RevocationEntry> {
            val have = installed.toSet()
            return feed.filter { (it.id to it.version) in have }.distinctBy { it.id to it.version }
        }
    }
}
