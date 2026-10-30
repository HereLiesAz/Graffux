package com.hereliesaz.graffitixr.data.azphalt.purchase

import android.app.Activity

/** A completed (or pending) Play purchase, as the Billing Library reported it. */
data class PlayPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    /** `Purchase.PurchaseState`: 1 purchased, 2 pending. */
    val pending: Boolean,
)

/** What came of launching a Play purchase flow. */
sealed interface PlayFlowResult {
    data class Purchased(val purchase: PlayPurchase) : PlayFlowResult
    data class Pending(val purchase: PlayPurchase) : PlayFlowResult
    /**
     * The buyer chose the developer's billing on Google's choice screen; the sale is reported to
     * Google against [externalTransactionToken].
     */
    data class AlternativeChosen(val externalTransactionToken: String) : PlayFlowResult
    data object Cancelled : PlayFlowResult
    data class Failed(val message: String) : PlayFlowResult
}

/** Which Google programme API the Play purchase flow is launched under. */
enum class BillingMode { PLAY_ONLY, BILLING_CHOICE, USER_CHOICE }

/**
 * The Play Billing Library, behind an interface so purchase logic is testable without Google Play.
 * [PlayBillingGateway] is the real one.
 */
interface BillingGateway {
    /** True when this build was installed by Google Play (not the GitHub APK or a sideload). */
    fun isPlayDistribution(): Boolean

    /** The buyer's Play country (`BillingConfig.getCountryCode`), or null when Play cannot say. */
    suspend fun countryCode(): String?

    /** Whether Google has this app enrolled and live for [mode] / alternative-billing-only, for this user. */
    suspend fun isProgrammeAvailable(clientApi: String): Boolean

    /**
     * US alternative billing only: show Google's one-time information dialog when due, then create the
     * external transaction token the sale is reported against. Null when unavailable.
     */
    suspend fun alternativeBillingOnlyToken(activity: Activity): String?

    /** Launch Play's purchase flow for [productId], under [mode]. Suspends until Google answers. */
    suspend fun purchase(activity: Activity, productId: String, mode: BillingMode): PlayFlowResult

    /**
     * What Play will charge for [productId], localised and formatted by Google (`ProductDetails`
     * one-time offer's `formattedPrice`): the number a Play route's Buy button must show. Null when
     * Play cannot say.
     */
    suspend fun formattedPrice(productId: String): String? = null

    /** Every one-time purchase Play holds for this user (restores on reinstall / a new device). */
    suspend fun ownedPurchases(): List<PlayPurchase>
}
