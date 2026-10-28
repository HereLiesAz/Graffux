package com.hereliesaz.graffitixr.data.azphalt.purchase

import kotlinx.coroutines.CancellationException

/** A licence to keep: the token, where it came from, and (Play) what to re-exchange on restore. */
data class Grant(
    val packageId: String,
    val token: String,
    val source: EntitlementSource,
    val purchaseToken: String? = null,
    val productId: String? = null,
)

/**
 * The licences this device holds. Every one is verified on the device before it is kept, whatever
 * route produced it, and removed (with its package) once it is revoked, lapses or stops verifying.
 */
internal class Licences(
    private val verifier: EntitlementVerifier,
    private val store: EntitlementStore,
    private val hooks: PurchaseHooks,
    private val tuning: PurchaseTuning,
    private val report: (String, PurchaseState) -> PurchaseState,
) {
    fun entitlementFor(packageId: String): String? {
        val stored = store.get(packageId) ?: return null
        return (verifier.verify(stored.token, packageId, tuning.now()) as? EntitlementCheck.Valid)?.let { stored.token }
    }

    /**
     * An entitlement obtained outside this app (azphalt.store, a developer's site selling through
     * azphalt, a store app's handoff). Verified exactly like an in-app one and kept for downloads
     * and restores; nothing about its origin makes it weaker.
     */
    fun acceptExternal(packageId: String, token: String): EntitlementCheck {
        val check = verifier.verify(token, packageId, tuning.now())
        if (check is EntitlementCheck.Valid) store.put(StoredEntitlement(packageId, token, EntitlementSource.EXTERNAL))
        return check
    }

    fun isValid(packageId: String, token: String): Boolean =
        verifier.verify(token, packageId, tuning.now()) is EntitlementCheck.Valid

    suspend fun accept(grant: Grant, installNow: Boolean): PurchaseState {
        val id = grant.packageId
        return when (val check = verifier.verify(grant.token, id, tuning.now())) {
            is EntitlementCheck.Valid -> {
                store.put(StoredEntitlement(id, grant.token, grant.source, grant.purchaseToken, grant.productId))
                if (installNow) install(id, grant.token) else report(id, PurchaseState.Idle)
            }
            is EntitlementCheck.Expired -> report(id, PurchaseState.Failed("That licence has expired."))
            is EntitlementCheck.Invalid ->
                report(id, PurchaseState.Failed("The store's licence couldn't be verified (${check.reason})."))
        }
    }

    private suspend fun install(packageId: String, token: String): PurchaseState {
        report(packageId, PurchaseState.Working("Installing"))
        @Suppress("TooGenericExceptionCaught") // any download/verify failure is reported, the licence kept
        return try {
            tuning.io { hooks.install(packageId, token) }
            report(packageId, PurchaseState.Installed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bought and verified; only the download failed. The licence stays, so Install retries.
            val message = "Purchased, but the download failed: ${e.message}. Tap Install to retry."
            report(packageId, PurchaseState.Failed(message))
        }
    }

    suspend fun remove(packageId: String, reason: String) {
        store.remove(packageId)
        if (hooks.isInstalled(packageId)) tuning.io { hooks.revoke(packageId, reason) }
        report(packageId, PurchaseState.Idle)
    }

    /** Remove every stored licence that lapsed or whose signature fails. Returns how many. */
    suspend fun revalidate(): Int {
        var removed = 0
        for (e in store.all()) {
            val reason = when (val check = verifier.verify(e.token, e.packageId, tuning.now())) {
                is EntitlementCheck.Valid -> null
                is EntitlementCheck.Expired -> "licence expired"
                // A changed trust store is not proof the token is bad; only a signature that fails is.
                is EntitlementCheck.Invalid ->
                    if (check.reason.contains("signature")) "licence no longer verifies" else null
            } ?: continue
            remove(e.packageId, reason)
            removed++
        }
        return removed
    }
}
