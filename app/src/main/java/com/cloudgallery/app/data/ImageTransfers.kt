package com.cloudgallery.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Serializes requests for one cache key without retaining locks for the entire library. */
internal class ImageKeyLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    suspend fun <T> withKey(key: String, action: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try { return entry.mutex.withLock { action() } }
        finally { synchronized(entries) { if (--entry.users == 0) entries.remove(key) } }
    }
}

/** Cancellation closes the socket even while waiting for headers or a slow response body. */
internal suspend fun Call.downloadTo(file: File, limit: Long) = withContext(Dispatchers.IO) {
    val call = this@downloadTo
    suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                try {
                    response.use {
                        check(it.isSuccessful) { "图片加载失败（HTTP ${it.code}）" }
                        val body = it.body ?: error("图片响应为空")
                        check(body.contentLength() <= limit) { "图片超过单次下载限制" }
                        continuation.context.ensureActive()
                        body.byteStream().use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var received = 0L
                            while (true) {
                                continuation.context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                check(received <= limit) { "图片超过单次下载限制" }
                                output.write(buffer, 0, count)
                            }
                            check(received > 0) { "图片响应为空" }
                        } }
                    }
                    continuation.resumeWith(Result.success(Unit))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    if (!continuation.isActive && call.isCanceled()) file.delete()
                }
            }
        })
    }
}
