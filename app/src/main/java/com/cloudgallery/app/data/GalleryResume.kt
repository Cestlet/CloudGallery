package com.cloudgallery.app.data

data class GalleryPosition(val index: Int = 0, val offset: Int = 0, val anchor: String? = null)

/** A successful scan timestamp survives process death; manual refresh bypasses this check. */
fun needsAutomaticScan(lastSuccess: Long, now: Long): Boolean =
    lastSuccess <= 0 || now < lastSuccess || now - lastSuccess >= 60_000

fun galleryItemKeys(media: List<MediaItem>, byDate: Boolean = true): List<String> = buildList {
    galleryGroups(media, byDate).forEach { (date, photos) ->
        if (date != null) add("date-$date")
        photos.forEach { add(it.id) }
    }
}

fun resolveGalleryPosition(position: GalleryPosition, keys: List<String>): GalleryPosition {
    if (keys.isEmpty()) return GalleryPosition()
    val anchored = position.anchor?.let(keys::indexOf)?.takeIf { it >= 0 }
    return position.copy(index = anchored ?: position.index.coerceIn(0, keys.lastIndex), offset = position.offset.coerceAtLeast(0))
}
