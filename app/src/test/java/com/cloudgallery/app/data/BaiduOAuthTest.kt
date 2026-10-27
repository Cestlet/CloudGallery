package com.cloudgallery.app.data

import com.cloudgallery.app.data.baidu.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BaiduOAuthTest {
    @Test fun rejectsUntrustedAuthorizationPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_code":"device","user_code":"ABCD","verification_url":"https://baidu.com.evil.example/device","expires_in":600}"""))
            assertTrue(runCatching { BaiduOAuth("key", "secret", server.url("/")).begin() }.isFailure)
        }
    }
    @Test fun persistenceFailureDoesNotPublishNewCredentials() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""")) }
            val session = BaiduSession(BaiduTokens("old", "old-refresh", 0),
                BaiduOAuth("key", "secret", server.url("/"), { 1000L }), { error("storage failure") }, { 1000L })
            repeat(2) { assertTrue(runCatching { session.accessToken() }.isFailure) }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun pendingThenGranted() = runBlocking {
        MockWebServer().use { server ->
            val oauth = BaiduOAuth("app-key", "test-secret", server.url("/"), { 1000L })
            server.enqueue(MockResponse().setBody("""{"device_code":"device","user_code":"ABCD","verification_url":"https://openapi.baidu.com/device","expires_in":600,"interval":5}"""))
            val device = oauth.begin()
            assertEquals("basic,netdisk", server.takeRequest().requestUrl!!.queryParameter("scope"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
            assertEquals(AuthorizationResult.Pending, oauth.poll(device))
            server.takeRequest()
            server.enqueue(MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""))
            val granted = oauth.poll(device) as AuthorizationResult.Granted
            assertEquals("access", granted.tokens.accessToken)
            assertFalse(granted.tokens.toString().contains("refresh"))
            assertEquals("device_token", server.takeRequest().requestUrl!!.queryParameter("grant_type"))
        }
    }
    @Test fun deniedSlowDownAndExpiry() = runBlocking {
        MockWebServer().use { server ->
            val oauth = BaiduOAuth("key", "secret", server.url("/"), { 1000L })
            val device = DeviceAuthorization("device", "ABCD", "https://openapi.baidu.com/device", 10_000, 5)
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
            assertEquals(AuthorizationResult.SlowDown, oauth.poll(device))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"access_denied"}"""))
            assertEquals(AuthorizationResult.Denied, oauth.poll(device))
            assertEquals(AuthorizationResult.Expired, oauth.poll(device.copy(expiresAt = 999)))
        }
    }
    @Test fun simultaneousReadersRefreshOnceAndPersistRotation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""))
            var persisted: BaiduTokens? = null
            val oauth = BaiduOAuth("key", "secret", server.url("/"), { 1000L })
            val session = BaiduSession(BaiduTokens("old-access", "old-refresh", 0), oauth, { persisted = it }, { 1000L })
            val results = (1..6).map { async { session.accessToken() } }.awaitAll()
            assertEquals(List(6) { "new-access" }, results)
            assertEquals(1, server.requestCount)
            assertEquals("new-refresh", persisted!!.refreshToken)
        }
    }
    @Test fun missingConfigurationFailsBeforeNetworking() = runBlocking {
        assertTrue(runCatching { BaiduOAuth("", "").begin() }.isFailure)
    }
}
