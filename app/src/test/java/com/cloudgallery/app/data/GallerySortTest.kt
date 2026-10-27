package com.cloudgallery.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GallerySortTest {
    private val photos = listOf(
        MediaItem("a", "z.jpg", "/A", LocalDate.of(2026, 9, 26), path = "/A/z.jpg", modified = 100, size = 30),
        MediaItem("b", "A.jpg", "/B", LocalDate.of(2026, 9, 27), path = "/B/A.jpg", modified = 300, size = 10),
        MediaItem("c", "b.jpg", "/A", LocalDate.of(2026, 9, 27), path = "/A/b.jpg", modified = 200, size = 20))
    @Test fun timeSortingUsesSecondsWithinTheSameDay() {
        assertEquals(listOf("b", "c", "a"), GallerySort.NEWEST.arrange(photos).map { it.id })
        assertEquals(listOf("a", "c", "b"), GallerySort.OLDEST.arrange(photos).map { it.id })
    }
    @Test fun nameAndSizeWorkAcrossDatesAndFolders() {
        for (sort in listOf(GallerySort.NAME_ASC, GallerySort.SMALLEST)) {
            val ordered = sort.arrange(photos)
            assertEquals(listOf("b", "c", "a"), ordered.map { it.id })
            assertEquals(ordered, galleryGroups(ordered, sort.byDate).values.flatten())
            assertEquals(ordered.map { it.id }, galleryItemKeys(ordered, sort.byDate))
        }
        assertEquals(listOf("a", "c", "b"), GallerySort.NAME_DESC.arrange(photos).map { it.id })
        assertEquals(listOf("a", "c", "b"), GallerySort.LARGEST.arrange(photos).map { it.id })
    }
    @Test fun equalValuesHaveStablePathOrder() {
        val same = photos.map { it.copy(size = 1, modified = 1, name = "same.jpg") }
        for (sort in GallerySort.entries) assertEquals(listOf("c", "a", "b"), sort.arrange(same.reversed()).map { it.id })
    }
    @Test fun dateHeadersAndEmptyListsMatchRendering() {
        val ordered = GallerySort.NEWEST.arrange(photos)
        assertEquals(listOf("date-2026-09-27", "b", "c", "date-2026-09-26", "a"), galleryItemKeys(ordered, true))
        assertTrue(galleryItemKeys(emptyList(), false).isEmpty())
    }
    @Test fun uploadNamesRemainStableOnRetryAndKeepFormat() {
        val first = uploadFileName("测试.HEIF", "batch-one", 0)
        assertTrue(first.endsWith(".HEIF"))
        assertEquals(first, uploadFileName("测试.HEIF", "batch-one", 0))
        assertNotEquals(first, uploadFileName("测试.HEIF", "batch-two", 0))
        assertNotEquals(first, uploadFileName("测试.HEIF", "batch-one", 1))
    }
}
