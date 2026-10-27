package com.cloudgallery.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Streams 4 MB blocks, never loads an entire video into memory. */
internal class BaiduUploader(private val provider: com.cloudgallery.app.data.baidu.BaiduProvider, private val openInput: () -> java.io.InputStream) {
    private val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).followRedirects(false).build()
    private suspend fun response(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: java.io.IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        check(it.isSuccessful) { "备份网络请求失败（HTTP ${it.code}）" }
                        JSONObject(it.body?.string() ?: error("备份返回空响应")).also { json ->
                            val code = json.optInt("errno", json.optInt("error_code", 0))
                            if (code != 0) throw com.cloudgallery.app.data.baidu.BaiduApiException(code)
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
            }
        })
    }
    private suspend fun post(method: String, fields: Map<String, String>): JSONObject {
        val url = provider.uploadApi(method)
        return response(Request.Builder().url(url).post(FormBody.Builder().apply { fields.forEach { (k,v) -> add(k,v) } }.build()).build())
    }
    private suspend fun blocks(action: suspend (Int, ByteArray) -> Unit) {
        openInput().use { stream ->
            var index = 0
            while (true) {
                val buffer = ByteArray(4 * 1024 * 1024)
                var count = 0
                while (count < buffer.size) { val n = stream.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                if (count == 0) break
                action(index++, if (count == buffer.size) buffer else buffer.copyOf(count))
            }
        }
    }
    suspend fun upload(size: Long, path: String, checkActive: suspend () -> Unit) {
        val hashes = mutableListOf<String>()
        blocks { _, bytes -> checkActive(); hashes.add(MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }) }
        checkActive()
        val fields = mapOf("path" to normalizePath(path), "size" to size.toString(), "isdir" to "0",
            "block_list" to JSONArray(hashes).toString(), "rtype" to "0")
        val pre = post("precreate", fields + ("autoinit" to "1"))
        if (pre.optInt("return_type") == 2) return
        val uploadId = pre.getString("uploadid")
        check(uploadId.isNotBlank()) { "备份响应缺少上传标识" }
        val needed = pre.optJSONArray("block_list")?.let { array -> (0 until array.length()).map { array.getInt(it) }.toSet() }
            ?: hashes.indices.toSet()
        // Re-open the content URI for each block so cancellation/network policy is checked between requests.
        openInput().use { stream ->
            hashes.indices.forEach { index ->
                checkActive()
                val bytes = ByteArray(minOf(4L * 1024 * 1024, size - index * 4L * 1024 * 1024).toInt())
                var read = 0
                while (read < bytes.size) { val n = stream.read(bytes, read, bytes.size-read); check(n > 0) { "手机文件已改变，请重试" }; read += n }
                check(MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } == hashes[index]) { "手机文件已改变，请重试" }
                if (index in needed) {
                    val url = provider.uploadSlice(path, uploadId, index)
                    val result = response(Request.Builder().url(url).post(MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", "block", bytes.toRequestBody()).build()).build())
                    check(result.getString("md5") == hashes[index]) { "上传分片校验失败" }
                }
            }
        }
        checkActive()
        val created = post("create", fields + ("uploadid" to uploadId))
        check(created.getString("path") == normalizePath(path) && created.getLong("size") == size) { "上传结果与手机文件不一致" }
    }
}
