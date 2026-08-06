package com.hereliesaz.graffitixr.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb

fun calculateProgress(paths: List<Path>, bitmap: Bitmap): Int {
    val width = bitmap.width
    val height = bitmap.height
    if (width == 0 || height == 0) return 0

    val scale = if (width > 1024 || height > 1024) {
        minOf(1024f / width, 1024f / height)
    } else {
        1f
    }

    val sw = (width * scale).toInt().coerceAtLeast(1)
    val sh = (height * scale).toInt().coerceAtLeast(1)

    val scratch = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
    return try {
        val canvas = Canvas(scratch)
        canvas.scale(scale, scale)
        val paint = Paint().apply {
            color = Color.Red.toArgb()
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }

        paths.forEach { path ->
            canvas.drawPath(path.asAndroidPath(), paint)
        }

        val pixels = IntArray(sw * sh)
        scratch.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        val count = pixels.count { it != 0 }
        if (scale != 1f) {
            (count / (scale * scale)).toInt()
        } else {
            count
        }
    } finally {
        scratch.recycle()
    }
}
