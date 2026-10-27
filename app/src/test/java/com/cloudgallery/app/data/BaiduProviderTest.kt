package com.cloudgallery.app.data

import com.cloudgallery.app.data.baidu.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class BaiduProviderTest {
    @Test fun createsFolderWithoutRenamingOrOverwriting() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":0,"path":"/Photos/新相册","isdir":1}"""))
            BaiduProvider("account", "/Photos", { "token" }, server.url("/")).createFolder("/Photos/新相册")
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("create", request.requestUrl!!.queryParameter("method"))
            assertEquals("token", request.requestUrl!!.queryParameter("access_token"))
            val form = ("http://localhost/?" + request.body.readUtf8()).toHttpUrl()
            assertEquals("/Photos/新相册", form.queryParameter("path"))
            assertEquals("1", form.queryParameter("isdir"))
            assertEquals("0", form.queryParameter("rtype"))
        }
    }
    @Test fun duplicateFolderDoesNotAppearSuccessful() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":-8}"""))
            try { BaiduProvider("account", "/Photos", { "token" }, server.url("/")).createFolder("/Photos/exists"); fail("Conflict must fail") }
            catch (error: BaiduApiException) { assertEquals(-8, error.code) }
        }
    }
    @Test fun cannotCreateOutsideGalleryOrUseInvalidName() = runBlocking {
        MockWebServer().use { server ->
            val provider = BaiduProvider("account", "/Photos", { "token" }, server.url("/"))
            for (path in listOf("/Other/album", "/Photos/../Other", "/Photos/bad:name")) {
                try { provider.createFolder(path); fail("Must reject $path") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun gridPrefersMediumThumbnailOverLargeVariant() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":0,"list":[{"fs_id":1,"path":"/a.jpg","server_filename":"a.jpg","thumbs":{"url1":"https://example.com/small","url2":"https://example.com/medium","url3":"https://example.com/large"}}]}"""))
            val photo = BaiduProvider("account", "/", { "token" }, server.url("/")).list("/", 1).files.single()
            assertEquals("https://example.com/medium", photo.thumb)
        }
    }
    @Test fun identityUsesStableAccountId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":0,"uk":123456,"baidu_name":"test-user"}"""))
            val provider = BaiduProvider("pending", "/", { "token" }, server.url("/"))
            assertEquals("123456" to "test-user", provider.identity())
            val request = server.takeRequest().requestUrl!!
            assertEquals("/rest/2.0/xpan/nas", request.encodedPath)
            assertEquals("uinfo", request.queryParameter("method"))
        }
    }
    @Test fun directoryUsesOffsetAndThumbnailFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":0,"list":[{"fs_id":123,"path":"/照片/a.jpg","server_filename":"a.jpg","isdir":0,"size":12,"server_mtime":1720000000,"thumbs":{"url3":"https://example.com/thumb"}}]}"""))
            val provider = BaiduProvider("account", "/照片", { "test-token" }, server.url("/"))
            val page = provider.list("/照片", 2)
            assertFalse(page.more)
            assertEquals("123", page.files.single().remoteId)
            assertEquals("https://example.com/thumb", page.files.single().thumb)
            val url = server.takeRequest().requestUrl!!
            assertEquals("200", url.queryParameter("start"))
            assertEquals("200", url.queryParameter("limit"))
            assertEquals("1", url.queryParameter("web"))
            assertEquals("/照片", url.queryParameter("dir"))
            assertEquals("test-token", url.queryParameter("access_token"))
        }
    }
    @Test fun fullPageContinuesEvenWithoutTotal() = runBlocking {
        MockWebServer().use { server ->
            val items = (1..200).joinToString(",") { """{"fs_id":$it,"path":"/a$it.jpg","server_filename":"a$it.jpg"}""" }
            server.enqueue(MockResponse().setBody("""{"errno":0,"list":[$items]}"""))
            assertTrue(BaiduProvider("account", "/", { "token" }, server.url("/")).list("/", 1).more)
        }
    }
    @Test fun originalResolvesPersistedFileIdAndUserAgent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":0,"list":[{"fs_id":123,"path":"/照片/a.jpg","dlink":"https://d.pcs.baidu.com/file/image"}]}"""))
            val provider = BaiduProvider("account", "/照片", { "token" }, server.url("/"))
            val download = provider.original("/照片/a.jpg", "123").toHttpUrl()
            assertEquals("d.pcs.baidu.com", download.host)
            assertEquals("/file/image", download.encodedPath)
            assertEquals("token", download.queryParameter("access_token"))
            assertEquals("[123]", server.takeRequest().requestUrl!!.queryParameter("fsids"))
            assertEquals(mapOf("User-Agent" to "pan.baidu.com"), provider.downloadHeaders("https://d.pcs.baidu.com/file/image"))
        }
    }
    @Test fun originalDownloadRequiresTokenAndPreservesSignature() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    if (url.encodedPath == "/rest/2.0/xpan/multimedia") {
                        return MockResponse().setBody("""{"errno":0,"list":[{"fs_id":1,"path":"/a.jpg","dlink":"${server.url("/download?sign=a%2Bb&access_token=stale")}"}]}""")
                    }
                    return if (url.queryParameter("access_token") == "current-token" &&
                        url.queryParameterValues("access_token").size == 1 && url.queryParameter("sign") == "a+b" &&
                        request.getHeader("User-Agent") == "pan.baidu.com") MockResponse().setBody("original-image")
                    else MockResponse().setResponseCode(403)
                }
            }
            val provider = BaiduProvider("account", "/", { "current-token" }, server.url("/"))
            val link = provider.original("/a.jpg", "1")
            val request = Request.Builder().url(link).apply {
                provider.downloadHeaders(link).forEach { (key, value) -> header(key, value) }
            }.build()
            OkHttpClient().newCall(request).execute().use {
                assertEquals(200, it.code)
                assertEquals("original-image", it.body!!.string())
            }
        }
    }
    @Test fun doesNotSendOriginalTokenToUntrustedDomain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":0,"list":[{"fs_id":1,"path":"/a.jpg","dlink":"https://baidu.com.evil.example/image"}]}"""))
            assertTrue(runCatching { BaiduProvider("account", "/", { "token" }, server.url("/")).original("/a.jpg", "1") }.isFailure)
        }
    }
    @Test fun authErrorsAreNotEmptyDirectories() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":20016,"errmsg":"sensitive response"}"""))
            val error = runCatching { BaiduProvider("account", "/", { "token" }, server.url("/")).list("/", 1) }.exceptionOrNull()
            assertTrue(error is BaiduApiException)
            assertFalse(error!!.message!!.contains("sensitive"))
        }
    }
    @Test fun rejectsOutsideMountAndSeparatesAccounts() = runBlocking {
        val first = BaiduProvider("a", "/照片", { "token" })
        val second = BaiduProvider("b", "/照片", { "token" })
        assertNotEquals(first.sourceId, second.sourceId)
        assertTrue(runCatching { first.list("/其他", 1) }.isFailure)
        assertTrue(runCatching { first.absolute("http://evil.example/image") }.isFailure)
    }
}
