package com.hereliesaz.graffitixr.data.azphalt.purchase

import android.app.Activity
import android.content.Context
import android.os.Build
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode.OK
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.DeveloperBillingOptionParams
import com.android.billingclient.api.EnableBillingProgramParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [BillingGateway] over the Play Billing Library (9.1+, which the billing-choice programme needs).
 *
 * One `BillingClient` per [BillingMode], because a client is built with at most one programme API
 * enabled. Acknowledgement is deliberately NOT done here: the repository acknowledges after it has
 * issued the licence (`POST /entitlements/play`), so a purchase that never became a licence stays
 * unacknowledged and Google refunds it.
 */
class PlayBillingGateway(private val context: Context) : BillingGateway {

    private val clients = mutableMapOf<BillingMode, BillingClient>()
    private var alternativeOnlyClient: BillingClient? = null
    @Volatile private var flow: CompletableDeferred<PlayFlowResult>? = null

    private fun builder(): BillingClient.Builder = BillingClient.newBuilder(context)
        .setListener { result, purchases ->
            val done = flow ?: return@setListener
            val first = purchases?.firstOrNull()
            done.complete(when {
                result.responseCode == OK && first != null ->
                    if (first.purchaseState == Purchase.PurchaseState.PENDING) {
                        PlayFlowResult.Pending(first.toPlayPurchase())
                    } else {
                        PlayFlowResult.Purchased(first.toPlayPurchase())
                    }
                result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> PlayFlowResult.Cancelled
                else -> PlayFlowResult.Failed(failure(result))
            })
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()

    private fun client(mode: BillingMode): BillingClient = synchronized(clients) {
        clients.getOrPut(mode) {
            val b = builder()
            when (mode) {
                BillingMode.PLAY_ONLY -> Unit
                BillingMode.BILLING_CHOICE -> b.enableBillingProgram(
                    EnableBillingProgramParams.newBuilder()
                        .setBillingProgram(BillingClient.BillingProgram.BILLING_CHOICE)
                        .setDeveloperProvidedBillingListener { details ->
                            val token = details.externalTransactionToken
                            flow?.complete(
                                if (token.isNullOrBlank()) {
                                    PlayFlowResult.Failed("Google Play returned no transaction token.")
                                } else {
                                    PlayFlowResult.AlternativeChosen(token)
                                },
                            )
                        }
                        .build(),
                )
                BillingMode.USER_CHOICE -> b.enableUserChoiceBilling { details ->
                    flow?.complete(PlayFlowResult.AlternativeChosen(details.externalTransactionToken))
                }
            }
            b.build()
        }
    }

    private fun altOnlyClient(): BillingClient = synchronized(clients) {
        alternativeOnlyClient ?: builder().enableAlternativeBillingOnly().build().also { alternativeOnlyClient = it }
    }

    private suspend fun ready(c: BillingClient): Boolean {
        if (c.isReady) return true
        return suspendCancellableCoroutine { cont ->
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    override fun isPlayDistribution(): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)

    override suspend fun countryCode(): String? {
        val c = client(BillingMode.PLAY_ONLY)
        if (!ready(c)) return null
        return suspendCancellableCoroutine { cont ->
            c.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { result, config ->
                if (cont.isActive) cont.resume(config?.countryCode?.takeIf { result.responseCode == OK })
            }
        }
    }

    override suspend fun isProgrammeAvailable(clientApi: String): Boolean = when (clientApi) {
        "alternative_billing_only" -> {
            val c = altOnlyClient()
            ready(c) && suspendCancellableCoroutine { cont ->
                c.isAlternativeBillingOnlyAvailableAsync { r ->
                    if (cont.isActive) cont.resume(r.responseCode == OK)
                }
            }
        }
        "billing_choice" -> {
            val c = client(BillingMode.BILLING_CHOICE)
            ready(c) && suspendCancellableCoroutine { cont ->
                c.isBillingProgramAvailableAsync(BillingClient.BillingProgram.BILLING_CHOICE) { r, _ ->
                    if (cont.isActive) cont.resume(r.responseCode == OK)
                }
            }
        }
        // User choice billing has no availability call: an unenrolled app simply never sees the choice screen.
        "user_choice" -> ready(client(BillingMode.USER_CHOICE))
        else -> false
    }

    override suspend fun alternativeBillingOnlyToken(activity: Activity): String? {
        val c = altOnlyClient()
        val shown = ready(c) && suspendCancellableCoroutine { cont ->
            c.showAlternativeBillingOnlyInformationDialog(activity) { r ->
                if (cont.isActive) cont.resume(r.responseCode == OK)
            }
        }
        if (!shown) return null
        return suspendCancellableCoroutine { cont ->
            c.createAlternativeBillingOnlyReportingDetailsAsync { r, details ->
                if (cont.isActive) cont.resume(details?.externalTransactionToken?.takeIf { r.responseCode == OK })
            }
        }
    }

    override suspend fun purchase(activity: Activity, productId: String, mode: BillingMode): PlayFlowResult {
        val c = client(mode)
        val details = if (!ready(c)) null else suspendCancellableCoroutine<ProductDetails?> { cont ->
            c.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                )).build(),
            ) { _, result -> if (cont.isActive) cont.resume(result.productDetailsList.firstOrNull()) }
        }
        if (details == null) return PlayFlowResult.Failed(NOT_ON_PLAY)

        val deferred = CompletableDeferred<PlayFlowResult>()
        flow = deferred
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
            )
            .apply {
                if (mode == BillingMode.BILLING_CHOICE) {
                    enableDeveloperBillingOption(
                        DeveloperBillingOptionParams.newBuilder()
                            .setBillingProgram(BillingClient.BillingProgram.BILLING_CHOICE)
                            .build(),
                    )
                }
            }
            .build()
        val launched = c.launchBillingFlow(activity, params)
        val result = when (launched.responseCode) {
            OK -> deferred.await()
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                ownedPurchases().firstOrNull { productId in it.productIds }?.let { PlayFlowResult.Purchased(it) }
                    ?: PlayFlowResult.Failed("Google Play says you already own this.")
            else -> PlayFlowResult.Failed(failure(launched))
        }
        flow = null
        return result
    }

    override suspend fun ownedPurchases(): List<PlayPurchase> {
        val c = client(BillingMode.PLAY_ONLY)
        if (!ready(c)) throw java.io.IOException("Google Play is not available")
        return suspendCancellableCoroutine { cont ->
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            c.queryPurchasesAsync(params) { r, list ->
                if (!cont.isActive) return@queryPurchasesAsync
                cont.resume(if (r.responseCode == OK) list.map { it.toPlayPurchase() } else emptyList())
            }
        }
    }

    companion object {
        const val PLAY_STORE = "com.android.vending"
        private const val NOT_ON_PLAY = "Google Play can't offer this extension yet. Try again shortly."
    }
}

private fun Purchase.toPlayPurchase() = PlayPurchase(
    productIds = products,
    purchaseToken = purchaseToken,
    pending = purchaseState == Purchase.PurchaseState.PENDING,
)

private fun failure(r: BillingResult): String = r.debugMessage.ifBlank { "Google Play error ${r.responseCode}" }
