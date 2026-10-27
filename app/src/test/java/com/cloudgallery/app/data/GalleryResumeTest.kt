package com.cloudgallery.app.data

import org.junit.Assert.*
import org.junit.Test

class GalleryResumeTest {
    @Test fun recentSuccessfulScanSurvivesRestartWithoutAnotherScan() {
        assertFalse(needsAutomaticScan(100_000, 159_999))
        assertTrue(needsAutomaticScan(100_000, 160_000))
        assertTrue(needsAutomaticScan(0, 10_000))
        assertTrue(needsAutomaticScan(100_000, 99_000))
    }
    @Test fun insertingPhotosBeforeAnchorKeepsTheViewedPhoto() {
        val restored = resolveGalleryPosition(GalleryPosition(1, 37, "photo-b"), listOf("new", "photo-a", "photo-b", "photo-c"))
        assertEquals(2, restored.index)
        assertEquals(37, restored.offset)
    }
    @Test fun removedAnchorAndShortenedGalleryClampToValidItem() {
        assertEquals(1, resolveGalleryPosition(GalleryPosition(100, -20, "deleted"), listOf("a", "b")).index)
        assertEquals(0, resolveGalleryPosition(GalleryPosition(100, -20, "deleted"), listOf("a", "b")).offset)
        assertEquals(GalleryPosition(), resolveGalleryPosition(GalleryPosition(100, 40, "deleted"), emptyList()))
    }
}
