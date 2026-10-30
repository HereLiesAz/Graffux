package com.hereliesaz.graffitixr.data.azphalt.purchase

import android.app.Activity
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Duration
import java.time.Instant

private const val PAID = "com.example.paid"
private const val PORTAL = "<html><body>Sign in to the hotel Wi-Fi</body></html>"

/** Second-audit purchase findings: #2, #5, #11, #15, #16. */
class PurchaseRound2Test {
    @get:Rule val tmp = TemporaryFolder()
    private val registry = TestRegistry()
    private val activity = mockk<Activity>(relaxed = true)
    private lateinit var billing: FakeBilling
    private lateinit var api: FakeApi
    private lateinit var store: EntitlementStore
    private val installed = mutableMapOf<String, String>()
    private val start = Instant.parse("2026-10-28T00:00:00Z")

    @Before fun setUp() {
        billing = FakeBilling()
        api = FakeApi(registry)
        store = EntitlementStore(tmp.newFile("e.json").also { it.delete() })
    }

    private fun coordinator(
        now: Instant = start,
        trusted: () -> Set<String> = { setOf(registry.publicKey) },
        refresh: suspend () -> Unit = {},
    ) = PurchaseCoordinator(
        appId = "com.hereliesaz.graffux",
        backend = PurchaseBackend(billing, api, EntitlementVerifier(trusted), store),
        hooks = PurchaseHooks(
            install = { id, token -> installed[id] = token },
            revoke = { id, _ -> installed.remove(id) },
            isInstalled = { it in installed },
            refreshTrust = refresh,
        ),
        tuning = PurchaseTuning(now = { now }, pollDelayMs = 0),
    )

    // #2 ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a captive portal's HTML 200 is an IOException from every parser`() {
        for (parse in listOf<() -> Unit>(
            { PurchaseReplies.route(PORTAL) },
            { PurchaseReplies.checkoutStart(PORTAL, null) },
            { PurchaseReplies.purchases(PORTAL) },
            { PurchaseReplies.purchases("""{"not":"an array"}""") },
        )) {
            try {
                parse(); fail("parsed a portal page")
            } catch (_: IOException) {
                // expected
            }
        }
    }

    @Test fun `a non-IO failure ends the buy as Failed, never stuck on Working`() = runTest {
        val c = coordinator()
        api.routeError = SerializationException("Unexpected JSON token at offset 0")
        val state = c.buy(activity, PAID)
        assertTrue(state is PurchaseState.Failed)
        assertEquals(state, c.states.value[PAID])
        // And the next tap runs normally.
        api.routeError = null
        assertEquals(PurchaseState.Installed, c.buy(activity, PAID))
    }

    // #5 ────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun abandonCheckout() {
        billing.play = false
        api.route = BillingRoute(route = "stripe", productId = PAID)
        assertTrue(coordinator().buy(activity, PAID) is PurchaseState.AwaitingCheckout)
        api.checkout = CheckoutStatus.Pending
    }

    @Test fun `a silent startup restore does not raise Not paid yet for an open checkout`() = runTest {
        abandonCheckout()
        val later = coordinator(now = start.plus(Duration.ofHours(1)))
        later.restore()
        val shown = later.states.value[PAID]
        assertTrue("card was set to $shown", shown == null || shown == PurchaseState.Idle)
        assertEquals(mapOf("cs_1" to PAID), store.pending.checkouts())
    }

    @Test fun `an abandoned checkout expires after 24 hours and is not polled again`() = runTest {
        abandonCheckout()
        val dayLater = coordinator(now = start.plus(Duration.ofHours(25)))
        dayLater.restore()
        assertTrue(store.pending.checkouts().isEmpty())
        assertEquals(PurchaseState.Idle, dayLater.states.value[PAID])
    }

    @Test fun `a session recorded before timestamps existed is stamped, then expires`() = runTest {
        // Legacy record: no seen-at stamp.
        store.pending.addCheckout("cs_old", PAID)
        val raw = tmp.root.resolve("e.json")
        raw.writeText(raw.readText().replace(Regex(""","checkoutSeenAt":\{[^}]*\}"""), ""))
        api.checkout = CheckoutStatus.Pending
        coordinator(now = start).restore()
        assertEquals(setOf("cs_old"), store.pending.checkouts().keys)
        coordinator(now = start.plus(Duration.ofHours(25))).restore()
        assertTrue(store.pending.checkouts().isEmpty())
    }

    // #11 ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `cancellation during the trust refresh stops the purchase instead of carrying on`() = runTest {
        val c = coordinator(trusted = { emptySet() }, refresh = { throw CancellationException("scope cancelled") })
        try {
            c.buy(activity, PAID); fail("cancellation was swallowed")
        } catch (_: CancellationException) {
            // expected
        }
        assertNull(store.get(PAID))
        assertFalse(PAID in installed)
        assertFalse(c.states.value[PAID] is PurchaseState.Working)
    }

    @Test fun `rethrowCancellation keeps other failures and rethrows cancellation`() {
        assertTrue(runCatching { throw IOException("x") }.rethrowCancellation().isFailure)
        try {
            runCatching { throw CancellationException("c") }.rethrowCancellation(); fail()
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test fun `empty trust (a bad clock) never removes a one-time licence`() = runTest {
        coordinator().buy(activity, PAID)
        val skewed = coordinator(trusted = { emptySet() })
        assertEquals(0, skewed.restore().removed)
        assertTrue(store.get(PAID) != null)
    }

    // #15 ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a purchase code restores the licence and the buyer cookie on a fresh install`() = runTest {
        api.checkout = CheckoutStatus.Ready(registry.token(PAID), PAID, "azphalt_buyer_session=xyz")
        val c = coordinator()
        assertFalse(c.hasCardPurchaseSession())
        val state = c.restoreFromCode("https://azphalt.store/checkout/success?session_id=cs_test_a1B2c3D4e5")
        assertEquals(PurchaseState.Installed, state)
        assertEquals(EntitlementSource.STRIPE, store.get(PAID)!!.source)
        assertEquals("azphalt_buyer_session=xyz", store.buyerCookie)
        assertTrue(c.hasCardPurchaseSession())
    }

    @Test fun `a purchase code is read from a bare id or a link, and junk is refused`() = runTest {
        assertEquals("cs_live_abcdefgh12", PurchaseCoordinator.checkoutSessionId("  cs_live_abcdefgh12 "))
        assertEquals("cs_abcdefgh12", PurchaseCoordinator.checkoutSessionId("x?session_id=cs_abcdefgh12&y=1"))
        assertNull(coordinator().restoreFromCode("hello"))
        api.checkout = CheckoutStatus.Unavailable("HTTP 500")
        assertTrue(coordinator().restoreFromCode("cs_abcdefgh12") is PurchaseState.Failed)
    }

    // #16 ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a Play route shows Play's price, a Stripe route the repository's`() = runTest {
        billing.price = "R$ 24,90"
        api.routes = mapOf(
            PAID to BillingRoute(route = "play", productId = PAID),
            "com.example.web" to BillingRoute(route = "stripe", productId = "com.example.web"),
        )
        val c = coordinator()
        c.loadPrices(listOf(PAID, "com.example.web"))
        assertEquals(BuyPrice.Play("R$ 24,90"), c.prices.value[PAID])
        assertEquals(BuyPrice.Repository, c.prices.value["com.example.web"])
    }

    @Test fun `no Play price means no number, and a non-Play build uses the repository's`() = runTest {
        billing.price = null
        val c = coordinator()
        c.loadPrices(listOf(PAID))
        assertNull(c.prices.value[PAID])
        billing.play = false
        val direct = coordinator()
        direct.loadPrices(listOf(PAID))
        assertEquals(BuyPrice.Repository, direct.prices.value[PAID])
    }
}
