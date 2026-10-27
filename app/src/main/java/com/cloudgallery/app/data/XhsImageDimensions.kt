package com.cloudgallery.app.data

/** Page metadata may describe a smaller rendition of the same fileId. */
internal fun sufficientXhsDimensions(width: Int, height: Int, expectedWidth: Int, expectedHeight: Int): Boolean {
    if (expectedWidth <= 0 || expectedHeight <= 0 || width < expectedWidth || height < expectedHeight ||
        width.toLong() * height > 150_000_000) return false
    val a = width.toLong() * expectedHeight
    val b = height.toLong() * expectedWidth
    // Allow rounding from rescaling, while rejecting a different crop/aspect ratio.
    return kotlin.math.abs(a - b).toDouble() <= maxOf(a, b) * .01
}

/** Swap dimensions only when the file explicitly declares a quarter-turn orientation. */
internal fun sufficientOrientedXhsDimensions(width: Int, height: Int, expectedWidth: Int, expectedHeight: Int,
    orientation: Int): Boolean = sufficientXhsDimensions(width, height, expectedWidth, expectedHeight) ||
    orientation in 5..8 && sufficientXhsDimensions(height, width, expectedWidth, expectedHeight)


/** The direct file endpoint can cap large images below the stale page metadata.
 * Accept its high-resolution rendition only: at least 2048 px on the long edge,
 * 2 MP, matching aspect ratio, no upscaling and no preview URL exception. */
internal fun acceptableXhsSourceDimensions(width: Int, height: Int, expectedWidth: Int, expectedHeight: Int,
    orientation: Int, directSource: Boolean): Boolean {
    if (sufficientOrientedXhsDimensions(width, height, expectedWidth, expectedHeight, orientation)) return true
    if (!directSource || width <= 0 || height <= 0 || expectedWidth <= 0 || expectedHeight <= 0 ||
        maxOf(width, height) < 2048 || width.toLong() * height !in 2_000_000L..150_000_000L) return false
    fun capped(w: Int, h: Int): Boolean {
        if (w > expectedWidth || h > expectedHeight) return false
        val a = w.toLong() * expectedHeight
        val b = h.toLong() * expectedWidth
        return kotlin.math.abs(a - b).toDouble() <= maxOf(a, b) * .01
    }
    return capped(width, height) || orientation in 5..8 && capped(height, width)
}
