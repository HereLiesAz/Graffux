package com.hereliesaz.graffitixr.data.azphalt.purchase

import android.app.Activity
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class FakeBilling : BillingGateway {
    var play = true
    var country: String? = "BR"
    var available = mutableSetOf<String>()
    var altToken: String? = "alt-token"
    var next: PlayFlowResult =
        PlayFlowResult.Purchased(PlayPurchase(listOf("com.example.paid"), "ptok", pending = false))
    var owned = listOf<PlayPurchase>()
    val launches = mutableListOf<Pair<String, BillingMode>>()
    override fun isPlayDistribution() = play
    override suspend fun countryCode() = country
    override suspend fun isProgrammeAvailable(clientApi: String) = clientApi in available
    override suspend fun alternativeBillingOnlyToken(activity: Activity) = altToken
    override suspend fun purchase(activity: Activity, productId: String, mode: BillingMode): PlayFlowResult {
        launches += productId to mode
        return next
    }
    override suspend fun ownedPurchases() = owned
}

class FakeApi(private val registry: TestRegistry) : PurchaseApi {
    var route = BillingRoute(route = "play", productId = "com.example.paid")
    var exchange: (String) -> PlayExchange = { PlayExchange.Ready(registry.token("com.example.paid")) }
    var checkout: CheckoutStatus = CheckoutStatus.Ready(registry.token("com.example.paid"))
    var restored = listOf<RestoredPurchase>()
    val checkouts = mutableListOf<Pair<String?, String?>>()
    val routeQueries = mutableListOf<Triple<String, String, String?>>()
    var routeFails = false
    override fun route(appId: String, distribution: String, country: String?, packageId: String): BillingRoute {
        routeQueries += Triple(appId, distribution, country)
        if (routeFails) throw IOException("offline")
        return route
    }
    override fun exchangePlay(appId: String, packageId: String, productId: String, purchaseToken: String) =
        exchange(purchaseToken)
    override fun startCheckout(
        packageId: String,
        appId: String,
        buyerCookie: String?,
        region: String?,
        externalTransactionToken: String?,
    ): CheckoutStart {
        checkouts += region to externalTransactionToken
        return CheckoutStart("cs_1", "https://checkout.stripe.com/c/cs_1", "azphalt_buyer_session=abc")
    }
    override fun checkoutStatus(sessionId: String, buyerCookie: String?) = checkout
    override fun purchases(buyerCookie: String) = restored
}

private const val PAID = "com.example.paid"

class PurchaseCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val registry = TestRegistry()
    private val activity = mockk<Activity>(relaxed = true)
    private lateinit var billing: FakeBilling
    private lateinit var api: FakeApi
    private lateinit var store: EntitlementStore
    private val installed = mutableMapOf<String, String>()
    private val revoked = mutableListOf<String>()
    private lateinit var c: PurchaseCoordinator

    @Before fun setUp() {
        billing = FakeBilling()
        api = FakeApi(registry)
        store = EntitlementStore(tmp.newFile("e.json").also { it.delete() })
        c = PurchaseCoordinator(
            appId = "com.hereliesaz.graffux",
            backend = PurchaseBackend(billing, api, EntitlementVerifier { setOf(registry.publicKey) }, store),
            hooks = PurchaseHooks(
                install = { id, token -> installed[id] = token },
                revoke = { id, _ -> revoked += id; installed.remove(id) },
                isInstalled = { it in installed },
            ),
            tuning = PurchaseTuning(now = { Instant.parse("2026-10-28T00:00:00Z") }, pollDelayMs = 0),
        )
    }

    @Test fun `play route buys with Play Billing, verifies, stores and installs with the entitlement`() = runTest {
        assertEquals(PurchaseState.Installed, c.buy(activity, "com.example.paid"))
        assertEquals(listOf("com.example.paid" to BillingMode.PLAY_ONLY), billing.launches)
        assertEquals(store.get("com.example.paid")!!.token, installed["com.example.paid"])
        assertEquals(EntitlementSource.PLAY, store.get("com.example.paid")!!.source)
        assertEquals("ptok", store.get("com.example.paid")!!.purchaseToken)
        assertEquals(Triple("com.hereliesaz.graffux", "play", "BR"), api.routeQueries.single())
    }

    @Test fun `non-Play builds always use Stripe and never touch Play`() = runTest {
        billing.play = false
        api.route = BillingRoute(route = "stripe", productId = "com.example.paid")
        val s = c.buy(activity, "com.example.paid")
        assertTrue(s is PurchaseState.AwaitingCheckout)
        assertEquals("direct", api.routeQueries.single().second)
        assertTrue(billing.launches.isEmpty())
        assertEquals(null to null, api.checkouts.single())
        assertEquals("azphalt_buyer_session=abc", store.buyerCookie)
        assertEquals(PurchaseState.Installed, c.resumeCheckout("cs_1"))
        assertEquals(EntitlementSource.STRIPE, store.get("com.example.paid")!!.source)
        assertTrue(store.pending.checkouts().isEmpty())
    }

    @Test fun `US programme passes Google's token to Stripe, and falls back to Play when not enrolled`() = runTest {
        billing.country = "US"
        api.route = BillingRoute(
            "stripe", clientApi = "alternative_billing_only", reportToGoogle = true, productId = PAID,
        )
        billing.available += "alternative_billing_only"
        assertTrue(c.buy(activity, "com.example.paid") is PurchaseState.AwaitingCheckout)
        assertEquals("US" to "alt-token", api.checkouts.single())

        billing.available.clear()
        assertEquals(PurchaseState.Installed, c.buy(activity, "com.example.paid"))
        assertEquals(BillingMode.PLAY_ONLY, billing.launches.single().second)
    }

    @Test fun `a Play build is never sent to Stripe without a programme`() = runTest {
        api.route = BillingRoute(route = "stripe", productId = "com.example.paid")
        assertEquals(PurchaseState.Installed, c.buy(activity, "com.example.paid"))
        assertTrue(api.checkouts.isEmpty())
    }

    @Test fun `choice route runs Google's choice screen, then Stripe if the buyer picks it`() = runTest {
        billing.country = "DE"
        api.route = BillingRoute("choice", clientApi = "billing_choice", reportToGoogle = true, productId = PAID)
        billing.available += "billing_choice"
        billing.next = PlayFlowResult.AlternativeChosen("choice-token")
        assertTrue(c.buy(activity, "com.example.paid") is PurchaseState.AwaitingCheckout)
        assertEquals(BillingMode.BILLING_CHOICE, billing.launches.single().second)
        assertEquals("DE" to "choice-token", api.checkouts.single())
    }

    @Test fun `choice route without enrolment is plain Play Billing`() = runTest {
        api.route = BillingRoute("choice", clientApi = "billing_choice", reportToGoogle = true, productId = PAID)
        assertEquals(PurchaseState.Installed, c.buy(activity, "com.example.paid"))
        assertEquals(BillingMode.PLAY_ONLY, billing.launches.single().second)
    }

    @Test fun `pending purchases wait and finish on restore`() = runTest {
        billing.next = PlayFlowResult.Pending(PlayPurchase(listOf("com.example.paid"), "ptok", pending = true))
        assertTrue(c.buy(activity, "com.example.paid") is PurchaseState.Pending)
        assertTrue(installed.isEmpty())
        assertEquals(mapOf("ptok" to "com.example.paid"), store.pending.play())
        billing.owned = listOf(PlayPurchase(listOf("com.example.paid"), "ptok", pending = false))
        assertEquals(1, c.restore(installMissing = true).restored)
        assertTrue(store.pending.play().isEmpty())
        assertTrue("com.example.paid" in installed)
    }

    @Test fun `server pending and outages keep the purchase rather than failing it`() = runTest {
        api.exchange = { PlayExchange.Pending }
        assertTrue(c.buy(activity, "com.example.paid") is PurchaseState.Pending)
        api.exchange = { PlayExchange.Unavailable("502") }
        assertTrue(c.buy(activity, "com.example.paid") is PurchaseState.Pending)
        assertEquals(mapOf("ptok" to "com.example.paid"), store.pending.play())
    }

    @Test fun `an entitlement that does not verify is never stored or installed`() = runTest {
        api.exchange = { PlayExchange.Ready(TestRegistry().token("com.example.paid")) }
        assertTrue(c.buy(activity, "com.example.paid") is PurchaseState.Failed)
        assertNull(store.get("com.example.paid"))
        assertTrue(installed.isEmpty())
    }

    @Test fun `cancel returns to idle and route failure is reported`() = runTest {
        billing.next = PlayFlowResult.Cancelled
        assertEquals(PurchaseState.Idle, c.buy(activity, "com.example.paid"))
        api.routeFails = true
        assertTrue(c.buy(activity, "com.example.paid") is PurchaseState.Failed)
    }

    @Test fun `restore on a fresh install maps Play products back through the catalogue`() = runTest {
        billing.owned = listOf(PlayPurchase(listOf("com.example.paid_x"), "ptok", pending = false))
        api.exchange = { PlayExchange.Ready(registry.token("com.example.Paid-X")) }
        val report = c.restore(paidCatalogue = { listOf("com.example.Paid-X") })
        assertEquals(1, report.restored)
        assertTrue(installed.isEmpty()) // restored the licence, did not download unasked
        assertTrue(c.entitlementFor("com.example.Paid-X") != null)
    }

    @Test fun `restore pulls Stripe purchases for this device's buyer session`() = runTest {
        billing.play = false
        store.buyerCookie = "azphalt_buyer_session=abc"
        api.restored = listOf(RestoredPurchase("com.example.paid", registry.token("com.example.paid")))
        assertEquals(1, c.restore().restored)
        assertEquals(EntitlementSource.STRIPE, store.get("com.example.paid")!!.source)
    }

    @Test fun `a refunded Play purchase is revoked and its package removed`() = runTest {
        c.buy(activity, "com.example.paid")
        billing.owned = listOf(PlayPurchase(listOf("com.example.paid"), "ptok", pending = false))
        api.exchange = { PlayExchange.Revoked }
        assertEquals(1, c.restore().removed)
        assertEquals(listOf("com.example.paid"), revoked)
        assertNull(store.get("com.example.paid"))
    }

    @Test fun `a lapsed subscription licence is removed on restore`() = runTest {
        val lapsed = registry.token("com.example.sub", expiresAt = "2026-10-02T00:00:00Z")
        store.put(StoredEntitlement("com.example.sub", lapsed, EntitlementSource.STRIPE))
        installed["com.example.sub"] = "x"
        billing.play = false
        assertEquals(1, c.restore().removed)
        assertEquals(listOf("com.example.sub"), revoked)
    }

    @Test fun `network failure during restore removes nothing`() = runTest {
        c.buy(activity, "com.example.paid")
        billing.owned = listOf(PlayPurchase(listOf("com.example.paid"), "ptok", pending = false))
        api.exchange = { PlayExchange.Unavailable("offline") }
        assertEquals(0, c.restore().removed)
        assertTrue(store.get("com.example.paid") != null)
    }

    @Test fun `an external entitlement verifies, stores and downloads exactly like an in-app one`() = runTest {
        // Bought on azphalt.store (or through a store app / a developer's azphalt-backed site) and handed over.
        val external = registry.token("com.example.web", subject = "buyer_web")
        assertTrue(c.acceptExternal("com.example.web", external) is EntitlementCheck.Valid)
        assertEquals(external, c.entitlementFor("com.example.web"))

        // The same package bought in-app yields the same kind of record and the same download token.
        api.exchange = { PlayExchange.Ready(registry.token("com.example.web")) }
        api.route = BillingRoute(route = "play", productId = "com.example.web")
        billing.next = PlayFlowResult.Purchased(PlayPurchase(listOf("com.example.web"), "ptok2", pending = false))
        val inApp = registry.token("com.example.web")
        assertTrue(c.acceptExternal("com.example.web", inApp) is EntitlementCheck.Valid)
        assertEquals(inApp, c.entitlementFor("com.example.web"))

        // A forged "external" one does not get in.
        val forged = TestRegistry().token("com.example.web")
        assertTrue(c.acceptExternal("com.example.web", forged) is EntitlementCheck.Invalid)
        assertEquals(inApp, c.entitlementFor("com.example.web"))
        // Restore keeps the external licence: no route drops it.
        store.put(StoredEntitlement("com.example.web", external, EntitlementSource.EXTERNAL))
        billing.play = false
        assertEquals(0, c.restore().removed)
        assertEquals(external, c.entitlementFor("com.example.web"))
    }
}
