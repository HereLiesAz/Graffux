package com.hereliesaz.graffitixr.common.model

/**
 * Bytes per element of an OpenCV Mat type (`CV_8UC1`, `CV_32FC1`, ...): depth size times channel
 * count, decoded the way OpenCV packs `CV_MAKETYPE(depth, cn)`.
 */
internal fun cvElemSize(type: Int): Int {
    val depthBytes = when (type and 7) {
        0, 1 -> 1 // CV_8U, CV_8S
        2, 3, 7 -> 2 // CV_16U, CV_16S, CV_16F
        4, 5 -> 4 // CV_32S, CV_32F
        else -> 8 // CV_64F
    }
    val channels = (type shr 3) + 1
    return depthBytes * channels
}

/**
 * The native restore wraps a descriptor blob in `cv::Mat(rows, cols, type, data)`, which reads
 * `rows * cols * elemSize(type)` bytes. A blob shorter than that would be read out of bounds, so
 * a corrupt project must fail here instead.
 */
internal fun requireDescriptorBlobCovers(data: ByteArray, rows: Int, cols: Int, type: Int) {
    if (rows <= 0 || cols <= 0) return
    require(type >= 0) { "descriptorsType ($type) must be a valid OpenCV type" }
    val needed = rows.toLong() * cols * cvElemSize(type)
    require(data.size.toLong() >= needed) {
        "descriptorsData (${data.size} B) is shorter than $rows x $cols of type $type ($needed B)"
    }
}
