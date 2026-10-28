package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Finds the subject of a picture: the foreground a person would cut out. [segment] returns one
 * confidence (0..1) per pixel of [bitmap], row-major, or null when there is no model to run.
 * Called off the main thread.
 */
fun interface SubjectSegmenter {
    suspend fun segment(bitmap: Bitmap): FloatArray?
}

/**
 * ML Kit Subject Segmentation (Google Play services). It runs on the device. The model module is
 * declared as an install-time dependency in the manifest (`com.google.mlkit.vision.DEPENDENCIES`),
 * so Play delivers it with the app and no network is needed when it runs. Where the module is
 * missing (no Play services, or the download has not finished), [segment] returns null.
 */
class MlKitSubjectSegmenter : SubjectSegmenter {

    private val client by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
    }

    override suspend fun segment(bitmap: Bitmap): FloatArray? = suspendCancellableCoroutine { cont ->
        val task = try {
            client.process(InputImage.fromBitmap(bitmap, 0))
        } catch (e: IllegalStateException) {
            android.util.Log.w("SubjectSegmenter", "subject segmentation unavailable", e)
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        task.addOnSuccessListener { result ->
            val buffer = result.foregroundConfidenceMask
            val n = bitmap.width * bitmap.height
            if (buffer == null || buffer.remaining() < n) {
                cont.resume(null)
            } else {
                val out = FloatArray(n)
                buffer.get(out)
                cont.resume(out)
            }
        }.addOnFailureListener { e ->
            android.util.Log.w("SubjectSegmenter", "subject segmentation failed", e)
            cont.resume(null)
        }
    }
}

/** How a subject confidence mask becomes a selection: the pixels at or above [THRESHOLD]. */
internal object SubjectMask {
    const val THRESHOLD = 0.5f

    /** The long edge the picture is scaled to before segmenting (memory and speed, not quality). */
    const val MAX_EDGE = 1280

    fun threshold(confidence: FloatArray): BooleanArray = BooleanArray(confidence.size) { confidence[it] >= THRESHOLD }

    /** The factor [bitmap] is scaled by to fit [MAX_EDGE]: 1 when it already fits. */
    fun scaleFor(width: Int, height: Int): Float {
        val long = maxOf(width, height)
        return if (long <= MAX_EDGE) 1f else MAX_EDGE.toFloat() / long
    }
}
