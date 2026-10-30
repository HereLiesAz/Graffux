package com.hereliesaz.graffitixr.data.azphalt.purchase

import android.app.Activity
import kotlinx.coroutines.CancellationException
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
import java.time.Duration
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

/** The price a paid card's Buy button shows, from whoever will actually charge it. */
sealed interface BuyPrice {
    /** A Play route: Google's localised price, the amount the Play sheet will charge. */
    data class Play(val formatted: String) : BuyPrice
    /** A Stripe route (or a non-Play build): the repository's listed price. */
    data object Repository : BuyPrice
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
    /**
     * How long an opened Stripe Checkout session is polled before it is dropped as abandoned: Stripe
     * expires an unpaid session after 24 hours. A payment that settles later (a bank debit) still
     * arrives, through the buyer's purchases on the next restore.
     */
    val checkoutLifetime: Duration = Duration.ofHours(CHECKOUT_LIFETIME_HOURS),
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
private const val UNEXPECTED = "Something went wrong talking to the store. Nothing was charged here; try again."
private const val CODE_UNREADABLE = "The store's reply didn't name the extension. Try again later."
private const val CODE_NOT_PAID = "That purchase hasn't been paid yet. It installs once the payment clears."
private const val CODE_UNREACHABLE = "Couldn't check that purchase code right now. Try again when you're online."

/** Stripe expires an unpaid Checkout Session after this many hours. */
private const val CHECKOUT_LIFETIME_HOURS = 24L

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
    private val hooks: PurchaseHooks,
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

    private val _prices = MutableStateFlow<Map<String, BuyPrice>>(emptyMap())

    /**
     * Per package, whose price to show. No entry means not known yet (or Play couldn't say on a Play
     * route): show no number, rather than one the buyer may not be charged.
     */
    val prices: StateFlow<Map<String, BuyPrice>> = _prices.asStateFlow()

    /**
     * Work out [packageIds]' [prices]. A non-Play build always buys through Stripe at the repository's
     * price. A Play build asks the repository for each package's route, and for a `play` or `choice`
     * route asks Play for its own price: Google applies regional pricing and tax, so the repository's
     * number can differ from what the Play sheet charges.
     */
    suspend fun loadPrices(packageIds: List<String>) {
        val missing = packageIds.filter { it !in _prices.value }
        if (missing.isEmpty()) return
        if (!gateway.isPlayDistribution()) {
            _prices.update { m -> m + missing.associateWith { BuyPrice.Repository } }
            return
        }
        val country = gateway.countryCode()
        for (id in missing) {
            val price = playRoutePrice(id, country) ?: continue
            _prices.update { it + (id to price) }
        }
    }

    private suspend fun playRoutePrice(packageId: String, country: String?): BuyPrice? {
        val route = guardedCall { tuning.io { api.route(appId, "play", country, packageId) } }
        return when (route?.route) {
            // Under the US programme a Play build may still fall back to Play Billing: no number.
            "stripe" -> if (route.clientApi == "alternative_billing_only") null else BuyPrice.Repository
            "play", "choice" -> route.productId?.let { guardedCall { gateway.formattedPrice(it) } }?.let(BuyPrice::Play)
            else -> null
        }
    }

    private fun set(packageId: String, state: PurchaseState): PurchaseState {
        _states.update { it + (packageId to state) }
        return state
    }

    /** The entitlement to present when downloading [packageId], if this device holds a valid one. */
    fun entitlementFor(packageId: String): String? = licences.entitlementFor(packageId)

    /** Whether this device holds the repository's buyer cookie, so card purchases restore on their own. */
    fun hasCardPurchaseSession(): Boolean = runCatching { store.buyerCookie != null }.getOrDefault(false)

    /** A licence obtained outside this app; see [Licences.acceptExternal]. */
    fun acceptExternal(packageId: String, token: String): EntitlementCheck = licences.acceptExternal(packageId, token)

    /** Buy [packageId]. A second tap while that package's purchase is under way returns its current state. */
    suspend fun buy(activity: Activity, packageId: String): PurchaseState =
        locks.ifFree(packageId) { guarded(packageId) { buyLocked(activity, packageId) } }
            ?: _states.value[packageId] ?: PurchaseState.Working("Checking how to pay")

    /**
     * Any failure of [block] other than cancellation ends as [PurchaseState.Failed] rather than an
     * uncaught crash, and a [PurchaseState.Working] left behind by an abnormal exit (cancellation)
     * is reset to Idle, so a card never stays on "Working".
     */
    @Suppress("TooGenericExceptionCaught") // the point: nothing escapes the purchase flow
    private suspend fun guarded(packageId: String, block: suspend () -> PurchaseState): PurchaseState {
        var finished = false
        try {
            return unreadableAsFailure(packageId) { block() }.also { finished = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finished = true
            return set(packageId, PurchaseState.Failed(UNEXPECTED + (e.message?.let { " ($it)" } ?: "")))
        } finally {
            if (!finished) _states.update { m -> if (m[packageId] is PurchaseState.Working) m - packageId else m }
        }
    }

    private suspend fun unreadableAsFailure(
        packageId: String,
        block: suspend () -> PurchaseState,
    ): PurchaseState = try {
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
        store.pending.addCheckout(start.sessionId, packageId, tuning.now().toEpochMilli())
        return set(packageId, PurchaseState.AwaitingCheckout(start.sessionId, start.url))
    }

    /** After the Custom Tab closes: poll the session a few times for its entitlement. */
    suspend fun resumeCheckout(sessionId: String, attempts: Int = 5): PurchaseState =
        resumeCheckout(sessionId, attempts, quiet = false)

    /**
     * [quiet] (the startup restore): a session that is still unpaid leaves the card as it was, rather
     * than telling the buyer "Not paid yet" on every launch for a checkout they closed.
     */
    private suspend fun resumeCheckout(sessionId: String, attempts: Int, quiet: Boolean): PurchaseState {
        val packageId = runCatching { store.pending.checkouts()[sessionId] }.getOrNull() ?: return PurchaseState.Idle
        return locks.locked(packageId) {
            guarded(packageId) { resumeLocked(sessionId, packageId, attempts, quiet) }
        }
    }

    private suspend fun resumeLocked(
        sessionId: String,
        packageId: String,
        attempts: Int,
        quiet: Boolean,
    ): PurchaseState {
        // Another resume finished this session while we waited for the lock.
        if (sessionId !in store.pending.checkouts()) return _states.value[packageId] ?: PurchaseState.Idle
        val before = _states.value[packageId]
        val expired = isExpired(sessionId)
        set(packageId, PurchaseState.Working("Confirming your payment"))
        return when (val s = poll(sessionId, attempts)) {
            is CheckoutStatus.Ready -> {
                s.buyerCookie?.let { store.buyerCookie = it }
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
            else -> unpaid(sessionId, packageId, expired, if (quiet) before ?: PurchaseState.Idle else null)
        }
    }

    /** Up to [attempts] checks of [sessionId], stopping at the first final answer. */
    private suspend fun poll(sessionId: String, attempts: Int): CheckoutStatus {
        var status: CheckoutStatus = CheckoutStatus.Pending
        for (i in 0 until attempts) {
            if (i > 0) delay(tuning.pollDelayMs)
            status = tuning.io { api.checkoutStatus(sessionId, store.buyerCookie) }
            if (status is CheckoutStatus.Ready || status is CheckoutStatus.Revoked) break
        }
        return status
    }

    /** Whether [sessionId] was opened longer ago than Stripe keeps an unpaid session. */
    private fun isExpired(sessionId: String): Boolean {
        val now = tuning.now()
        val opened = Instant.ofEpochMilli(store.pending.checkoutSeenAt(sessionId, now.toEpochMilli()))
        return !now.isBefore(opened.plus(tuning.checkoutLifetime))
    }

    /**
     * A session the store still calls unpaid. [restoreTo] (a quiet restore) puts the card back as it
     * was; otherwise the buyer is told it is not paid yet. An expired one is dropped either way.
     */
    private fun unpaid(
        sessionId: String,
        packageId: String,
        expired: Boolean,
        restoreTo: PurchaseState?,
    ): PurchaseState =
        when {
            // Abandoned: Stripe has expired it. Dropped, and the card goes back to Buy.
            expired -> {
                store.pending.removeCheckout(sessionId)
                set(packageId, PurchaseState.Idle)
            }
            restoreTo != null -> set(packageId, restoreTo)
            else -> set(packageId, PurchaseState.Pending(NOT_PAID_YET))
        }

    /**
     * Restore a Stripe purchase on a device that has no buyer cookie (a reinstall, a new phone: the
     * cookie is deliberately never backed up). [code] is the purchase's Checkout Session id (`cs_…`)
     * or the `…/checkout/success?session_id=cs_…` link it was shown. The repository answers with the
     * licence and sets the buyer cookie again, so the next [restore] brings back every other purchase
     * that buyer made too. Null when [code] holds no session id.
     */
    suspend fun restoreFromCode(code: String): PurchaseState? {
        val sessionId = checkoutSessionId(code) ?: return null
        val status = guardedCall { tuning.io { api.checkoutStatus(sessionId, store.buyerCookie) } }
        return when (status) {
            is CheckoutStatus.Ready -> status.packageId?.let { acceptRestored(it, status) }
                ?: PurchaseState.Failed(CODE_UNREADABLE)
            CheckoutStatus.Revoked -> PurchaseState.Failed("That purchase was refunded or disputed.")
            CheckoutStatus.Pending -> PurchaseState.Pending(CODE_NOT_PAID)
            is CheckoutStatus.Unavailable, null -> PurchaseState.Failed(CODE_UNREACHABLE)
        }
    }

    private suspend fun acceptRestored(packageId: String, status: CheckoutStatus.Ready): PurchaseState =
        locks.locked(packageId) {
            guarded(packageId) {
                status.buyerCookie?.let { store.buyerCookie = it }
                val grant = Grant(packageId, status.entitlement, EntitlementSource.STRIPE)
                licences.accept(grant, installNow = !hooks.isInstalled(packageId)).state
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guardedCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("SwallowedException") e: Exception) {
        null
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
        for ((sessionId, _) in store.pending.checkouts()) resumeCheckout(sessionId, attempts = 1, quiet = true)
        restorer.restore(paidCatalogue, installMissing)
    }

    companion object {
        /** The Play product id a package is sold as (same rule as the repository's `playSkuFor`). */
        fun playSkuFor(packageId: String): String = packageId.lowercase().replace(Regex("[^a-z0-9_.]"), "_")

        private val SESSION_ID = Regex("""\bcs_(?:test_|live_)?[A-Za-z0-9]{8,200}""")

        /** The Checkout Session id in a pasted code or success link, or null. */
        fun checkoutSessionId(code: String): String? = SESSION_ID.find(code.trim())?.value
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
