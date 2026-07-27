package com.hereliesaz.graffitixr.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap

fun resizeBitmapForArCore(bitmap: Bitmap): Bitmap {
    val MAX_DIMENSION = 1024
    // A degenerate (zero-width/height) bitmap can't be meaningfully resized; falling through would
    // divide by zero below, producing an Infinity/NaN ratio and a 0-sized target that
    // Bitmap.createScaledBitmap rejects with IllegalArgumentException.
    if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
    if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) return bitmap

    val ratio = Math.min(
        MAX_DIMENSION.toFloat() / bitmap.width,
        MAX_DIMENSION.toFloat() / bitmap.height
    )
    val width = (bitmap.width * ratio).toInt()
    val height = (bitmap.height * ratio).toInt()

    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
