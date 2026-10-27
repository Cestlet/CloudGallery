package com.cloudgallery.app.data.baidu

import com.cloudgallery.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class BaiduApiException(val code: Int) : Exception(when (code) {
    -6, 20016, 20017, 31045 -> "百度网盘授权失效，请重新授权"
    31034 -> "百度网盘请求频率受限，请稍后重试"
    -8 -> "同名文件或文件夹已存在，请更换相册名称"
    -9 -> "百度网盘目录或文件不存在"
    else -> "百度网盘请求失败（$code）"
})

/** Native virtual mount; requests go straight from the phone to Baidu. */
class BaiduProvider(
    accountId: String,
    root: String,
    private val accessToken: suspend () -> String,
    private val baseUrl: HttpUrl = "https://pan.baidu.com/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).followRedirects(false).build()
) : StorageProvider {
    override val rootPath = normalizePath(root)
    override val sourceId = digest("baidu|$accountId|$rootPath")
    init { require(accountId.isNotBlank()) { "缺少百度账户标识" } }
    private fun scoped(path: String): String = normalizePath(path).also {
        require(rootPath == "/" || it == rootPath || it.startsWith("$rootPath/")) { "目录超出已选择的图库范围" }
    }
    private suspend fun get(endpoint: String, params: Map<String, String>): JSONObject {
        val token = accessToken()
        require(token.isNotBlank()) { "尚未授权百度网盘" }
        return withContext(Dispatchers.IO) {
            val url = baseUrl.resolve(endpoint)!!.newBuilder().addQueryParameter("access_token", token)
                .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
            client.newCall(Request.Builder().url(url).header("User-Agent", "pan.baidu.com").build()).execute().use { response ->
                check(response.isSuccessful) { "百度网盘网络请求失败（HTTP ${response.code}）" }
                val body = JSONObject(response.body?.string() ?: error("百度网盘返回空响应"))
                val code = when {
                    body.has("errno") -> body.getInt("errno")
                    body.has("error_code") -> body.getInt("error_code")
                    else -> error("百度网盘响应缺少状态码")
                }
                if (code != 0) throw BaiduApiException(code)
                body
            }
        }
    }
    suspend fun identity(): Pair<String, String> {
        val json = get("/rest/2.0/xpan/nas", mapOf("method" to "uinfo"))
        val id = json.get("uk").toString()
        require(id.isNotBlank()) { "百度账户标识无效" }
        return id to json.optString("baidu_name", "百度网盘")
    }
    override suspend fun list(path: String, page: Int, refresh: Boolean): DirectoryPage {
        require(page in 1..1_000_000) { "无效页码" }
        val directory = scoped(path)
        val json = get("/rest/2.0/xpan/file", mapOf("method" to "list", "dir" to directory,
            "start" to ((page - 1) * 200).toString(), "limit" to "200", "order" to "name", "desc" to "0", "web" to "1"))
        val items = json.getJSONArray("list")
        val files = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val name = item.getString("server_filename")
            require(name.isNotBlank() && '/' !in name && name != "." && name != "..") { "百度网盘返回无效文件名" }
            require(normalizePath(item.getString("path")) == directory.trimEnd('/') + "/" + name) { "百度网盘返回目录不匹配" }
            val thumbs = item.optJSONObject("thumbs")
            val thumb = listOf("url2", "url1", "url3").firstNotNullOfOrNull { key -> thumbs?.optString(key)?.takeIf { it.isNotBlank() } } ?: ""
            RemoteFile(name, item.optInt("isdir") == 1, item.optLong("size"),
                Instant.ofEpochSecond(item.optLong("server_mtime")).atOffset(ZoneOffset.UTC).toString(),
                thumb, item.getLong("fs_id").toString())
        }
        return DirectoryPage(files, files.size == 200)
    }
    override suspend fun createFolder(path: String) {
        val directory = scoped(path)
        require(directory != rootPath) { "不能创建已存在的图库根目录" }
        albumFolderName(directory.substringAfterLast('/'))
        val url = uploadApi("create")
        withContext(Dispatchers.IO) {
            val form = okhttp3.FormBody.Builder().add("path", directory).add("isdir", "1").add("rtype", "0").build()
            client.newCall(Request.Builder().url(url).header("User-Agent", "pan.baidu.com").post(form).build()).execute().use { response ->
                check(response.isSuccessful) { "创建相册失败（HTTP ${response.code}）" }
                val json = JSONObject(response.body?.string() ?: error("百度网盘返回空响应"))
                val code = if (json.has("errno")) json.getInt("errno") else json.getInt("error_code")
                if (code != 0) throw BaiduApiException(code)
                check(json.optInt("isdir") == 1 && normalizePath(json.getString("path")) == directory) { "网盘返回的相册路径不匹配" }
            }
        }
    }
    override suspend fun original(path: String, remoteId: String): String {
        val normalized = scoped(path)
        val id = remoteId.toLongOrNull() ?: error("百度图片缺少文件标识，请更新索引")
        val json = get("/rest/2.0/xpan/multimedia", mapOf("method" to "filemetas", "fsids" to "[$id]", "dlink" to "1", "extra" to "1"))
        val entries = json.getJSONArray("list")
        val item = (0 until entries.length()).map { entries.getJSONObject(it) }
            .firstOrNull { it.getLong("fs_id") == id && normalizePath(it.getString("path")) == normalized }
            ?: error("百度图片不存在或索引需要更新")
        val link = absolute(item.getString("dlink")).toHttpUrl()
        val localFixture = baseUrl.scheme == "http" && baseUrl.host in setOf("localhost", "127.0.0.1") && link.host == baseUrl.host
        require(localFixture || link.host == "baidu.com" || link.host.endsWith(".baidu.com") ||
            link.host == "baidupcs.com" || link.host.endsWith(".baidupcs.com")) { "百度原图下载域名无效" }
        // filemetas dlink is an authenticated download endpoint, not a public image URL.
        return link.newBuilder().setQueryParameter("access_token", accessToken()).build().toString()
    }
    override fun absolute(url: String): String {
        val parsed = url.toHttpUrl()
        // Local HTTP is used only by injected test servers; production media must use HTTPS.
        require(parsed.scheme == "https" || parsed.host == baseUrl.host && baseUrl.scheme == "http") { "百度图片地址未使用 HTTPS" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "百度图片地址无效" }
        return parsed.toString()
    }
    override fun downloadHeaders(url: String) = mapOf("User-Agent" to "pan.baidu.com")
    internal suspend fun uploadApi(method: String): HttpUrl = baseUrl.resolve("/rest/2.0/xpan/file")!!.newBuilder()
        .addQueryParameter("method", method).addQueryParameter("access_token", accessToken()).build()
    internal suspend fun uploadSlice(path: String, uploadId: String, index: Int): HttpUrl {
        val host = if (baseUrl.scheme == "http") baseUrl else "https://d.pcs.baidu.com/".toHttpUrl()
        return host.resolve("/rest/2.0/pcs/superfile2")!!.newBuilder()
            .addQueryParameter("method", "upload").addQueryParameter("type", "tmpfile")
            .addQueryParameter("access_token", accessToken()).addQueryParameter("path", normalizePath(path))
            .addQueryParameter("uploadid", uploadId).addQueryParameter("partseq", index.toString()).build()
    }
}
