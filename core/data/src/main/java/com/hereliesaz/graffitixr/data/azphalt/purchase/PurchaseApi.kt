package com.hereliesaz.graffitixr.data.azphalt.purchase

import com.hereliesaz.graffitixr.data.azphalt.AzphaltStoreHandoff
import com.hereliesaz.graffitixr.data.azphalt.RepositoryApiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_ACCEPTED
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PAYMENT_REQUIRED
import java.net.URL
import java.net.URLEncoder

/** `GET /billing/route` (azphalt storefront-worker README § In-app purchasing). */
@Serializable
data class BillingRoute(
    /** `play`, `stripe`, `choice` or `unavailable`. */
    val route: String,
    val program: String? = null,
    /** `alternative_billing_only`, `billing_choice` or `user_choice`. */
    val clientApi: String? = null,
    val reportToGoogle: Boolean = false,
    val reason: String? = null,
    val productId: String? = null,
    val country: String? = null,
)

/** The outcome of `POST /entitlements/play`. */
sealed interface PlayExchange {
    data class Ready(val entitlement: String) : PlayExchange
    data object Pending : PlayExchange
    /** 402: Google says this purchase does not entitle the package. */
    data object Refused : PlayExchange
    /** 403 `revoked`: the repository voided it (refunded, charged back). The only removal signal. */
    data object Revoked : PlayExchange
    /**
     * Any other 403 (`app_not_accepted`, or a repository too old to say): a configuration problem,
     * not proof the buyer lost the purchase. Never removes anything.
     */
    data class NotAccepted(val message: String) : PlayExchange
    /** 502 or a network failure: try again later; never tell the buyer they did not pay. */
    data class Unavailable(val message: String) : PlayExchange
}

data class CheckoutStart(val sessionId: String, val url: String, val buyerCookie: String?)

sealed interface CheckoutStatus {
    data class Ready(val entitlement: String) : CheckoutStatus
    data object Pending : CheckoutStatus
    data object Revoked : CheckoutStatus
    data class Unavailable(val message: String) : CheckoutStatus
}

/** One of the buyer's purchases; [revoked] when the repository says it was refunded or disputed. */
data class RestoredPurchase(val packageId: String, val token: String, val revoked: Boolean = false)

/** The repository's purchase endpoints. An interface so [PurchaseCoordinator] is testable with a fake. */
interface PurchaseApi {
    fun route(appId: String, distribution: String, country: String?, packageId: String): BillingRoute
    fun exchangePlay(appId: String, packageId: String, productId: String, purchaseToken: String): PlayExchange
    fun startCheckout(
        packageId: String,
        appId: String,
        buyerCookie: String?,
        region: String?,
        externalTransactionToken: String?,
    ): CheckoutStart
    fun checkoutStatus(sessionId: String, buyerCookie: String?): CheckoutStatus
    fun purchases(buyerCookie: String): List<RestoredPurchase>
}

/**
 * Whether a 403 body says the purchase was voided: `{error:{code:"revoked"}}` (`/entitlements/play`)
 * or `{status:"revoked"}` (checkout session). Anything else, including a bare 403, is not.
 */
internal fun isRevokedReply(body: String): Boolean = runCatching {
    val o = Json.parseToJsonElement(body) as JsonObject
    val code = ((o["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content
    code == "revoked" || (o["status"] as? JsonPrimitive)?.content == "revoked"
}.getOrDefault(false)

/** [PurchaseApi] over HTTPS against [baseUrl] (azphalt.store by default). */
class HttpPurchaseApi(private val baseUrl: String = AzphaltStoreHandoff.WEB_STORE_URL) : PurchaseApi {
    private val json = Json { ignoreUnknownKeys = true }

    private class Reply(val code: Int, val body: String, val setCookie: String?)

    private fun call(method: String, path: String, body: JsonObject? = null, cookie: String? = null): Reply {
        val url = baseUrl + path
        if (!url.startsWith("https://")) throw IOException("Repository API requires https: $url")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.instanceFollowRedirects = false
        c.requestMethod = method
        c.setRequestProperty("Accept", "application/json")
        if (cookie != null) c.setRequestProperty("Cookie", cookie)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in HTTP_OK until HTTP_MULT_CHOICE) c.inputStream else c.errorStream
        val text = stream?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER_BYTES)
            var total = 0
            while (true) {
                val n = s.read(buf); if (n < 0) break
                total += n; if (total > MAX_RESPONSE_BYTES) throw IOException("response too large")
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        } ?: ""
        // Only the name=value part is ever sent back.
        val cookieHeader = c.getHeaderField("set-cookie")?.substringBefore(";")
        return Reply(code, text, cookieHeader)
    }

    private fun field(body: String, name: String): String? = runCatching {
        (json.parseToJsonElement(body) as JsonObject)[name]?.let { (it as? JsonPrimitive)?.content }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    override fun route(appId: String, distribution: String, country: String?, packageId: String): BillingRoute {
        val query = "app=${enc(appId)}&distribution=${enc(distribution)}" +
            "&country=${enc(country ?: "")}&packageId=${enc(packageId)}"
        val r = call("GET", "/billing/route?$query")
        if (r.code != HTTP_OK) throw IOException("billing route: HTTP ${r.code}")
        return json.decodeFromString(BillingRoute.serializer(), r.body)
    }

    override fun exchangePlay(
        appId: String,
        packageId: String,
        productId: String,
        purchaseToken: String,
    ): PlayExchange = try {
        val r = call("POST", "/entitlements/play", buildJsonObject {
            put("packageName", appId)
            put("packageId", packageId)
            put("productId", productId)
            put("purchaseToken", purchaseToken)
        })
        when (r.code) {
            HTTP_OK -> field(r.body, "entitlement")?.let { PlayExchange.Ready(it) }
                ?: PlayExchange.Unavailable("no entitlement in response")
            HTTP_ACCEPTED -> PlayExchange.Pending
            HTTP_PAYMENT_REQUIRED -> PlayExchange.Refused
            HTTP_FORBIDDEN ->
                if (isRevokedReply(r.body)) PlayExchange.Revoked else PlayExchange.NotAccepted(r.body.take(ERROR_CHARS))
            else -> PlayExchange.Unavailable("HTTP ${r.code}")
        }
    } catch (e: IOException) {
        PlayExchange.Unavailable(e.message ?: "network error")
    }

    override fun startCheckout(
        packageId: String,
        appId: String,
        buyerCookie: String?,
        region: String?,
        externalTransactionToken: String?,
    ): CheckoutStart {
        val r = call("POST", "/api/checkout", buildJsonObject {
            put("packageId", packageId)
            put("inApp", true)
            put("appId", appId)
            region?.let { put("region", it) }
            externalTransactionToken?.let { put("externalTransactionToken", it) }
        }, buyerCookie)
        if (r.code != HTTP_OK) throw IOException(field(r.body, "error") ?: "checkout failed: HTTP ${r.code}")
        val session = (json.parseToJsonElement(r.body) as JsonObject)["session"] as? JsonObject
            ?: throw IOException("checkout returned no session")
        val id = (session["id"] as? JsonPrimitive)?.content ?: throw IOException("checkout returned no session id")
        val url = (session["url"] as? JsonPrimitive)?.content ?: throw IOException("checkout returned no url")
        if (!url.startsWith("https://")) throw IOException("checkout url is not https")
        return CheckoutStart(id, url, r.setCookie)
    }

    override fun checkoutStatus(sessionId: String, buyerCookie: String?): CheckoutStatus = try {
        val path = "/api/checkout/session/" + RepositoryApiClient.encodePathSegment(sessionId)
        val r = call("GET", path, cookie = buyerCookie)
        when (r.code) {
            HTTP_OK -> field(r.body, "token")?.let { CheckoutStatus.Ready(it) }
                ?: CheckoutStatus.Unavailable("no token")
            HTTP_ACCEPTED -> CheckoutStatus.Pending
            HTTP_FORBIDDEN ->
                if (isRevokedReply(r.body)) CheckoutStatus.Revoked else CheckoutStatus.Unavailable("HTTP 403")
            else -> CheckoutStatus.Unavailable("HTTP ${r.code}")
        }
    } catch (e: IOException) {
        CheckoutStatus.Unavailable(e.message ?: "network error")
    }

    override fun purchases(buyerCookie: String): List<RestoredPurchase> {
        // includeRevoked: refunded purchases come back flagged, so restore revokes exactly those.
        val r = call("GET", "/api/purchases?includeRevoked=1", cookie = buyerCookie)
        if (r.code != HTTP_OK) throw IOException("purchases: HTTP ${r.code}")
        val items = json.parseToJsonElement(r.body) as? kotlinx.serialization.json.JsonArray
        return items.orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = (o["packageId"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val token = (o["token"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val revoked = (o["revoked"] as? JsonPrimitive)?.content == "true"
            RestoredPurchase(id, token, revoked)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val HTTP_MULT_CHOICE = 300
        const val BUFFER_BYTES = 8192
        const val MAX_RESPONSE_BYTES = 1_000_000
        const val ERROR_CHARS = 200
    }
}
