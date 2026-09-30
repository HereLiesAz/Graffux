package com.hereliesaz.graffux

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How an entry's hero preview is drawn (see [HeroPreview]).
 *
 * - [LAYER]: a thumbnail of the active layer with the effect applied ([EffectLayerPreview]). Only
 *   installed LUTs: `EditorViewModel.previewInstalledLut` grades through the same
 *   `gradeWithInstalledLut` that `applyInstalledLut` commits.
 * - [FALLBACK]: the card's own icon or manifest preview image. Installed azphalt filters and tools:
 *   their sandbox host (`AzphaltSandboxHost`) exposes no pixel read or write, so a contribution
 *   cannot be run against an off-layer bitmap — only `requestRedraw` of the live layer.
 * - [STROKE]: brushes, Ink, and the built-in effect tools (Blur, Sharpen, Smudge, Liquify, Dodge,
 *   Burn, Heal, Clone, Color), which are painted strokes, not one-shot image results. They keep
 *   [StrokePreview] (which draws nothing for the tools, as before).
 */
internal enum class EffectPreviewKind { LAYER, FALLBACK, STROKE }

internal fun effectPreviewKind(entry: CarouselEntry): EffectPreviewKind = when (entry.action) {
    is CarouselAction.ExtensionLut -> EffectPreviewKind.LAYER
    is CarouselAction.ExtensionContribution -> EffectPreviewKind.FALLBACK
    else -> EffectPreviewKind.STROKE
}

/** The downscaled active layer effects render against; [generation] bumps on every new snapshot. */
internal data class LayerSnapshot(val bitmap: Bitmap, val generation: Long)

/** A rendered thumbnail's identity: which effect, at which parameters, on which snapshot. */
internal data class EffectPreviewKey(
    val effectKey: String,
    val params: CarouselItemSettings?,
    val generation: Long,
)

/** Renders [entry]'s effect onto a copy of [source] without committing anything; null if it can't. */
internal fun interface EffectPreviewRenderer {
    suspend fun render(entry: CarouselEntry, source: Bitmap, params: CarouselItemSettings?): Bitmap?
}

/**
 * An access-ordered LRU holding at most [maxEntries] values; each value pushed out (by size or by
 * [removeIf]) goes to [onEvict]. Not thread-safe: [EffectPreviewEngine] touches it on the main thread.
 */
internal class LruBitmapCache<K, V>(private val maxEntries: Int, private val onEvict: (V) -> Unit) {
    private val map = LinkedHashMap<K, V>(maxEntries + 1, LOAD_FACTOR, true)

    val size: Int get() = map.size
    val keys: Set<K> get() = map.keys.toSet()

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.put(key, value)?.takeIf { it !== value }?.let(onEvict)
        while (map.size > maxEntries) {
            val eldest = map.entries.iterator().next()
            map.remove(eldest.key)
            onEvict(eldest.value)
        }
    }

    fun removeIf(predicate: (K) -> Boolean) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key)) {
                it.remove()
                onEvict(e.value)
            }
        }
    }

    fun clear() = removeIf { true }
}

/** The long-edge size of the layer snapshot effects render against. */
private const val LOAD_FACTOR = 0.75f
internal const val EFFECT_PREVIEW_LONG_EDGE_PX = 256
internal const val EFFECT_PREVIEW_CACHE_SIZE = 12
internal const val EFFECT_SNAPSHOT_DEBOUNCE_MS = 250L
internal const val EFFECT_PARAMS_DEBOUNCE_MS = 100L

/** [source] scaled to fit [longEdge] (always a fresh, owned copy, so the live layer can change). */
internal fun downscaleForPreview(source: Bitmap, longEdge: Int = EFFECT_PREVIEW_LONG_EDGE_PX): Bitmap {
    val scale = longEdge.toFloat() / max(source.width, source.height)
    return if (scale >= 1f) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }
}

/**
 * The effect-thumbnail pipeline behind the hero preview: one downscaled snapshot of the active
 * layer, effects rendered against it off the main thread, and an LRU of the results keyed by
 * [EffectPreviewKey]. Call everything but the renderer from the main thread. The stroke/drawing
 * path never goes through here.
 */
@androidx.compose.runtime.Stable
internal class EffectPreviewEngine(
    private val renderer: EffectPreviewRenderer,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxEntries: Int = EFFECT_PREVIEW_CACHE_SIZE,
) {
    /** The current snapshot, or null when there is no active layer with pixels. */
    var snapshot by mutableStateOf<LayerSnapshot?>(null)
        private set

    private var generation = 0L
    private val displayed = HashMap<Bitmap, Int>()
    private val pendingRecycle = HashSet<Bitmap>()
    private val inFlight = HashMap<EffectPreviewKey, CompletableDeferred<Bitmap?>>()

    /** How many renders have run (not cache hits): tests read it. */
    var renderCount = 0
        private set

    internal val cache = LruBitmapCache<EffectPreviewKey, Bitmap>(maxEntries, ::release)

    /**
     * Takes a new snapshot of [layer] (downscaled on the background dispatcher) and drops every
     * thumbnail of an older one. The caller debounces ([EFFECT_SNAPSHOT_DEBOUNCE_MS]).
     */
    suspend fun updateSource(layer: Bitmap?) {
        val scaled = layer?.takeUnless { it.isRecycled || it.width == 0 || it.height == 0 }
            ?.let { src -> withContext(dispatcher) { runCatching { downscaleForPreview(src) }.getOrNull() } }
        generation++
        snapshot = scaled?.let { LayerSnapshot(it, generation) }
        val current = generation
        cache.removeIf { it.generation != current }
    }

    fun keyFor(entry: CarouselEntry, params: CarouselItemSettings?): EffectPreviewKey? =
        snapshot?.let { EffectPreviewKey(entry.key, params, it.generation) }

    fun cached(key: EffectPreviewKey): Bitmap? = cache[key]

    /** [entry]'s thumbnail at [params] on the current snapshot: cached, or rendered now. */
    @Suppress("ReturnCount")
    suspend fun thumbnail(entry: CarouselEntry, params: CarouselItemSettings?): Bitmap? {
        val snap = snapshot ?: return null
        val key = EffectPreviewKey(entry.key, params, snap.generation)
        cache[key]?.let { return it }
        inFlight[key]?.let { return it.await() }
        val deferred = CompletableDeferred<Bitmap?>()
        inFlight[key] = deferred
        try {
            renderCount++
            val out = withContext(dispatcher) {
                runCatching { renderer.render(entry, snap.bitmap, params) }.getOrNull()
            }
            // A newer snapshot arrived meanwhile: this result belongs to nothing.
            val result = if (out != null && snapshot?.generation == key.generation) {
                cache.put(key, out)
                out
            } else {
                out?.recycle()
                null
            }
            deferred.complete(result)
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            deferred.complete(null)
            throw e
        } finally {
            inFlight.remove(key)
        }
    }

    /** Marks [bitmap] as on screen, so an eviction defers its recycle until [release]d here. */
    fun retain(bitmap: Bitmap) {
        displayed[bitmap] = (displayed[bitmap] ?: 0) + 1
    }

    fun unretain(bitmap: Bitmap) {
        val n = (displayed[bitmap] ?: return) - 1
        if (n > 0) {
            displayed[bitmap] = n
        } else {
            displayed.remove(bitmap)
            if (pendingRecycle.remove(bitmap)) bitmap.recycle()
        }
    }

    private fun release(bitmap: Bitmap) {
        if (displayed.containsKey(bitmap)) pendingRecycle += bitmap else bitmap.recycle()
    }

    fun clear() {
        cache.clear()
        snapshot = null
    }
}

/** Taller than the stroke preview, so a layer thumbnail reads; it grows upward from the same slot. */
internal val EffectPreviewHeight = 72.dp
private val ThumbShape = RoundedCornerShape(8.dp)

/**
 * The hero preview for an effect: the active layer with the effect applied, aspect-fit, rounded,
 * or [fallback] when there is no layer snapshot or the effect can't render. It sits bottom-aligned
 * in the preview slot and grows upward, so the gap above the card is unchanged. While a render is
 * running (first time, or [EFFECT_PARAMS_DEBOUNCE_MS] after the card's settings move) it shows the
 * plain snapshot, then crossfades to the result.
 */
@Suppress("FunctionNaming")
@Composable
internal fun EffectLayerPreview(
    entry: CarouselEntry,
    content: CarouselContent,
    engine: EffectPreviewEngine,
    fallback: @Composable () -> Unit,
) {
    val snap = engine.snapshot
    if (snap == null) {
        fallback()
        return
    }
    val params = content.itemSettings(entry)
    val key = EffectPreviewKey(entry.key, params, snap.generation)
    var result by remember(entry.key, snap.generation) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(entry.key, snap.generation) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val hit = engine.cached(key)
        if (hit != null) {
            result = hit
            return@LaunchedEffect
        }
        // Debounces slider moves: a new key cancels this before it renders.
        if (result != null) delay(EFFECT_PARAMS_DEBOUNCE_MS)
        val out = engine.thumbnail(entry, params)
        failed = out == null
        result = out
    }
    if (failed) {
        fallback()
        return
    }
    val shown = result?.takeUnless { it.isRecycled } ?: snap.bitmap
    Crossfade(
        targetState = shown,
        animationSpec = tween(CROSSFADE_MS),
        label = "effect-preview",
        modifier = Modifier
            .wrapContentHeight(Alignment.Bottom, unbounded = true)
            .height(EffectPreviewHeight),
    ) { bitmap ->
        DisposableEffect(bitmap) {
            engine.retain(bitmap)
            onDispose { engine.unretain(bitmap) }
        }
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(ThumbShape)
                    .testTag(
                        if (bitmap === snap.bitmap) {
                            "carousel.effect.source.${entry.key}"
                        } else {
                            "carousel.effect.${entry.key}"
                        },
                    ),
            )
        }
    }
}

/** The card's own tip visual (icon or manifest preview), for effects without a layer thumbnail. */
@Suppress("FunctionNaming")
@Composable
internal fun EffectFallbackPreview(entry: CarouselEntry, content: CarouselContent) {
    Box(Modifier.testTag("carousel.effect.fallback.${entry.key}")) {
        TipVisual(carouselTip(entry), content, MaterialTheme.colorScheme.onSurface, FallbackSize)
    }
}

private val FallbackSize = 36.dp
private const val CROSSFADE_MS = 180
