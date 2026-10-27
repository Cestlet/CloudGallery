package com.cloudgallery.app.data

import java.time.LocalDate

data class MediaItem(val id: String, val name: String, val album: String, val date: LocalDate, val scene: Int = 0,
    val path: String = "", val thumb: String = "", val version: String = "", val remote: Boolean = false,
    val remoteId: String = "", val modified: Long = date.toEpochDay() * 86400, val size: Long = 0)

interface GalleryRepository { fun media(): List<MediaItem> }

/** Local deterministic fixtures. No network or storage credentials are required. */
class DemoRepository : GalleryRepository {
    override fun media(): List<MediaItem> = List(72) { index ->
        val album = listOf("山间旅行", "海边日记", "城市漫步")[index % 3]
        MediaItem("demo-$index", "IMG_${1000 + index}.jpg", album,
            LocalDate.of(2026, 9, 26).minusDays((index / 9).toLong()), index % 6)
    }
}
