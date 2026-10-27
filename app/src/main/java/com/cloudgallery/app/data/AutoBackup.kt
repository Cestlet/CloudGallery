package com.cloudgallery.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.*
import com.cloudgallery.app.GalleryApplication
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

data class DeviceMedia(val uri: Uri, val name: String, val album: String, val bucket: String, val size: Long, val modified: Long)

fun mediaPermissions(videos: Boolean): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    listOfNotNull(Manifest.permission.READ_MEDIA_IMAGES, if (videos) Manifest.permission.READ_MEDIA_VIDEO else null).toTypedArray()
} else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

fun hasMediaPermission(context: Context, videos: Boolean) = mediaPermissions(videos).all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

fun deviceMedia(context: Context, videos: Boolean): List<DeviceMedia> {
    check(hasMediaPermission(context, videos)) { "请允许访问手机照片和视频" }
    return buildList {
        val collections = listOfNotNull(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            if (videos) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else null)
        collections.forEach { collection ->
            context.contentResolver.query(collection, arrayOf("_id", "_display_name", "bucket_display_name", "bucket_id", "_size", "date_modified"),
                "_size > 0", null, "date_modified ASC")?.use { cursor ->
                while (cursor.moveToNext()) add(DeviceMedia(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    cursor.getString(1) ?: "media", cursor.getString(2) ?: "其他", cursor.getString(3) ?: "other", cursor.getLong(4), cursor.getLong(5)))
            }
        }
    }
}

fun scheduleBackup(context: Context, settings: AppSettings, immediate: Boolean = true) {
    val manager = WorkManager.getInstance(context)
    if (!settings.backup) {
        manager.cancelUniqueWork("auto-backup-now")
        manager.cancelUniqueWork("auto-backup")
        return
    }
    val constraints = Constraints.Builder().setRequiredNetworkType(if (settings.backupMobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true).build()
    manager.enqueueUniquePeriodicWork("auto-backup", ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build())
    if (immediate) manager.enqueueUniqueWork("auto-backup-now", ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<AutoBackupWorker>().setConstraints(constraints).build())
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = (applicationContext as GalleryApplication).services
        graph.backupLock.lock()
        try {
            val settings = graph.preferences.state.value
            if (!settings.backup) return@withContext Result.success()
            if (!hasMediaPermission(applicationContext, settings.videos)) {
                graph.preferences.prefs.edit().putString("backupStatus", "需要照片访问权限，请在自动备份中重新授权").apply()
                return@withContext Result.failure()
            }
            val account = graph.store.loadBaidu() ?: return@withContext Result.failure()
            check(settings.destination.isNotBlank()) { "请选择备份目标文件夹" }
            val provider = graph.provider(account, "/")
            val ledger = applicationContext.getSharedPreferences("backup_ledger", Context.MODE_PRIVATE)
            
            var uploaded = 0
            for (item in deviceMedia(applicationContext, settings.videos)) {
                currentCoroutineContext().ensureActive()
                val active = graph.preferences.state.value
                if (active.backupConfig() != settings.backupConfig() || graph.store.loadBaidu()?.id != account.id) return@withContext Result.success()
                val network = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                if (!settings.backupMobile && network.isActiveNetworkMetered) return@withContext Result.retry()
                if (!settings.allAlbums && item.bucket !in settings.albums) continue
                val key = digest("${account.id}|${settings.destination}|${item.uri}|${item.size}|${item.modified}")
                if (ledger.getBoolean(key, false)) continue
                graph.preferences.prefs.edit().putString("backupStatus", "正在备份：${item.name}").apply()
                // Deterministic names make retry safe while preserving distinct equal-named local files.
                val name = item.name.substringBeforeLast('.', item.name).replace('/', '_') + "-${key.take(10)}" +
                    item.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                BaiduUploader(provider) { applicationContext.contentResolver.openInputStream(item.uri) ?: error("无法读取手机文件") }.upload(item.size, settings.destination.trimEnd('/') + "/" + name) {
                    currentCoroutineContext().ensureActive()
                    check(graph.preferences.state.value.backupConfig() == settings.backupConfig() && graph.store.loadBaidu()?.id == account.id) { "备份配置已改变" }
                    check(settings.backupMobile || !network.isActiveNetworkMetered) { "等待 Wi-Fi" }
                }
                check(ledger.edit().putBoolean(key, true).commit())
                uploaded++
                if (uploaded >= 30) return@withContext Result.retry()
            }
            graph.preferences.prefs.edit().putString("backupStatus", "备份已完成 · ${java.time.LocalDateTime.now().withNano(0)}").apply()
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            graph.preferences.prefs.edit().putString("backupStatus", com.cloudgallery.app.safeError(error)).apply()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally { graph.backupLock.unlock() }
    }
}

