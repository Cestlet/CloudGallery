package com.cloudgallery.app.data.baidu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BaiduTokens(val accessToken: String, val refreshToken: String, val expiresAt: Long) {
    override fun toString() = "BaiduTokens(redacted)"
}
data class DeviceAuthorization(val deviceCode: String, val userCode: String, val verificationUrl: String,
    val expiresAt: Long, val intervalSeconds: Int)
sealed interface AuthorizationResult {
    data class Granted(val tokens: BaiduTokens) : AuthorizationResult
    data object Pending : AuthorizationResult
    data object SlowDown : AuthorizationResult
    data object Denied : AuthorizationResult
    data object Expired : AuthorizationResult
}
class BaiduOAuth(
    private val appKey: String,
    private val secretKey: String,
    private val baseUrl: HttpUrl = "https://openapi.baidu.com/".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
) {
    private suspend fun request(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        require(appKey.isNotBlank()) { "尚未配置百度网盘应用 App Key" }
        val url = baseUrl.resolve(path)!!.newBuilder().addQueryParameter("client_id", appKey)
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful || response.code == 400) { "百度授权网络请求失败（HTTP ${response.code}）" }
            JSONObject(response.body?.string() ?: error("百度授权返回空响应"))
        }
    }
    suspend fun begin(): DeviceAuthorization {
        val json = request("/oauth/2.0/device/code", mapOf("response_type" to "device_code", "scope" to "basic,netdisk"))
        check(!json.has("error")) { "无法发起百度授权，请检查应用配置" }
        val verification = json.getString("verification_url").toHttpUrl()
        val localFixture = baseUrl.scheme == "http" && baseUrl.host in setOf("127.0.0.1", "localhost") && verification.host == baseUrl.host
        require(localFixture || verification.scheme == "https" && (verification.host == "baidu.com" || verification.host.endsWith(".baidu.com"))) { "百度授权页面地址无效" }
        val expires = json.getLong("expires_in")
        require(expires in 1..86400) { "百度授权有效期无效" }
        return DeviceAuthorization(json.getString("device_code"), json.getString("user_code"), verification.toString(),
            clock() + expires * 1000, json.optInt("interval", 5).coerceIn(1, 60))
    }
    suspend fun poll(device: DeviceAuthorization): AuthorizationResult {
        if (clock() >= device.expiresAt) return AuthorizationResult.Expired
        require(secretKey.isNotBlank()) { "尚未配置百度网盘应用授权密钥" }
        val json = request("/oauth/2.0/token", mapOf("grant_type" to "device_token", "code" to device.deviceCode, "client_secret" to secretKey))
        return when (json.optString("error")) {
            "authorization_pending" -> AuthorizationResult.Pending
            "slow_down" -> AuthorizationResult.SlowDown
            "authorization_declined", "access_denied" -> AuthorizationResult.Denied
            "expired_token", "code_expired" -> AuthorizationResult.Expired
            "" -> AuthorizationResult.Granted(parseTokens(json))
            else -> error("百度授权失败，请重新授权或检查应用配置")
        }
    }
    suspend fun awaitAuthorization(device: DeviceAuthorization): BaiduTokens {
        var interval = device.intervalSeconds.toLong()
        while (clock() < device.expiresAt) {
            delay(minOf(interval * 1000, (device.expiresAt - clock()).coerceAtLeast(0)))
            when (val result = poll(device)) {
                is AuthorizationResult.Granted -> return result.tokens
                AuthorizationResult.Pending -> Unit
                AuthorizationResult.SlowDown -> interval = (interval + 5).coerceAtMost(60)
                AuthorizationResult.Denied -> error("用户取消了百度授权")
                AuthorizationResult.Expired -> error("百度授权已过期，请重新开始")
            }
        }
        error("百度授权已过期，请重新开始")
    }
    suspend fun refresh(token: String): BaiduTokens {
        require(secretKey.isNotBlank() && token.isNotBlank()) { "缺少百度授权刷新配置" }
        val json = request("/oauth/2.0/token", mapOf("grant_type" to "refresh_token", "refresh_token" to token, "client_secret" to secretKey))
        check(!json.has("error")) { "百度网盘授权无法刷新，请重新授权" }
        return parseTokens(json)
    }
    private fun parseTokens(json: JSONObject): BaiduTokens {
        val token = json.getString("access_token")
        val refresh = json.getString("refresh_token")
        val expires = json.getLong("expires_in")
        require(token.isNotBlank() && refresh.isNotBlank() && expires in 1..31536000) { "百度返回无效授权信息" }
        return BaiduTokens(token, refresh, clock() + expires * 1000)
    }
}

/** Coalesces concurrent refreshes; persist rotated credentials before publishing them. */
class BaiduSession(initial: BaiduTokens, private val oauth: BaiduOAuth,
    private val persist: suspend (BaiduTokens) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var tokens = initial
    suspend fun accessToken(): String = mutex.withLock {
        if (clock() + 60_000 >= tokens.expiresAt) {
            val refreshed = oauth.refresh(tokens.refreshToken)
            persist(refreshed)
            tokens = refreshed
        }
        tokens.accessToken
    }
}
