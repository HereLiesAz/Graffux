package com.hereliesaz.graffitixr.data.azphalt.purchase

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** Where one package's purchase stands, for the UI. */
sealed interface PurchaseState {
    data object Idle : PurchaseState
    data class Working(val step: String) : PurchaseState
    /** Open [url] (Stripe Checkout) in a Custom Tab, then call [PurchaseCoordinator.resumeCheckout]. */
    data class AwaitingCheckout(val sessionId: String, val url: String) : PurchaseState
    /**
     * Paid with a slow method, Google has not confirmed yet, or the licence is waiting on the store's
     * keys. Finished by the next restore: on the next launch, or when the buyer taps Restore purchases.
     */
    data class Pending(val message: String) : PurchaseState
    /** A subscription past its expiry by this device's clock. Kept installed; never removed for this alone. */
    data class RenewalNeeded(val message: String) : PurchaseState
    data object Installed : PurchaseState
    data class Failed(val message: String) : PurchaseState
}

/** Who the coordinator talks to. */
class PurchaseBackend(
    val gateway: BillingGateway,
    val api: PurchaseApi,
    val verifier: EntitlementVerifier,
    val store: EntitlementStore,
)

/** What the host does with a licence. */
class PurchaseHooks(
    /** Download and install a package presenting the entitlement as the Bearer token. */
    val install: suspend (packageId: String, entitlement: String) -> Unit,
    /** Remove a package whose entitlement was revoked or lapsed. */
    val revoke: suspend (packageId: String, reason: String) -> Unit,
    val isInstalled: (String) -> Boolean,
    /** Re-fetch the repository's signing keys (blocking; run off the main thread). */
    val refreshTrust: suspend () -> Unit = {},
)

/**
 * One lock per package, so a buy, a checkout resume and a restore never run the same package's
 * exchange-and-install twice at once (two installs into one package directory).
 */
internal class PackageLocks {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun of(packageId: String) = locks.getOrPut(packageId) { Mutex() }

    suspend fun <T> locked(packageId: String, block: suspend () -> T): T = of(packageId).withLock { block() }

    /** Runs [block] only if nothing else holds [packageId]; null when it is busy. */
    suspend fun <T> ifFree(packageId: String, block: suspend () -> T): T? {
        val m = of(packageId)
        if (!m.tryLock()) return null
        return try { block() } finally { m.unlock() }
    }
}

class PurchaseTuning(
    val now: () -> Instant = Instant::now,
    val pollDelayMs: Long = 2_000,
    /** Where blocking HTTP and disk work runs; Play's UI calls stay on the caller's (main) thread. */
    val ioContext: CoroutineContext = Dispatchers.IO,
)

internal suspend fun <T> PurchaseTuning.io(block: suspend () -> T): T = withContext(ioContext) { block() }

private const val NOT_IN_APP = "This extension can't be bought in this app. You can still buy it on azphalt.store."
private const val CONFIRM_LATER =
    "Paid. Waiting to confirm with the store; this finishes on the next launch or Restore purchases."
private const val NOT_SAVED = "This device's purchase records can't be read, so nothing was changed " +
    "and no licence was removed. Your purchase is safe with the store."
private const val NOT_PAID_YET =
    "Not paid yet. If you finished checkout, it installs as soon as the payment clears."
private const val PENDING_PLAY = "Your payment is pending. The extension installs when Google Play confirms it."

/**
 * In-app purchasing of azphalt packages: picks the payment route the repository's policy names for
 * this buyer, runs it, trades the result for a registry-signed entitlement, verifies that on the
 * device ([Licences]), and installs the package with it. It is one way to buy, not the only one:
 * entitlements from azphalt.store or a store-app handoff go through [acceptExternal] and are
 * honoured the same.
 *
 * Routes (`GET /billing/route`):
 * - `stripe`: Stripe Checkout in a Custom Tab. A non-Play build always lands here. Under the US
 *   programme a Play build first gets Google's external transaction token, and falls back to Play
 *   Billing when Google says the app is not enrolled.
 * - `choice`: Play's purchase flow with Google's choice screen; if the buyer picks the developer's
 *   billing, Stripe runs with the token Google handed over. Not enrolled: plain Play Billing.
 * - `play`: Play Billing.
 */
@Suppress("TooManyFunctions") // one public entry per purchase step, each with its locked body
class PurchaseCoordinator(
    private val appId: String,
    private val backend: PurchaseBackend,
    hooks: PurchaseHooks,
    private val tuning: PurchaseTuning = PurchaseTuning(),
) {
    private val gateway get() = backend.gateway
    private val api get() = backend.api
    private val store get() = backend.store

    private val _states = MutableStateFlow<Map<String, PurchaseState>>(emptyMap())
    val states: StateFlow<Map<String, PurchaseState>> = _states.asStateFlow()

    private val licences = Licences(backend.verifier, store, hooks, tuning) { id, state -> set(id, state) }
    private val locks = PackageLocks()
    private val restoring = Mutex()
    private val restorer = PurchaseRestorer(appId, backend, hooks, tuning, licences, locks)

    private fun set(packageId: String, state: PurchaseState): PurchaseState {
        _states.update { it + (packageId to state) }
        return state
    }

    /** The entitlement to present when downloading [packageId], if this device holds a valid one. */
    fun entitlementFor(packageId: String): String? = licences.entitlementFor(packageId)

    /** A licence obtained outside this app; see [Licences.acceptExternal]. */
    fun acceptExternal(packageId: String, token: String): EntitlementCheck = licences.acceptExternal(packageId, token)

    /** Buy [packageId]. A second tap while that package's purchase is under way returns its current state. */
    suspend fun buy(activity: Activity, packageId: String): PurchaseState =
        locks.ifFree(packageId) { unreadableAsFailure(packageId) { buyLocked(activity, packageId) } }
            ?: _states.value[packageId] ?: PurchaseState.Working("Checking how to pay")

    private inline fun unreadableAsFailure(packageId: String, block: () -> PurchaseState): PurchaseState = try {
        block()
    } catch (@Suppress("SwallowedException") e: EntitlementStoreUnreadable) {
        // Shown as NOT_SAVED; nothing was written or removed.
        set(packageId, PurchaseState.Failed(NOT_SAVED))
    }

    private suspend fun buyLocked(activity: Activity, packageId: String): PurchaseState {
        set(packageId, PurchaseState.Working("Checking how to pay"))
        val play = gateway.isPlayDistribution()
        val country = if (play) gateway.countryCode() else null
        val lookup = runCatching { tuning.io { api.route(appId, if (play) "play" else "direct", country, packageId) } }
            .onFailure { if (it !is IOException) throw it }
        val route = lookup.getOrNull()
        val productId = route?.productId
        if (route == null || productId == null) {
            val why = lookup.exceptionOrNull()?.let { "Couldn't reach the store: ${it.message}" }
                ?: "This extension isn't for sale right now."
            return set(packageId, PurchaseState.Failed(why))
        }
        val purchase = Purchase(activity, packageId, productId, country)
        return when (route.route) {
            "stripe" -> if (play) playBuildStripe(purchase, route) else startStripe(packageId, null, null)
            "choice" -> playFlow(purchase, gateway.choiceMode(route))
            "play" -> playFlow(purchase, BillingMode.PLAY_ONLY)
            else -> set(
                packageId,
                PurchaseState.Failed(NOT_IN_APP),
            )
        }
    }

    private class Purchase(val activity: Activity, val packageId: String, val productId: String, val country: String?)

    /** A Play build is only sent to Stripe under a programme that allows it; otherwise Play Billing. */
    private suspend fun playBuildStripe(p: Purchase, route: BillingRoute): PurchaseState {
        val programme = route.reportToGoogle && route.clientApi == "alternative_billing_only"
        val token = if (programme && gateway.isProgrammeAvailable("alternative_billing_only")) {
            gateway.alternativeBillingOnlyToken(p.activity)
        } else {
            null
        }
        return if (token != null) startStripe(p.packageId, p.country, token) else playFlow(p, BillingMode.PLAY_ONLY)
    }

    private suspend fun playFlow(p: Purchase, mode: BillingMode): PurchaseState {
        set(p.packageId, PurchaseState.Working("Waiting for Google Play"))
        return when (val r = gateway.purchase(p.activity, p.productId, mode)) {
            is PlayFlowResult.Purchased -> completePlay(p.packageId, p.productId, r.purchase.purchaseToken)
            is PlayFlowResult.Pending -> {
                store.pending.addPlay(r.purchase.purchaseToken, p.packageId)
                set(p.packageId, PurchaseState.Pending(PENDING_PLAY))
            }
            is PlayFlowResult.AlternativeChosen -> startStripe(p.packageId, p.country, r.externalTransactionToken)
            PlayFlowResult.Cancelled -> set(p.packageId, PurchaseState.Idle)
            is PlayFlowResult.Failed -> set(p.packageId, PurchaseState.Failed(r.message))
        }
    }

    private suspend fun completePlay(packageId: String, productId: String, purchaseToken: String): PurchaseState {
        set(packageId, PurchaseState.Working("Confirming your purchase"))
        return when (val r = tuning.io { api.exchangePlay(appId, packageId, productId, purchaseToken) }) {
            is PlayExchange.Ready -> {
                // Recorded first and cleared only once the licence is stored, so a licence that
                // can't be verified yet (keys not loaded) is retried by restore, not lost.
                store.pending.addPlay(purchaseToken, packageId)
                val grant = Grant(packageId, r.entitlement, EntitlementSource.PLAY, purchaseToken, productId)
                val accepted = licences.accept(grant, installNow = true)
                if (accepted.kept) store.pending.removePlay(purchaseToken)
                accepted.state
            }
            PlayExchange.Pending -> {
                store.pending.addPlay(purchaseToken, packageId)
                set(packageId, PurchaseState.Pending(PENDING_PLAY))
            }
            PlayExchange.Refused -> set(packageId, PurchaseState.Failed("Google Play didn't confirm this purchase."))
            PlayExchange.Revoked -> set(packageId, PurchaseState.Failed("This purchase was refunded or cancelled."))
            is PlayExchange.NotAccepted,
            is PlayExchange.Unavailable -> {
                // Paid, but not confirmed yet: keep it, and restore will finish it.
                store.pending.addPlay(purchaseToken, packageId)
                set(packageId, PurchaseState.Pending(CONFIRM_LATER))
            }
        }
    }

    private suspend fun startStripe(
        packageId: String,
        region: String?,
        externalTransactionToken: String?,
    ): PurchaseState {
        set(packageId, PurchaseState.Working("Opening checkout"))
        val start = try {
            tuning.io { api.startCheckout(packageId, appId, store.buyerCookie, region, externalTransactionToken) }
        } catch (e: IOException) {
            return set(packageId, PurchaseState.Failed("Checkout couldn't start: ${e.message}"))
        }
        start.buyerCookie?.let { store.buyerCookie = it }
        store.pending.addCheckout(start.sessionId, packageId)
        return set(packageId, PurchaseState.AwaitingCheckout(start.sessionId, start.url))
    }

    /** After the Custom Tab closes: poll the session a few times for its entitlement. */
    suspend fun resumeCheckout(sessionId: String, attempts: Int = 5): PurchaseState {
        val packageId = runCatching { store.pending.checkouts()[sessionId] }.getOrNull() ?: return PurchaseState.Idle
        return locks.locked(packageId) {
            unreadableAsFailure(packageId) { resumeLocked(sessionId, packageId, attempts) }
        }
    }

    private suspend fun resumeLocked(sessionId: String, packageId: String, attempts: Int): PurchaseState {
        // Another resume finished this session while we waited for the lock.
        if (sessionId !in store.pending.checkouts()) return _states.value[packageId] ?: PurchaseState.Idle
        set(packageId, PurchaseState.Working("Confirming your payment"))
        var status: CheckoutStatus = CheckoutStatus.Pending
        for (i in 0 until attempts) {
            if (i > 0) delay(tuning.pollDelayMs)
            status = tuning.io { api.checkoutStatus(sessionId, store.buyerCookie) }
            if (status is CheckoutStatus.Ready || status is CheckoutStatus.Revoked) break
        }
        return when (val s = status) {
            is CheckoutStatus.Ready -> {
                val grant = Grant(packageId, s.entitlement, EntitlementSource.STRIPE)
                val accepted = licences.accept(grant, installNow = true)
                // Only a stored licence clears the session; otherwise restore polls it again.
                if (accepted.kept) store.pending.removeCheckout(sessionId)
                accepted.state
            }
            CheckoutStatus.Revoked -> {
                store.pending.removeCheckout(sessionId)
                set(packageId, PurchaseState.Failed("This purchase was refunded or disputed."))
            }
            else -> set(packageId, PurchaseState.Pending(NOT_PAID_YET))
        }
    }

    /**
     * Restore on startup or reinstall; see [PurchaseRestorer]. [paidCatalogue] maps a Play product
     * back to its package on a fresh install. With [installMissing], restored packages are installed.
     */
    suspend fun restore(
        paidCatalogue: suspend () -> List<String> = { emptyList() },
        installMissing: Boolean = false,
    ): RestoreReport = restoring.withLock {
        // Throws EntitlementStoreUnreadable rather than comparing anything against an unreadable store.
        for ((sessionId, _) in store.pending.checkouts()) resumeCheckout(sessionId, attempts = 1)
        restorer.restore(paidCatalogue, installMissing)
    }

    companion object {
        /** The Play product id a package is sold as (same rule as the repository's `playSkuFor`). */
        fun playSkuFor(packageId: String): String = packageId.lowercase().replace(Regex("[^a-z0-9_.]"), "_")
    }
}

/** The programme API a `choice` route runs under, or plain Play Billing when Google says it is not live. */
private suspend fun BillingGateway.choiceMode(route: BillingRoute): BillingMode {
    val api = route.clientApi
    return when {
        api == null || !isProgrammeAvailable(api) -> BillingMode.PLAY_ONLY
        api == "user_choice" -> BillingMode.USER_CHOICE
        else -> BillingMode.BILLING_CHOICE
    }
}

data class RestoreReport(val restored: Int, val removed: Int)
