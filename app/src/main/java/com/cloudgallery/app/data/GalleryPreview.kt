package com.cloudgallery.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import java.io.File

/** Bounded, orientation-aware previews, including HEIF codecs exposed through ImageDecoder. */
internal fun decodeGalleryPreview(file: File, maxEdge: Int = 768): Bitmap {
    require(maxEdge in 1..2048)
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width; val height = info.size.height
            check(width > 0 && height > 0 && width.toLong() * height <= 150_000_000) { "图片尺寸不受支持" }
            val ratio = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
            decoder.setTargetSize(maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val info = readXhsImageInfo(file)
    check(info.width > 0 && info.height > 0 && info.width.toLong() * info.height <= 150_000_000) { "无法生成图片预览" }
    var sample = 1
    while (maxOf(info.width, info.height) / sample > maxEdge) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("无法生成图片预览")
    val matrix = Matrix().apply {
        when (info.orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }
            8 -> setRotate(270f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
        finally { bitmap.recycle() }
}
