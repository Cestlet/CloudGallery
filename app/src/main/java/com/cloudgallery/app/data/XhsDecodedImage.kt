package com.cloudgallery.app.data

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.os.Build
import java.io.File

internal data class XhsDecodedImage(val width: Int, val height: Int, val mime: String?, val orientation: Int)

internal fun readXhsImageInfo(file: File): XhsDecodedImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val orientation = runCatching { ExifInterface(file.absolutePath)
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
    if (bounds.outWidth > 0 && bounds.outHeight > 0)
        return XhsDecodedImage(bounds.outWidth, bounds.outHeight, bounds.outMimeType, orientation)
    if (Build.VERSION.SDK_INT >= 28) {
        var info: XhsDecodedImage? = null
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, header, _ ->
                check(header.size.width > 0 && header.size.height > 0 && header.size.width.toLong() * header.size.height <= 150_000_000)
                info = XhsDecodedImage(header.size.width, header.size.height, header.mimeType, 1)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(1, 1)
            }.recycle()
        }.onFailure { info = null }
        info?.let { return it }
    }
    return XhsDecodedImage(bounds.outWidth, bounds.outHeight, bounds.outMimeType, orientation)
}
