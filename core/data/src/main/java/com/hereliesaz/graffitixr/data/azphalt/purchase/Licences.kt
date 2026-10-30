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

/** What [Licences.accept] did: the state to show, and whether the licence is now stored. */
internal data class Accepted(val state: PurchaseState, val kept: Boolean)

private const val VERIFY_LATER =
    "Paid. The licence will be verified and installed as soon as the store's keys load; " +
    "Restore purchases retries it."
private const val SAVE_FAILED =
    "Paid, but the licence couldn't be saved on this device. Restore purchases retries it."
private const val RENEWAL_NEEDED = "This subscription needs renewing. It stays installed meanwhile."

/**
 * The licences this device holds. Every one is verified on the device before it is kept, whatever
 * route produced it. One is removed (with its package) only on proof: the repository says it was
 * refunded or voided, or its signature fails. A lapsed subscription is kept and marked
 * [PurchaseState.RenewalNeeded], because "lapsed" here is only the device clock's opinion; a
 * one-time purchase never lapses at all.
 */
internal class Licences(
    private val verifier: EntitlementVerifier,
    private val store: EntitlementStore,
    private val hooks: PurchaseHooks,
    private val tuning: PurchaseTuning,
    private val report: (String, PurchaseState) -> PurchaseState,
) {
    fun entitlementFor(packageId: String): String? {
        val stored = runCatching { store.get(packageId) }.getOrNull() ?: return null
        return (verifier.verify(stored.token, packageId, tuning.now()) as? EntitlementCheck.Valid)?.let { stored.token }
    }

    /**
     * An entitlement obtained outside this app (azphalt.store, a developer's site selling through
     * azphalt, a store app's handoff). Verified exactly like an in-app one and kept for downloads
     * and restores; nothing about its origin makes it weaker.
     */
    fun acceptExternal(packageId: String, token: String): EntitlementCheck {
        val check = verifier.verify(token, packageId, tuning.now())
        if (check !is EntitlementCheck.Valid) return check
        return try {
            store.put(StoredEntitlement(packageId, token, EntitlementSource.EXTERNAL))
            check
        } catch (e: java.io.IOException) {
            EntitlementCheck.Invalid("couldn't save the licence: ${e.message}")
        }
    }

    fun isValid(packageId: String, token: String): Boolean =
        verifier.verify(token, packageId, tuning.now()) is EntitlementCheck.Valid

    /** Verify, refreshing the trust store once when it is empty or does not know the signing key. */
    private suspend fun verifyFresh(token: String, packageId: String): EntitlementCheck {
        val first = verifier.verify(token, packageId, tuning.now())
        if (first !is EntitlementCheck.Invalid || !first.isTrustGap()) return first
        runCatching { tuning.io { hooks.refreshTrust() } }
        return verifier.verify(token, packageId, tuning.now())
    }

    private fun EntitlementCheck.Invalid.isTrustGap() =
        reason == EntitlementVerifier.NO_TRUSTED_KEYS || reason.contains("not signed by a trusted")

    suspend fun accept(grant: Grant, installNow: Boolean): Accepted {
        val id = grant.packageId
        return when (val check = verifyFresh(grant.token, id)) {
            is EntitlementCheck.Valid -> {
                try {
                    store.put(StoredEntitlement(id, grant.token, grant.source, grant.purchaseToken, grant.productId))
                } catch (@Suppress("SwallowedException") e: java.io.IOException) {
                    // Reported as SAVE_FAILED; the pending record stays, so restore retries.
                    return Accepted(report(id, PurchaseState.Failed(SAVE_FAILED)), kept = false)
                }
                Accepted(if (installNow) install(id, grant.token) else report(id, PurchaseState.Idle), kept = true)
            }
            is EntitlementCheck.Expired ->
                Accepted(report(id, PurchaseState.Failed("That licence has expired.")), kept = false)
            is EntitlementCheck.Invalid ->
                if (check.reason == EntitlementVerifier.NO_TRUSTED_KEYS) {
                    // Not a verdict on the purchase: keep the pending record and say so.
                    Accepted(report(id, PurchaseState.Pending(VERIFY_LATER)), kept = false)
                } else {
                    val why = "The store's licence couldn't be verified (${check.reason})."
                    Accepted(report(id, PurchaseState.Failed(why)), kept = false)
                }
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

    /** Remove the licence, then the package. If the store can't be written, nothing is uninstalled. */
    suspend fun remove(packageId: String, reason: String) {
        store.remove(packageId)
        if (hooks.isInstalled(packageId)) tuning.io { hooks.revoke(packageId, reason) }
        report(packageId, PurchaseState.Idle)
    }

    /**
     * Re-check every stored licence. Only a signature that fails removes one; a lapsed subscription is
     * kept and marked [PurchaseState.RenewalNeeded]. [locked] runs each package's change under that
     * package's lock. Returns how many were removed.
     */
    suspend fun revalidate(locked: suspend (String, suspend () -> Unit) -> Unit): Int {
        var removed = 0
        for (e in store.all()) {
            when (val check = verifier.verify(e.token, e.packageId, tuning.now())) {
                is EntitlementCheck.Valid -> Unit
                is EntitlementCheck.Expired -> report(e.packageId, PurchaseState.RenewalNeeded(RENEWAL_NEEDED))
                // A changed trust store is not proof the token is bad; only a signature that fails is.
                is EntitlementCheck.Invalid -> if (check.reason.contains(EntitlementVerifier.SIGNATURE_FAILS)) {
                    locked(e.packageId) { remove(e.packageId, "licence no longer verifies") }
                    removed++
                }
            }
        }
        return removed
    }
}
