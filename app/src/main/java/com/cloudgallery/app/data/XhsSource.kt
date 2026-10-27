package com.cloudgallery.app.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Call
import okhttp3.Callback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class QuickImportError(val reason: String) : Exception(reason)
internal data class ResolvedXhsImage(val noteId: String, val fileId: String, val url: HttpUrl,
    val width: Int, val height: Int, val alternatives: List<HttpUrl> = emptyList())

/** Only public share pages, no app cookies, signature generation or remote JavaScript execution. */
internal class XhsSource(
    private val client: OkHttpClient = sharedClient,
    private val fixture: HttpUrl? = null
) {
    companion object {
        // Reuse DNS/connection/TLS state across imports instead of a client per task.
        private val sharedClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).build()
        const val MAX_IMAGE = 64L * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private val pageHosts = setOf("www.xiaohongshu.com", "xiaohongshu.com", "xhslink.com", "xhslink.cn")
        fun shareUrl(text: String): String? = Regex("https?://(?:xhslink\\.(?:com|cn)|(?:www\\.)?xiaohongshu\\.com)/[^\\s<>]+")
            .find(text)?.value?.trimEnd('。', '，', ')', '）')
    }
    private fun checked(url: HttpUrl, image: Boolean): HttpUrl {
        if (fixture != null && url.host == fixture.host && url.port == fixture.port) return url
        val hostAllowed = if (image) url.host == "ci.xiaohongshu.com" || url.host.endsWith(".xhscdn.com") else url.host in pageHosts
        if (!hostAllowed || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443))
            throw QuickImportError("图片来源地址不受支持")
        return url.newBuilder().scheme("https").port(443).fragment(null).build()
    }
    private data class Fetched<T>(val redirect: HttpUrl? = null, val value: T? = null)
    private suspend fun <T : Any> fetch(url: HttpUrl, image: Boolean, read: (Response) -> T): T {
        var target = checked(url, image)
        repeat(7) {
            val current = target
            val call = client.newCall(Request.Builder().url(current).header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.xiaohongshu.com/").build())
            // The continuation owns the entire body read, so cancellation also
            // closes slow downloads after their response headers have arrived.
            val fetched = suspendCancellableCoroutine<Fetched<T>> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: java.io.IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!continuation.isActive) return
                                val value = if (it.code in setOf(301, 302, 303, 307, 308)) {
                                    Fetched<T>(redirect = checked(it.header("Location")?.let(current::resolve)
                                        ?: throw QuickImportError("分享链接无法解析"), image))
                                } else {
                                    if (it.code == 408 || it.code == 429 || it.code >= 500) throw java.io.IOException("Source HTTP ${it.code}")
                                    if (!it.isSuccessful) throw QuickImportError("图片来源请求失败（HTTP ${it.code}）")
                                    Fetched(value = read(it))
                                }
                                if (continuation.isActive) continuation.resumeWith(Result.success(value))
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                        }
                    }
                })
            }
            fetched.value?.let { return it }
            target = fetched.redirect ?: throw QuickImportError("分享链接无法解析")
        }
        throw QuickImportError("分享链接跳转次数过多")
    }
    suspend fun download(url: HttpUrl, file: java.io.File, active: () -> Unit, progress: (Long, Long) -> Unit) {
        val context = kotlinx.coroutines.currentCoroutineContext()
        fetch(url, true) { response ->
            val body = response.body ?: throw QuickImportError("图片内容为空")
            if (body.contentLength() > MAX_IMAGE) throw QuickImportError("图片超过 64 MB，无法导入")
            var bytes = 0L
            try {
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        context.ensureActive()
                        active()
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count
                        if (bytes > MAX_IMAGE) throw QuickImportError("图片超过 64 MB，无法导入")
                        output.write(buffer, 0, count)
                        progress(bytes, body.contentLength())
                    }
                } }
                if (bytes == 0L || body.contentLength() >= 0 && body.contentLength() != bytes) throw java.io.IOException("Incomplete image")
            } catch (error: Exception) { file.delete(); throw error }
        }
    }
    suspend fun resolve(link: String, page: Int, total: Int): ResolvedXhsImage {
        val start = link.toHttpUrl()
        val (html, url) = fetch(start, false) { response ->
            val body = response.body ?: throw QuickImportError("分享页面为空")
            val bytes = body.byteStream().use { it.readBytesBounded(2 * 1024 * 1024) }
            bytes.toString(Charsets.UTF_8) to response.request.url
        }
        val noteId = Regex("/(?:discovery/item|explore)/([0-9a-f]{24})/?").matchEntire(url.encodedPath)?.groupValues?.get(1)
            ?: throw QuickImportError("该笔记需要登录或验证，暂时无法导入")
        val raw = Regex("<script[^>]*>\\s*window\\.__SETUP_SERVER_STATE__\\s*=\\s*(\\{.*?\\})\\s*;?\\s*</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw QuickImportError("小红书页面格式已变化，暂时无法导入")
        val note = try { JSONObject(raw).getJSONObject("LAUNCHER_SSR_STORE_PAGE_DATA").getJSONObject("noteData") }
            catch (_: Exception) { throw QuickImportError("小红书页面格式已变化，暂时无法导入") }
        if (note.optString("noteId") != noteId || note.optString("type") != "normal") throw QuickImportError("无法确认当前图文笔记")
        val images = note.optJSONArray("imageList") ?: throw QuickImportError("该笔记没有可导入图片")
        if (images.length() != total || page !in 1..total) throw QuickImportError("图片页码与笔记不一致，请重试")
        val image = images.getJSONObject(page - 1)
        val fileId = image.optString("fileId")
        if (!Regex("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*").matches(fileId)) throw QuickImportError("无法获取高清图片来源")
        val width = image.optInt("width"); val height = image.optInt("height")
        if (width <= 0 || height <= 0 || width.toLong() * height > 150_000_000) throw QuickImportError("图片尺寸不受支持")
        val original = (fixture ?: "https://ci.xiaohongshu.com/".toHttpUrl()).newBuilder()
            .addPathSegments(fileId).build()
        // Only addresses supplied for this exact image are fallback candidates.
        // Preserve their CDN transforms and signatures; every result is dimension-checked.
        val supplied = buildList {
            add(image.optString("url"))
            image.optJSONArray("infoList")?.let { entries ->
                for (i in 0 until entries.length()) add(entries.optJSONObject(i)?.optString("url").orEmpty())
            }
        }.mapNotNull { value -> runCatching { checked(value.toHttpUrl(), true) }.getOrNull() }
            .distinct().filter { it != original }.take(2)
        return ResolvedXhsImage(noteId, fileId, original, width, height, supplied)
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer); if (count < 0) break
        if (out.size() + count > limit) throw QuickImportError("分享页面过大，暂时无法导入")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
