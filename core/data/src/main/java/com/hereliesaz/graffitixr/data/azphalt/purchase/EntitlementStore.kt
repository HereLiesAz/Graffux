package com.hereliesaz.graffitixr.data.azphalt.purchase

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Where an entitlement came from. Every source is honoured the same; this is only for restore. */
@Serializable
enum class EntitlementSource { PLAY, STRIPE, EXTERNAL }

@Serializable
data class StoredEntitlement(
    val packageId: String,
    /** The base64 token, exactly as the registry issued it: also the download Bearer token. */
    val token: String,
    val source: EntitlementSource,
    /** Play purchase token, for re-exchange on restore. */
    val purchaseToken: String? = null,
    val productId: String? = null,
)

@Serializable
private data class StoreFile(
    val entitlements: List<StoredEntitlement> = emptyList(),
    /** The repository's buyer-session cookie (`Cookie:` value), so Stripe purchases restore. */
    val buyerCookie: String? = null,
    /** Stripe Checkout sessions opened but not yet confirmed: sessionId → packageId. */
    val pendingCheckouts: Map<String, String> = emptyMap(),
    /** Play purchases Google still holds as pending: purchaseToken → packageId. */
    val pendingPlay: Map<String, String> = emptyMap(),
)

/** The entitlement file exists but cannot be read; nothing is written over it. */
class EntitlementStoreUnreadable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The entitlements this device holds, in one small JSON file next to the extensions. Only verified
 * tokens are ever put here ([Licences] verifies first). The file is excluded from cloud backup and
 * device transfer (it carries the buyer-session cookie, a bearer credential).
 *
 * A missing file is an empty store; an unreadable one (truncated, corrupt, or from a newer format)
 * is NOT: reads fall back to the last good `.bak`, the bad file is copied aside to `.corrupt`, and
 * if neither is readable every write throws [EntitlementStoreUnreadable] instead of replacing a file
 * that may still hold paid licences and the cookie Stripe restore depends on.
 */
class EntitlementStore(private val file: File) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val backup get() = File(file.parentFile, file.name + ".bak")

    private fun parse(f: File): StoreFile = json.decodeFromString(StoreFile.serializer(), f.readText())

    private fun read(): StoreFile {
        if (!file.exists()) return if (backup.exists()) readBackup(null) else StoreFile()
        return try {
            parse(file)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            val aside = File(file.parentFile, file.name + ".corrupt")
            if (!aside.exists()) runCatching { file.copyTo(aside) }
            readBackup(e)
        }
    }

    private fun readBackup(cause: Exception?): StoreFile = try {
        parse(backup)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        throw EntitlementStoreUnreadable("${file.name} is unreadable; not overwriting it", cause ?: e)
    }

    private fun update(change: (StoreFile) -> StoreFile) = synchronized(lock) {
        val next = change(read())
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(StoreFile.serializer(), next).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // Keep the last good file, so a torn rename or a bad write still leaves one readable copy.
        if (file.exists()) runCatching { parse(file) }.onSuccess { file.copyTo(backup, overwrite = true) }
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse rename-over; the .bak above is the fallback if this is torn.
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
        }
    }

    fun all(): List<StoredEntitlement> = synchronized(lock) { read().entitlements }

    fun get(packageId: String): StoredEntitlement? = all().firstOrNull { it.packageId == packageId }

    fun put(entitlement: StoredEntitlement) = update { f ->
        f.copy(entitlements = f.entitlements.filterNot { it.packageId == entitlement.packageId } + entitlement)
    }

    fun remove(packageId: String) = update { f ->
        f.copy(entitlements = f.entitlements.filterNot { it.packageId == packageId })
    }

    var buyerCookie: String?
        get() = synchronized(lock) { read().buyerCookie }
        set(value) = update { it.copy(buyerCookie = value) }

    /** Purchases started but not yet confirmed, so a restart finishes them. */
    val pending = Pending()

    inner class Pending {
        fun checkouts(): Map<String, String> = synchronized(lock) { read().pendingCheckouts }
        fun addCheckout(sessionId: String, packageId: String) =
            update { it.copy(pendingCheckouts = it.pendingCheckouts + (sessionId to packageId)) }
        fun removeCheckout(sessionId: String) = update { it.copy(pendingCheckouts = it.pendingCheckouts - sessionId) }

        fun play(): Map<String, String> = synchronized(lock) { read().pendingPlay }
        fun addPlay(purchaseToken: String, packageId: String) =
            update { it.copy(pendingPlay = it.pendingPlay + (purchaseToken to packageId)) }
        fun removePlay(purchaseToken: String) = update { it.copy(pendingPlay = it.pendingPlay - purchaseToken) }
    }

    companion object {
        const val FILE_NAME = "azphalt-entitlements.json"
    }
}
