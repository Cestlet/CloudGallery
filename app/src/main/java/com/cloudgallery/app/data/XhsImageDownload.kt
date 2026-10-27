package com.cloudgallery.app.data

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Tries only this image's declared URLs, and never accepts a thumbnail as the original. */
internal suspend fun downloadXhsImage(source: XhsSource, image: ResolvedXhsImage, target: File,
    active: () -> Unit, progress: (Long, Long) -> Unit,
    diagnostic: (XhsDecodedImage, Int) -> Unit): String {
    var failure: Exception? = null
    for ((index, url) in (listOf(image.url) + image.alternatives).distinct().withIndex()) {
        currentCoroutineContext().ensureActive()
        active()
        try {
            source.download(url, target, active, progress)
            val decoded = readXhsImageInfo(target)
            diagnostic(decoded, index + 1)
            if (decoded.width <= 0 || decoded.height <= 0)
                throw QuickImportError("当前设备无法读取图片格式或尺寸，请复制导图诊断")
            if (!acceptableXhsSourceDimensions(decoded.width, decoded.height, image.width, image.height, decoded.orientation, directSource = index == 0))
                throw QuickImportError("图源尺寸校验失败（取得 ${decoded.width}×${decoded.height}，预期至少 ${image.width}×${image.height}），暂时无法导入")
            return when (decoded.mime) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/heif" -> "heif"; "image/heic" -> "heic"
                else -> throw QuickImportError("图片格式不受当前设备支持")
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { target.delete(); failure = error }
    }
    throw failure ?: QuickImportError("无法取得完整尺寸图片")
}
