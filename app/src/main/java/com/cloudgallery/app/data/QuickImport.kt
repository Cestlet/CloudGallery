package com.cloudgallery.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.work.*
import com.cloudgallery.app.GalleryApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class QuickImportTask(val id: String, val account: String, val destination: String,
    val page: Int, val total: Int, val created: Long, val status: String, val detail: String,
    val link: String, val fileKey: String, val extension: String, val saveLocal: Boolean, val localUri: String)

internal class QuickImportStore(context: Context, databaseName: String = "quick_import.db") : SQLiteOpenHelper(context, databaseName, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, account TEXT NOT NULL, destination TEXT NOT NULL, page INTEGER NOT NULL, total INTEGER NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', link TEXT NOT NULL DEFAULT '', fileKey TEXT NOT NULL DEFAULT '', extension TEXT NOT NULL DEFAULT '', saveLocal INTEGER NOT NULL DEFAULT 0, localUri TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN saveLocal INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN localUri TEXT NOT NULL DEFAULT ''")
        }
    }
    fun create(account: String, destination: String, page: XhsImagePage, saveLocal: Boolean): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("tasks", null, ContentValues().apply {
            put("id", id); put("account", account); put("destination", normalizePath(destination))
            put("page", page.index); put("total", page.total); put("created", System.currentTimeMillis()); put("status", "capturing")
            put("saveLocal", if (saveLocal) 1 else 0)
        })
        return id
    }
    fun get(id: String) = query("id = ?", arrayOf(id)).firstOrNull()
    fun recent() = query(null, null, "50")
    fun pending() = query("status IN ('queued','resolving','downloading','saving','uploading','paused')", null)
    private fun query(where: String?, args: Array<String>?, limit: String? = null): List<QuickImportTask> =
        readableDatabase.query("tasks", null, where, args, null, null, "created DESC", limit).use { cursor ->
            buildList {
                fun text(key: String) = cursor.getString(cursor.getColumnIndexOrThrow(key))
                while (cursor.moveToNext()) add(QuickImportTask(text("id"), text("account"), text("destination"),
                    cursor.getInt(cursor.getColumnIndexOrThrow("page")), cursor.getInt(cursor.getColumnIndexOrThrow("total")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created")), text("status"), text("detail"), text("link"), text("fileKey"), text("extension"),
                    cursor.getInt(cursor.getColumnIndexOrThrow("saveLocal")) != 0, text("localUri")))
            }
        }
    fun update(id: String, status: String, detail: String = "", link: String? = null, fileKey: String? = null, extension: String? = null, localUri: String? = null, runningOnly: Boolean = false): Boolean {
        return writableDatabase.update("tasks", ContentValues().apply {
            put("status", status); put("detail", detail)
            link?.let { put("link", it) }; fileKey?.let { put("fileKey", it) }; extension?.let { put("extension", it) }
            localUri?.let { put("localUri", it) }
        }, if (runningOnly) "id = ? AND status NOT IN ('cancelled','completed','failed')" else "id = ?", arrayOf(id)) == 1
    }
    fun cancel(id: String): Boolean = writableDatabase.update("tasks", ContentValues().apply {
        put("status", "cancelled"); put("detail", "已取消"); put("link", "")
    }, "id = ? AND status != 'completed'", arrayOf(id)) == 1
    fun completed(key: String) = readableDatabase.rawQuery("SELECT 1 FROM tasks WHERE fileKey = ? AND status = 'completed' LIMIT 1", arrayOf(key)).use { it.moveToFirst() }
    fun failCapture(id: String, reason: String): Boolean = writableDatabase.update("tasks", ContentValues().apply {
        put("status", "failed"); put("detail", reason)
    }, "id = ? AND status = 'capturing'", arrayOf(id)) == 1
    fun stageCapture(id: String, stage: String) {
        writableDatabase.update("tasks", ContentValues().apply { put("detail", stage) },
            "id = ? AND status = 'capturing'", arrayOf(id))
    }
    fun queueCapture(id: String, link: String): Boolean = writableDatabase.update("tasks", ContentValues().apply {
        put("status", "queued"); put("detail", "等待网络后导入"); put("link", link)
    }, "id = ? AND status = 'capturing'", arrayOf(id)) == 1
    fun savedUri(key: String) = readableDatabase.rawQuery("SELECT localUri FROM tasks WHERE fileKey = ? AND localUri != '' ORDER BY created DESC LIMIT 1", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else "" }
    fun expireCaptures() {
        writableDatabase.execSQL("UPDATE tasks SET status = 'failed', detail = CASE WHEN detail = '' THEN '取链接流程未完成（进程或服务可能被中断），请重新按双音量键' ELSE detail || '；流程中断，请重新按双音量键' END WHERE status = 'capturing' AND created < ?", arrayOf(System.currentTimeMillis() - 30_000))
    }
}

internal fun quickFeedback(context: Context, success: Boolean, accepted: Boolean = false) {
    val graph = (context.applicationContext as GalleryApplication).services
    if (!graph.preferences.state.value.quickVibration) return
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31)
        context.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
        else context.getSystemService(Vibrator::class.java)
    if (!vibrator.hasVibrator()) return
    val effect = if (success) VibrationEffect.createOneShot(if (accepted) 12 else 30, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createWaveform(longArrayOf(0, 70, 100, 70), -1)
    try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, android.os.VibrationAttributes.Builder()
                .setUsage(android.os.VibrationAttributes.USAGE_NOTIFICATION).build())
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
    } catch (error: SecurityException) {
        android.util.Log.w("QuickImport", "Vibration permission unavailable", error)
    }
}

internal fun scheduleQuickImport(context: Context, id: String, replace: Boolean = false): Operation? {
    val graph = (context.applicationContext as GalleryApplication).services
    if (!graph.preferences.state.value.quickImport) return null
    val request = OneTimeWorkRequestBuilder<QuickImportWorker>().setInputData(workDataOf("task" to id))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(if (graph.preferences.state.value.quickMobile) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
        .build()
    return WorkManager.getInstance(context).enqueueUniqueWork("quick-import-$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
}

internal fun resumeQuickImports(context: Context, replace: Boolean = false) {
    val graph = (context.applicationContext as GalleryApplication).services
    graph.quickImports.expireCaptures()
    graph.quickImports.pending().forEach {
        if (graph.preferences.state.value.quickImport) scheduleQuickImport(context, it.id, replace)
        else {
            WorkManager.getInstance(context).cancelUniqueWork("quick-import-${it.id}")
            graph.quickImports.update(it.id, "paused", "快速导图已关闭", runningOnly = true)
        }
    }
}

class QuickImportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = (applicationContext as GalleryApplication).services
        val id = inputData.getString("task") ?: return@withContext Result.failure()
        graph.quickCoordinator.transfer(id) {
            val store = graph.quickImports
            val task = store.get(id) ?: return@transfer Result.failure()
            if (task.status in setOf("cancelled", "failed")) return@transfer Result.failure()
            if (task.status == "completed") return@transfer Result.success()
            fun update(status: String, detail: String = "", link: String? = null, fileKey: String? = null, extension: String? = null, localUri: String? = null): Boolean =
                store.update(id, status, detail, link, fileKey, extension, localUri, runningOnly = true)
            if (!graph.preferences.state.value.quickImport) {
                update("paused", "快速导图已关闭"); return@transfer Result.success()
            }
            val directory = File(applicationContext.filesDir, "quick-import").apply { mkdirs() }
            val partial = File(directory, "$id.part")
            val file = File(directory, "$id.image")
            // Disposable retry downloads have a bounded budget; records and local album copies stay.
            graph.quickCoordinator.cleanupLock.withLock {
                val activeIds = graph.quickCoordinator.activeTasks()
                var scratchBytes = directory.listFiles().orEmpty().sumOf { it.length() }
                directory.listFiles().orEmpty().filter {
                    it != file && it != partial && it.name.substringBefore('.') !in activeIds
                }.sortedBy { it.lastModified() }.forEach {
                    if (scratchBytes > 64L * 1024 * 1024) { val size = it.length(); if (it.delete()) scratchBytes -= size }
                }
            }
            try {
                val account = graph.store.loadBaidu() ?: throw QuickImportError("请重新连接百度网盘后重试")
                if (account.id != task.account) throw QuickImportError("网盘账户已改变，请切回原账户后重试")
                var checkedAt = Long.MIN_VALUE
                fun active(force: Boolean = false) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!force && checkedAt != Long.MIN_VALUE && now - checkedAt < 500) return
                    checkedAt = now
                    if (store.get(id)?.status == "cancelled") throw kotlinx.coroutines.CancellationException("Import cancelled")
                    if (!graph.preferences.state.value.quickImport) throw QuickImportError("快速导图已关闭")
                    if (graph.store.loadBaidu()?.id != task.account) throw QuickImportError("网盘账户已改变，请重试")
                    val network = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    if (!graph.preferences.state.value.quickMobile && network.isActiveNetworkMetered) throw java.io.IOException("Waiting for Wi-Fi")
                }
                currentCoroutineContext().ensureActive(); active()
                var key = task.fileKey
                var extension = task.extension
                if (!file.exists() || key.isBlank() || extension.isBlank()) {
                    update("resolving", "正在解析当前图片")
                    val source = if (com.cloudgallery.app.BuildConfig.MOCK_BAIDU && task.link.startsWith(com.cloudgallery.app.BuildConfig.BAIDU_API + "discovery/item/"))
                        XhsSource(fixture = com.cloudgallery.app.BuildConfig.BAIDU_API.toHttpUrl()) else XhsSource()
                    val image = source.resolve(task.link, task.page, task.total)
                    key = digest("${task.account}|${task.destination}|${image.noteId}|${image.fileId}")
                    val existingLocal = if (task.saveLocal) store.savedUri(key) else ""
                    val localExists = existingLocal.isNotBlank() && runCatching {
                        applicationContext.contentResolver.openFileDescriptor(android.net.Uri.parse(existingLocal), "r")?.use { true } == true
                    }.getOrDefault(false)
                    if (store.completed(key) && (!task.saveLocal || localExists)) {
                        if (!update("completed", if (localExists) "这张图片已导入并保存到手机相册" else "这张图片已导入", link = "", fileKey = key, localUri = existingLocal))
                            throw kotlinx.coroutines.CancellationException("Task ended before completion")
                        quickFeedback(applicationContext, true); return@transfer Result.success()
                    }
                    update("downloading", "正在下载高清图片", fileKey = key)
                    var progressAt = 0L
                    extension = downloadXhsImage(source, image, partial, { active() }, { received, total ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - progressAt >= 1000) {
                            val doneMb = String.format(java.util.Locale.ROOT, "%.1f", received / 1048576.0)
                            val sizeMb = if (total > 0) String.format(java.util.Locale.ROOT, "%.1f", total / 1048576.0) else "?"
                            update("downloading", "正在下载高清图片 · $doneMb / $sizeMb MB")
                            progressAt = now
                        }
                    }) { decoded, candidate ->
                        graph.preferences.prefs.edit().putString("quickImageDiagnostic",
                            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}, app ${com.cloudgallery.app.BuildConfig.VERSION_NAME}\n" +
                            "time=${System.currentTimeMillis()}, page=${task.page}/${task.total}, source=$candidate, expected=${image.width}x${image.height}, actual=${decoded.width}x${decoded.height}, mime=${decoded.mime}, orientation=${decoded.orientation}, bytes=${partial.length()}").apply()
                    }
                    check(partial.renameTo(file)) { "Unable to save image" }
                    update("uploading", "正在上传网盘", fileKey = key, extension = extension)
                }
                graph.quickCoordinator.publish(key) {
                active(force = true); currentCoroutineContext().ensureActive()
                if (task.saveLocal) {
                    val saved = task.localUri.ifBlank { store.savedUri(key) }
                    val exists = saved.isNotBlank() && runCatching {
                        applicationContext.contentResolver.openFileDescriptor(android.net.Uri.parse(saved), "r")?.use { true } == true
                    }.getOrDefault(false)
                    val local = if (exists) saved else {
                        update("saving", "正在保存到手机相册")
                        saveQuickImage(applicationContext, file, "xhs-${key.take(24)}.$extension", extension) {
                            currentCoroutineContext().ensureActive(); active()
                        }.toString()
                    }
                    update("uploading", "正在上传网盘", localUri = local)
                }
                val provider = graph.provider(account, "/")
                val target = task.destination.trimEnd('/') + "/xhs-${key.take(24)}.$extension"
                update("uploading", "正在上传网盘")
                if (!store.completed(key)) {
                    BaiduUploader(provider) { file.inputStream() }.upload(file.length(), target) {
                        currentCoroutineContext().ensureActive(); active(force = true)
                    }
                }
                active(force = true)
                if (!update("completed", if (task.saveLocal) "已上传网盘并保存到手机相册" else "已上传网盘", link = ""))
                    throw kotlinx.coroutines.CancellationException("Task ended before completion")
                file.delete()
                quickFeedback(applicationContext, true)
                // A successful upload asks for a scan; the existing index stays visible meanwhile.
                if (account.root != null && graph.store.loadBaidu()?.root == account.root &&
                    (account.root == "/" || task.destination == account.root || task.destination.startsWith("${account.root.trimEnd('/')}/"))) {
                    WorkManager.getInstance(applicationContext).enqueueUniqueWork("quick-import-refresh", ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<com.cloudgallery.app.GallerySyncWorker>().build())
                }
                Result.success()
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                partial.delete()
                if (store.get(id)?.status == "cancelled") file.delete()
                throw cancel
            }
            catch (error: Exception) {
                android.util.Log.w("QuickImport", "Import failed (${error.javaClass.simpleName}; cause=${error.cause?.javaClass?.simpleName})")
                partial.delete()
                val disabled = !graph.preferences.state.value.quickImport
                val retry = error is java.io.IOException && runAttemptCount < 4 && !disabled
                val detail = when {
                    disabled -> "快速导图已关闭"
                    error is QuickImportError -> error.reason
                    retry -> "等待网络后重试"
                    else -> "导入失败，请检查网络和网盘授权后重试"
                }
                val reported = update(if (disabled) "paused" else if (retry) "queued" else "failed", detail)
                if (reported && !retry && !disabled) quickFeedback(applicationContext, false)
                if (retry) Result.retry() else Result.failure()
            }
        }
    }
}
