package com.cloudgallery.app.data

enum class GallerySort(val title: String, val english: String, val byDate: Boolean = false) {
    NEWEST("修改时间：从新到旧", "Modified: newest first", true),
    OLDEST("修改时间：从旧到新", "Modified: oldest first", true),
    NAME_ASC("名称：A → Z", "Name: A → Z"),
    NAME_DESC("名称：Z → A", "Name: Z → A"),
    LARGEST("文件大小：从大到小", "Size: largest first"),
    SMALLEST("文件大小：从小到大", "Size: smallest first");

    fun arrange(media: List<MediaItem>): List<MediaItem> {
        val comparator = when (this) {
            NEWEST -> compareByDescending<MediaItem> { it.modified }
            OLDEST -> compareBy<MediaItem> { it.modified }
            NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
            LARGEST -> compareByDescending<MediaItem> { it.size }
            SMALLEST -> compareBy<MediaItem> { it.size }
        }.thenBy { it.path }.thenBy { it.id }
        return media.sortedWith(comparator)
    }
}

/** Name/size sorting must not be regrouped by date, which would change the chosen order. */
fun galleryGroups(media: List<MediaItem>, byDate: Boolean): Map<java.time.LocalDate?, List<MediaItem>> =
    if (byDate) media.groupBy { it.date } else linkedMapOf(null to media)
