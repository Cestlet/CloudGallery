package com.cloudgallery.app.data

import org.junit.Assert.*
import org.junit.Test

class GalleryImageFormatsTest {
    @Test fun includesHeifHeicAndExistingFormatsCaseInsensitively() {
        for (name in listOf("photo.heif", "photo.heic", "photo.HEIF", "photo.HeIc", "photo.jpg", "photo.JPEG", "photo.png", "photo.webp")) assertTrue(name, galleryImageFile(name))
    }
    @Test fun excludesOtherFilesAndMissingExtensions() {
        for (name in listOf("heif", "photo.heif.txt", "movie.mp4", "document.pdf")) assertFalse(name, galleryImageFile(name))
    }
}
