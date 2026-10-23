package com.hereliesaz.graffitixr.feature.editor

import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What went wrong, in words a painter can act on. Exception class names and raw messages (which
 * can be null, or carry a file path) never reach the screen; they go to the log instead.
 */
internal fun plainFailure(t: Throwable): String = when (t) {
    is OutOfMemoryError -> "the canvas is too large for this device's memory; try a smaller size"
    is UnknownHostException -> "there's no internet connection"
    is SocketTimeoutException -> "the connection timed out; try again"
    is FileNotFoundException -> "the file couldn't be found or opened"
    is SecurityException -> "Graffux isn't allowed to open that file any more; pick it again"
    is IOException -> if (t.message?.contains("ENOSPC") == true || t.message?.contains("No space") == true) {
        "the device is out of storage space"
    } else {
        "the file couldn't be read or written"
    }
    else -> "something unexpected went wrong"
}
