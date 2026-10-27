package com.cloudgallery.app.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

internal fun canSaveQuickImage(context: Context) = Build.VERSION.SDK_INT >= 29 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** Publishes an app-owned image; an incomplete MediaStore item is removed on failure. */
@Suppress("DEPRECATION")
internal suspend fun saveQuickImage(context: Context, source: File, name: String, extension: String,
    active: suspend () -> Unit): Uri {
    if (!canSaveQuickImage(context)) throw QuickImportError("请允许保存到手机相册后重试")
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, when (extension) {
            "jpg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "heif" -> "image/heif"; "heic" -> "image/heic"; else -> throw QuickImportError("图片格式不受支持")
        })
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixNexus Gallary")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PixNexus Gallary")
            if (!directory.exists() && !directory.mkdirs()) throw QuickImportError("无法创建手机相册文件夹")
            val target = File(directory, name).let {
                if (it.exists()) File(directory, "${name.substringBeforeLast('.')}-${java.util.UUID.randomUUID().toString().take(8)}.$extension") else it
            }
            put(MediaStore.Images.Media.DATA, target.absolutePath)
        }
    }
    val resolver = context.contentResolver
    val uri = try { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }
        catch (_: SecurityException) { throw QuickImportError("请允许保存到手机相册后重试") }
        ?: throw QuickImportError("无法写入手机相册")
    try {
        source.inputStream().use { input ->
            (resolver.openOutputStream(uri) ?: throw QuickImportError("无法写入手机相册")).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    active()
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        val ready = ContentValues().apply {
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 0)
            else put(MediaStore.Images.Media.SIZE, source.length())
        }
        if (resolver.update(uri, ready, null, null) != 1) throw QuickImportError("手机相册保存未完成")
        return uri
    } catch (error: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
}
