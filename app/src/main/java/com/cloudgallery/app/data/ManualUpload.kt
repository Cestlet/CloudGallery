package com.cloudgallery.app.data

import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.work.*
import com.cloudgallery.app.GalleryApplication
import com.cloudgallery.app.safeError
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun uploadManifest(context: Context, id: String): File {
    require(id.matches(Regex("[a-f0-9-]{36}")))
    return File(context.filesDir, "manual-uploads/$id.json").also { it.parentFile!!.mkdirs() }
}

internal fun writeUploadManifest(file: File, json: JSONObject) {
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
    catch (error: Exception) { atomic.failWrite(output); throw error }
}

internal fun scheduleManualUpload(context: Context, id: String, mobile: Boolean) {
    val request = OneTimeWorkRequestBuilder<ManualUploadWorker>()
        .setInputData(workDataOf("batch" to id))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
        .addTag("manual-upload-$id").build()
    WorkManager.getInstance(context).enqueueUniqueWork("manual-upload-$id", ExistingWorkPolicy.REPLACE, request)
}

/** A persisted batch survives navigation/process death; completed items are skipped on retry. */
class ManualUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = (applicationContext as GalleryApplication).services
        val id = inputData.getString("batch") ?: return@withContext Result.failure()
        val manifest = uploadManifest(applicationContext, id)
        graph.manualUploadLock.withLock {
            var json: JSONObject? = null
            var staged: File? = null
            try {
                json = JSONObject(AtomicFile(manifest).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                val batch = json
                val account = graph.store.loadBaidu() ?: error("请先连接百度网盘")
                check(account.id == batch.getString("account")) { "账户已改变，请重新选择上传图片" }
                val items = batch.getJSONArray("items")
                val network = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val provider = graph.provider(account, "/")
                val mobile = batch.getBoolean("mobile")
                suspend fun checkActive() {
                    currentCoroutineContext().ensureActive()
                    check(graph.store.loadBaidu()?.id == account.id) { "账户已改变，已停止上传" }
                    if (!mobile && network.isActiveNetworkMetered) throw java.io.IOException("等待 Wi-Fi 后继续上传")
                }
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    if (item.optBoolean("done")) continue
                    checkActive()
                    setProgress(workDataOf("done" to completedUploads(batch), "total" to items.length(), "name" to item.getString("name")))
                    val file = File(applicationContext.filesDir, "manual-uploads/$id-$index.image")
                    staged = file
                    if (!file.isFile) {
                        val temp = File(file.path + ".part")
                        try {
                            applicationContext.contentResolver.openInputStream(Uri.parse(item.getString("uri")))?.use { input ->
                                temp.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                            } ?: error("无法读取所选图片，请重新选择")
                            check(temp.length() > 0 && temp.renameTo(file)) { "无法准备上传图片" }
                        } finally { temp.delete() }
                    }
                    BaiduUploader(provider) { file.inputStream() }.upload(file.length(), item.getString("path")) { checkActive() }
                    item.put("done", true)
                    writeUploadManifest(manifest, batch)
                    file.delete()
                    setProgress(workDataOf("done" to completedUploads(batch), "total" to items.length()))
                }
                val result = workDataOf("done" to items.length(), "total" to items.length())
                // Refresh the index using the same network policy as normal gallery synchronization.
                WorkManager.getInstance(applicationContext).enqueue(OneTimeWorkRequestBuilder<com.cloudgallery.app.GallerySyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (graph.preferences.state.value.syncMobile) NetworkType.CONNECTED else NetworkType.UNMETERED).build()).build())
                manifest.delete()
                releaseUnusedUploadGrants(applicationContext, batch)
                Result.success(result)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val output = workDataOf("error" to safeError(error), "done" to (json?.let(::completedUploads) ?: 0),
                    "total" to (json?.getJSONArray("items")?.length() ?: 0))
                setProgress(output)
                if (error is java.io.IOException && runAttemptCount < 3) Result.retry() else Result.failure(output)
            } finally { staged?.delete() }
        }
    }
}

internal fun completedUploads(batch: JSONObject): Int = batch.getJSONArray("items").let { items ->
    (0 until items.length()).count { items.getJSONObject(it).optBoolean("done") }
}

internal fun selectedUploadName(context: Context, uri: Uri): String {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }.orEmpty()
    val extension = when (context.contentResolver.getType(uri)) {
        "image/png" -> "png"; "image/webp" -> "webp"; "image/heif" -> "heif"; "image/heic" -> "heic"; "image/jpeg" -> "jpg"; else -> ""
    }
    val cleaned = name.replace(Regex("[\\\\/\u0000-\u001f]"), "_").take(180)
    check(galleryImageFile(cleaned) || extension.isNotEmpty()) { "暂不支持此图片格式：$cleaned" }
    return if (galleryImageFile(cleaned)) cleaned else "${cleaned.ifBlank { "photo" }}.$extension"
}

internal fun uploadFileName(name: String, batch: String, index: Int): String =
    "${name.substringBeforeLast('.').take(50)}-${batch}-$index.${name.substringAfterLast('.')}"

internal fun releaseUnusedUploadGrants(context: Context, batch: JSONObject) {
    val retained = File(context.filesDir, "manual-uploads").listFiles().orEmpty().filter { it.extension == "json" }
        .flatMap { file -> runCatching {
            val items = JSONObject(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) }).getJSONArray("items")
            (0 until items.length()).map { items.getJSONObject(it).getString("uri") }
        }.getOrDefault(emptyList()) }.toSet()
    val items: JSONArray = batch.getJSONArray("items")
    for (index in 0 until items.length()) {
        val uri = items.getJSONObject(index).getString("uri")
        if (uri !in retained) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
