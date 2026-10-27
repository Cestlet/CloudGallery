package com.cloudgallery.app.data

internal fun galleryImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
