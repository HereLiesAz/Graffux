package com.hereliesaz.graffitixr.data.azphalt.purchase

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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

/**
 * The entitlements this device holds, in one small JSON file next to the extensions. Only verified
 * tokens are ever put here ([Licences] verifies first). Not a secret: a token is useless without the
 * purchase it records, and the repository checks it again on every download.
 */
class EntitlementStore(private val file: File) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun read(): StoreFile = runCatching {
        if (!file.exists()) StoreFile() else json.decodeFromString(StoreFile.serializer(), file.readText())
    }.getOrDefault(StoreFile())

    private fun update(change: (StoreFile) -> StoreFile) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(StoreFile.serializer(), change(read())))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
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
