package com.hereliesaz.graffitixr.data.azphalt.purchase

/**
 * Restore on startup or reinstall: re-exchanges every purchase Play holds (idempotent server-side),
 * finishes pending ones, pulls Stripe purchases for this device's buyer session, and re-checks every
 * stored licence. A licence that was refunded, lapsed or no longer verifies is removed with its
 * package. Network failures never remove anything.
 */
internal class PurchaseRestorer(
    private val appId: String,
    private val backend: PurchaseBackend,
    private val hooks: PurchaseHooks,
    private val tuning: PurchaseTuning,
    private val licences: Licences,
) {
    private val store get() = backend.store

    suspend fun restore(paidCatalogue: suspend () -> List<String>, installMissing: Boolean): RestoreReport {
        val play = if (backend.gateway.isPlayDistribution()) {
            restorePlay(paidCatalogue, installMissing)
        } else {
            RestoreReport(0, 0)
        }
        val stripe = restoreStripe(installMissing)
        val lapsed = licences.revalidate()
        return RestoreReport(play.restored + stripe, play.removed + lapsed)
    }

    private suspend fun restorePlay(paidCatalogue: suspend () -> List<String>, installMissing: Boolean): RestoreReport {
        val owned = runCatching { backend.gateway.ownedPurchases() }.getOrNull() ?: return RestoreReport(0, 0)
        val packageOf = PackageResolver(store, paidCatalogue, tuning)
        var restored = 0
        var removed = 0
        for (purchase in owned) {
            for (productId in purchase.productIds) {
                val packageId = packageOf(productId, purchase.purchaseToken) ?: continue
                when (restoreOne(packageId, productId, purchase, installMissing)) {
                    Outcome.RESTORED -> restored++
                    Outcome.REMOVED -> removed++
                    Outcome.NONE -> Unit
                }
            }
        }
        return RestoreReport(restored, removed)
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
                store.pending.removePlay(purchase.purchaseToken)
                val had = store.get(packageId) != null
                val grant = Grant(packageId, r.entitlement, EntitlementSource.PLAY, purchase.purchaseToken, productId)
                licences.accept(grant, installNow = installMissing && !hooks.isInstalled(packageId))
                if (had) Outcome.NONE else Outcome.RESTORED
            }
            PlayExchange.Revoked -> if (store.get(packageId)?.purchaseToken == purchase.purchaseToken) {
                licences.remove(packageId, "refunded or cancelled")
                Outcome.REMOVED
            } else {
                Outcome.NONE
            }
            else -> Outcome.NONE
        }
    }

    private suspend fun restoreStripe(installMissing: Boolean): Int {
        val purchases = store.buyerCookie
            ?.let { cookie -> runCatching { tuning.io { backend.api.purchases(cookie) } }.getOrNull() }
            ?: return 0
        var restored = 0
        for (p in purchases) {
            if (!licences.isValid(p.packageId, p.token)) continue
            val had = store.get(p.packageId) != null
            val installNow = installMissing && !hooks.isInstalled(p.packageId)
            licences.accept(Grant(p.packageId, p.token, EntitlementSource.STRIPE), installNow = installNow)
            if (!had) restored++
        }
        return restored
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
