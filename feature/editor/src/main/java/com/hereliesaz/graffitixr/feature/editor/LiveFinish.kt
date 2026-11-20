package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.coroutines.CoroutineContext

/**
 * Starts [finish] (a stroke's GPU finish: last dabs, selection, one readback, then the engine is
 * given back) NOW, on [context], and returns its result for the commit to await.
 *
 * A stroke's live engine holds the document engine's lease until it is given back. The finish used
 * to run inside the commit coroutine, which first waits for every earlier commit of the layer to
 * publish; the next stroke's live setup waits for that lease, so a quick re-touch waited for the
 * whole commit queue (and past 2 s fell back to a cold engine). Run here, the engine is back as soon
 * as its own readback is done, whatever the commit queue is doing, and the next stroke's setup gets
 * the warm engine at once. Stroke boundaries, the commit and its history entry are untouched: the
 * commit still awaits this result at the same point it ran the finish before. Never run on the main
 * thread or the touch path; independent of the commit's lifetime, so a commit cancelled meanwhile
 * (undo) cannot leave the engine held: [finish] gives it back itself.
 */
internal fun <R> CoroutineScope.startLiveFinish(context: CoroutineContext, finish: suspend () -> R): Deferred<R> =
    async(context) { finish() }
