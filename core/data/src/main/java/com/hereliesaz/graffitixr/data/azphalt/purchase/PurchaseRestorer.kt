package com.hereliesaz.graffitixr.data.azphalt.purchase

/**
 * Restore on startup or reinstall: re-exchanges every purchase Play holds (idempotent server-side),
 * finishes pending ones, pulls Stripe purchases for this device's buyer session, and re-checks every
 * stored licence.
 *
 * A licence is removed with its package only on proof: the repository answers `revoked` for it (a
 * refund, a chargeback, a void), or its signature fails. Play drops a refunded purchase from its
 * owned list, so a stored Play licence missing from that list is re-asked by its purchase token; a
 * Stripe one is removed when the buyer's purchase list flags it `revoked`. Absence alone, a network
 * failure, an app the repository does not accept, or the device clock never removes anything.
 */
internal class PurchaseRestorer(
    private val appId: String,
    private val backend: PurchaseBackend,
    private val hooks: PurchaseHooks,
    private val tuning: PurchaseTuning,
    private val licences: Licences,
    private val locks: PackageLocks,
) {
    private val store get() = backend.store

    suspend fun restore(paidCatalogue: suspend () -> List<String>, installMissing: Boolean): RestoreReport {
        val play = if (backend.gateway.isPlayDistribution()) {
            restorePlay(paidCatalogue, installMissing)
        } else {
            RestoreReport(0, 0)
        }
        val stripe = restoreStripe(installMissing)
        val lapsed = licences.revalidate { id, block -> locks.locked(id) { block() } }
        return RestoreReport(play.restored + stripe.restored, play.removed + stripe.removed + lapsed)
    }

    private suspend fun restorePlay(paidCatalogue: suspend () -> List<String>, installMissing: Boolean): RestoreReport {
        // A failed query is not "owns nothing": it throws, and nothing is compared against it.
        val owned = runCatching { backend.gateway.ownedPurchases() }.getOrNull() ?: return RestoreReport(0, 0)
        val packageOf = PackageResolver(store, paidCatalogue, tuning)
        var restored = 0
        var removed = 0
        for (purchase in owned) {
            for (productId in purchase.productIds) {
                val packageId = packageOf(productId, purchase.purchaseToken) ?: continue
                when (locks.locked(packageId) { restoreOne(packageId, productId, purchase, installMissing) }) {
                    Outcome.RESTORED -> restored++
                    Outcome.REMOVED -> removed++
                    Outcome.NONE -> Unit
                }
            }
        }
        removed += revokeMissingPlay(owned.map { it.purchaseToken }.toSet())
        return RestoreReport(restored, removed)
    }

    /** Stored Play licences Play no longer lists: ask the repository; remove only on `revoked`. */
    private suspend fun revokeMissingPlay(ownedTokens: Set<String>): Int {
        var removed = 0
        val missing = store.all().filter {
            it.source == EntitlementSource.PLAY && it.productId != null &&
                it.purchaseToken != null && it.purchaseToken !in ownedTokens
        }
        for (e in missing) {
            val token = e.purchaseToken!!
            val productId = e.productId!!
            val answer = tuning.io { backend.api.exchangePlay(appId, e.packageId, productId, token) }
            if (answer == PlayExchange.Revoked) {
                locks.locked(e.packageId) {
                    if (store.get(e.packageId)?.purchaseToken == token) {
                        licences.remove(e.packageId, "refunded or cancelled")
                        removed++
                    }
                }
            }
        }
        return removed
    }

    private enum class Outcome { RESTORED, REMOVED, NONE }

    private suspend fun restoreOne(
        packageId: String,
        productId: String,
        purchase: PlayPurchase,
        installMissing: Boolean,
    ): Outcome {
        if (purchase.pending) {
            store.pending.addPlay(purchase.purchaseToken, packageId)
            return Outcome.NONE
        }
        val exchange = tuning.io { backend.api.exchangePlay(appId, packageId, productId, purchase.purchaseToken) }
        return when (val r = exchange) {
            is PlayExchange.Ready -> {
                val had = store.get(packageId) != null
                val grant = Grant(packageId, r.entitlement, EntitlementSource.PLAY, purchase.purchaseToken, productId)
                val accepted = licences.accept(grant, installNow = installMissing && !hooks.isInstalled(packageId))
                // The pending record goes only once the licence is safely stored.
                if (accepted.kept) store.pending.removePlay(purchase.purchaseToken)
                if (had || !accepted.kept) Outcome.NONE else Outcome.RESTORED
            }
            PlayExchange.Revoked -> {
                store.pending.removePlay(purchase.purchaseToken)
                if (store.get(packageId)?.purchaseToken == purchase.purchaseToken) {
                    licences.remove(packageId, "refunded or cancelled")
                    Outcome.REMOVED
                } else {
                    Outcome.NONE
                }
            }
            else -> Outcome.NONE
        }
    }

    private suspend fun restoreStripe(installMissing: Boolean): RestoreReport {
        val purchases = store.buyerCookie
            ?.let { cookie -> runCatching { tuning.io { backend.api.purchases(cookie) } }.getOrNull() }
            ?: return RestoreReport(0, 0)
        var restored = 0
        var removed = 0
        for (p in purchases) {
            locks.locked(p.packageId) {
                if (p.revoked) {
                    // Exactly the token the repository revoked, whichever route stored it.
                    if (store.get(p.packageId)?.token == p.token) {
                        licences.remove(p.packageId, "refunded or disputed")
                        removed++
                    }
                    return@locked
                }
                if (!licences.isValid(p.packageId, p.token)) return@locked
                val had = store.get(p.packageId) != null
                val installNow = installMissing && !hooks.isInstalled(p.packageId)
                val grant = Grant(p.packageId, p.token, EntitlementSource.STRIPE)
                val accepted = licences.accept(grant, installNow = installNow)
                if (!had && accepted.kept) restored++
            }
        }
        return RestoreReport(restored, removed)
    }
}

/** Play product id → package id: the local record first, then the paid catalogue (fetched once). */
private class PackageResolver(
    private val store: EntitlementStore,
    private val paidCatalogue: suspend () -> List<String>,
    private val tuning: PurchaseTuning,
) {
    private val known = store.all().mapNotNull { e -> e.productId?.let { it to e.packageId } }.toMap()
    private var catalogue: Map<String, String>? = null

    suspend operator fun invoke(productId: String, purchaseToken: String): String? =
        known[productId] ?: store.pending.play()[purchaseToken] ?: fromCatalogue(productId)

    private suspend fun fromCatalogue(productId: String): String? {
        val map = catalogue ?: runCatching { tuning.io { paidCatalogue() } }.getOrDefault(emptyList())
            .associateBy(PurchaseCoordinator::playSkuFor)
            .also { catalogue = it }
        return map[productId]
    }
}
