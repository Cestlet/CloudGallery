package com.cloudgallery.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class PhotoCache(context: Context, private val preferences: AppPreferences = AppPreferences(context)) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val directory = File(context.cacheDir, "photos").apply { mkdirs() }
    private val dimensions = context.getSharedPreferences("photo_dimensions", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 12 })
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private val visibleSlots = Semaphore(6)
    private val prefetchSlots = Semaphore(2)
    private val warmupSlots = Semaphore(1)
    private val originalSlots = Semaphore(2)
    private val locks = ImageKeyLocks()
    private val maxBytes = 256L * 1024 * 1024
    private var accountedBytes: Long? = null
    @Volatile private var generation = 0L
    private fun key(photo: MediaItem, full: Boolean) = digest("${photo.id}|${photo.version}|${if (full) "full" else "thumb"}")
    fun cached(photo: MediaItem, full: Boolean): File? = File(directory, key(photo, full)).takeIf { it.exists() }
    fun canWarmThumbnails() = !connectivity.isActiveNetworkMetered
    fun aspectRatio(photo: MediaItem): Float = dimensions.getFloat(key(photo, false), 1f)
    fun rememberAspectRatio(photo: MediaItem, ratio: Float) {
        if (!ratio.isFinite() || ratio <= 0 || kotlin.math.abs(aspectRatio(photo) - ratio) < .0001f) return
        dimensions.edit().putFloat(key(photo, false), ratio).apply()
    }

    suspend fun load(provider: StorageProvider, photo: MediaItem, full: Boolean,
        allowOriginalFallback: Boolean = true, prefetch: Boolean = false, warmup: Boolean = false): File? = withContext(Dispatchers.IO) {
        if (warmup && !canWarmThumbnails()) return@withContext null
        val cacheKey = key(photo, full)
        val expectedGeneration = generation
        val target = File(directory, cacheKey)
        if (target.exists()) { target.setLastModified(System.currentTimeMillis()); return@withContext target }
        check(!full || preferences.state.value.originalMobile || !connectivity.isActiveNetworkMetered) { "原始内容仅在 Wi-Fi 下加载，可在设置 → 网络中允许移动数据" }
        val transfer: suspend () -> File? = {
        locks.withKey(cacheKey) {
            if (target.exists()) { target.setLastModified(System.currentTimeMillis()); return@withKey target }
            if (!full && photo.thumb.isBlank()) {
                if (!allowOriginalFallback) return@withKey null
                if (connectivity.isActiveNetworkMetered && cached(photo, true) == null) return@withKey null
                val original = load(provider, photo, true) ?: return@withKey null
                val bitmap = decodeGalleryPreview(original)
                val temp = File.createTempFile("preview-", ".part", directory)
                try {
                    temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "预览缓存写入失败" } }
                    coroutineContext.ensureActive()
                    commit(temp, target, expectedGeneration)
                    target
                } finally { bitmap.recycle(); temp.delete() }
            } else {
                    val temp = File.createTempFile("download-", ".part", directory)
                    try {
                        val url = provider.absolute(if (full) provider.original(photo.path, photo.remoteId) else photo.thumb)
                        val request = Request.Builder().url(url).apply {
                            provider.downloadHeaders(url).forEach { (name, value) -> header(name, value) }
                        }.build()
                        client.newCall(request).downloadTo(temp, if (full) 64L * 1024 * 1024 else 12L * 1024 * 1024)
                        coroutineContext.ensureActive()
                        commit(temp, target, expectedGeneration)
                        target
                    } finally { temp.delete() }
            }
        }
        }
        // Queued prefetches do not own a key until they have a transfer slot.
        // A visible request may therefore overtake a queued prefetch for the same image.
        if (!full && photo.thumb.isBlank()) transfer()
        else {
            val slots = if (full) originalSlots else if (warmup) warmupSlots else if (prefetch) prefetchSlots else visibleSlots
            slots.withPermit { transfer() }
        }
    }
    @Synchronized fun clear(): Long {
        generation++
        client.dispatcher.cancelAll()
        var cleared = 0L
        directory.listFiles()?.forEach { file ->
            val size = file.length(); if (file.delete()) cleared += size
        }
        accountedBytes = null
        return cleared
    }
    fun sizeBytes(): Long = directory.listFiles()?.filter { !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L
    @Synchronized private fun commit(temp: File, target: File, expectedGeneration: Long) {
        check(generation == expectedGeneration) { "缓存已清除，请重新加载" }
        var total = accountedBytes ?: (directory.listFiles()?.filter { !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L)
        if (!target.exists()) {
            check(temp.renameTo(target)) { "缓存写入失败" }
            total += target.length()
        }
        if (total > maxBytes) {
            // Reclaim in batches instead of enumerating and sorting every cache file per download.
            directory.listFiles()?.filter { it != target && !it.name.endsWith(".part") }
                ?.sortedBy { it.lastModified() }?.forEach { file ->
                    if (total > maxBytes * 9 / 10) { val size = file.length(); if (file.delete()) total -= size }
                }
        }
        accountedBytes = total
    }
}
