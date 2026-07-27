package com.hereliesaz.graffitixr.data.figma

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Figma REST API models and a transport-agnostic client, mirroring [RepositoryClient]'s design: the
 * caller supplies the HTTP function, so everything here is pure and unit-testable without a network.
 *
 * Only the read surface is modelled, because that is all the public API offers — Figma's REST API
 * cannot create or modify design content in a file. Pushing artwork the other way needs a companion
 * Figma plugin, which is tracked separately.
 */

/** Lenient so a newer/richer Figma response never breaks parsing — mirrors AzphaltJson's posture. */
internal val FigmaJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

object FigmaApi {
    const val BASE_URL = "https://api.figma.com"

    /** Figma authenticates a personal access token with this header, not `Authorization: Bearer`. */
    const val TOKEN_HEADER = "X-Figma-Token"

    /** Rendered image URLs are temporary; Figma expires them after 30 days. */
    const val MAX_IMAGE_SCALE = 4f
    const val MIN_IMAGE_SCALE = 0.01f
}

@Serializable
data class FigmaUser(
    val id: String = "",
    val email: String = "",
    @SerialName("handle") val handle: String = "",
)

@Serializable
data class FigmaFileResponse(
    val name: String = "",
    val lastModified: String = "",
    val version: String = "",
    val document: FigmaNode? = null,
)

@Serializable
data class FigmaNode(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val children: List<FigmaNode> = emptyList(),
    val absoluteBoundingBox: FigmaRect? = null,
)

@Serializable
data class FigmaRect(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
)

@Serializable
data class FigmaImagesResponse(
    val err: String? = null,
    /** Node id → temporary PNG URL. A node Figma couldn't render maps to null. */
    val images: Map<String, String?> = emptyMap(),
    val status: Int = 0,
)

/** A frame the user can pick to import: a renderable top-level node with real dimensions. */
data class FigmaFrame(
    val id: String,
    val name: String,
    val pageName: String,
    val width: Float,
    val height: Float,
)

class FigmaException(message: String) : Exception(message)

/**
 * Read-only Figma client. [httpGet] takes `(url, headers)` and returns the response body, exactly
 * like [ExtensionRepository.httpGetString] — so the repository supplies the real transport and tests
 * supply a canned one.
 */
class FigmaClient(
    private val token: String,
    private val httpGet: (String, Map<String, String>) -> String,
    private val baseUrl: String = FigmaApi.BASE_URL,
) {
    private val headers get() = mapOf(FigmaApi.TOKEN_HEADER to token)

    /** Verifies the token works, returning the account it belongs to. */
    fun me(): FigmaUser = FigmaJson.decodeFromString(httpGet("$baseUrl/v1/me", headers))

    /**
     * Fetches a file's node tree. [depth] bounds the traversal — depth 2 reaches pages and their
     * top-level frames, which is all the picker needs and avoids pulling an entire deep document.
     */
    fun file(fileKey: String, depth: Int = 2): FigmaFileResponse =
        FigmaJson.decodeFromString(httpGet("$baseUrl/v1/files/$fileKey?depth=$depth", headers))

    /**
     * Asks Figma to render [nodeIds] and returns node id → temporary PNG URL. Figma renders these
     * server-side, so this is a request to *start* producing the images as much as to fetch links.
     */
    fun images(fileKey: String, nodeIds: List<String>, scale: Float = 2f): Map<String, String> {
        if (nodeIds.isEmpty()) return emptyMap()
        val ids = nodeIds.joinToString(",") { encodeQueryComponent(it) }
        val clamped = scale.coerceIn(FigmaApi.MIN_IMAGE_SCALE, FigmaApi.MAX_IMAGE_SCALE)
        val body = httpGet("$baseUrl/v1/images/$fileKey?ids=$ids&format=png&scale=$clamped", headers)
        val parsed = FigmaJson.decodeFromString<FigmaImagesResponse>(body)
        if (parsed.err != null) throw FigmaException(parsed.err)
        // Drop nodes Figma declined to render rather than surfacing a null URL downstream.
        return parsed.images.mapNotNull { (id, url) -> url?.let { id to it } }.toMap()
    }
}

/**
 * Node ids contain a colon (`1:23`), which is legal in a query value but is percent-encoded here
 * anyway so the URL is unambiguous regardless of how the transport handles it.
 */
internal fun encodeQueryComponent(value: String): String = buildString {
    for (c in value) {
        when {
            c.isLetterOrDigit() || c in "-_.~" -> append(c)
            else -> append('%').append("%02X".format(c.code))
        }
    }
}
