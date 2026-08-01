package com.hereliesaz.graffitixr.data.azphalt

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/**
 * The standalone half of state reporting (azphalt spec/state-reporting.md § 3.2).
 *
 * When the user opens the store app directly rather than through this app, there is no browse request
 * and therefore no inventory extra — so a store would show *Get* on everything the user already has.
 * A host may expose its states through a `ContentProvider` so the store can read them anyway.
 *
 * **Read-only, and that is normative**: a store app has no business writing a host's state. The
 * mutating operations here do not fail quietly; they throw, because a store that believes it wrote
 * something is worse than one that knows it cannot.
 *
 * Access is gated by `azphalt.permission.READ_EXTENSION_STATE` (`android:readPermission` in the
 * manifest, protection level `normal`), and the authority is handed to the store on a browse request
 * via `azphalt.extra.STATE_AUTHORITY` — a store MUST NOT query an authority it was not given. Nothing
 * here enumerates other apps: this host reports its own extensions and nothing else, which is why no
 * `QUERY_ALL_PACKAGES` permission appears anywhere in this feature.
 *
 * Nothing in a row identifies a device, a user, or an install (spec § 2). The columns are exactly the
 * five the entry shape defines, all `TEXT`, with `at` as the ISO-8601 string rather than millis so a
 * row and a wire entry are the same thing.
 */
class ExtensionStateProvider : ContentProvider() {

    private lateinit var store: ExtensionStateStore

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        store = ExtensionStateStore(File(ctx.filesDir, ExtensionStateStore.FILE_NAME))
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.pathSegments.firstOrNull() != PATH_EXTENSIONS) return null
        // `selection` and `sortOrder` are deliberately not honoured: the table is one row per
        // installed package — tens of rows, not thousands — so a consumer filtering client-side costs
        // nothing, and a hand-rolled SQL-fragment parser on input from another application would be a
        // liability out of all proportion to what it buys.
        val columns = projection?.takeIf { it.isNotEmpty() } ?: COLUMNS
        val cursor = MatrixCursor(columns)
        for (entry in store.all()) {
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        COLUMN_ID -> entry.id
                        COLUMN_VERSION -> entry.version
                        COLUMN_STATE -> entry.state
                        COLUMN_AT -> entry.at
                        COLUMN_REASON -> entry.reason
                        else -> null
                    }
                }
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = when (uri.pathSegments.firstOrNull()) {
        PATH_EXTENSIONS -> "vnd.android.cursor.dir/vnd.azphalt.extension-state"
        else -> throw IllegalArgumentException("Unsupported path: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("azphalt extension state is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("azphalt extension state is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("azphalt extension state is read-only")

    companion object {
        /** Path segment carrying one row per package id (spec § 3.2). */
        const val PATH_EXTENSIONS: String = "extensions"

        const val COLUMN_ID: String = "id"
        const val COLUMN_VERSION: String = "version"
        const val COLUMN_STATE: String = "state"
        const val COLUMN_AT: String = "at"
        const val COLUMN_REASON: String = "reason"

        val COLUMNS: Array<String> = arrayOf(COLUMN_ID, COLUMN_VERSION, COLUMN_STATE, COLUMN_AT, COLUMN_REASON)

        /** The permission a store app must hold to read these rows (spec § 3.2). */
        const val READ_PERMISSION: String = "azphalt.permission.READ_EXTENSION_STATE"

        /** The authority for a host whose application id is [applicationId]. */
        fun authority(applicationId: String): String = "$applicationId.azphalt.state"

        /** `content://<authority>/extensions` — the one queryable path. */
        fun contentUri(applicationId: String): Uri =
            Uri.parse("content://${authority(applicationId)}/$PATH_EXTENSIONS")
    }
}
