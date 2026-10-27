package com.cloudgallery.app.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

class XhsSourceTest {
    @Test fun fallbackUrlsBelongToTheSelectedImageAndKeepSignatures() = runBlocking {
        MockWebServer().use { server ->
            val page = html().replace("\"fileId\":\"notes_pre_post/image1\"", "\"url\":\"https://sns-img.xhscdn.com/original?sign=a%2Bb\",\"infoList\":[{\"url\":\"https://unrelated.example/image\"},{\"url\":\"https://sns-img.xhscdn.com/second!format/png\"}],\"fileId\":\"notes_pre_post/image1\"")
            server.enqueue(MockResponse().setBody(page))
            val first = source(server).resolve(server.url("/discovery/item/$note").toString(), 1, 2)
            assertEquals(2, first.alternatives.size)
            assertEquals("sign=a%2Bb", first.alternatives[0].encodedQuery)
            assertEquals("/second!format/png", first.alternatives[1].encodedPath)
            server.enqueue(MockResponse().setBody(page))
            assertTrue(source(server).resolve(server.url("/discovery/item/$note").toString(), 2, 2).alternatives.isEmpty())
        }
    }
    @Test fun serverFailureIsRetryableButMissingImageIsNot(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(java.io.IOException::class.java) { source(server).blockingResolve(server.url("/explore/$note").toString(), 1, 2) }
            server.enqueue(MockResponse().setResponseCode(404))
            assertThrows(QuickImportError::class.java) { source(server).blockingResolve(server.url("/explore/$note").toString(), 1, 2) }
        }
    }
    @Test fun cancellationClosesSourceBodyAndRemovesPartialImage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(8192)).throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS))
            val file = java.io.File.createTempFile("source-cancel-", ".image")
            val client = OkHttpClient.Builder().followRedirects(false).build()
            try {
                val transfer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    XhsSource(client, server.url("/")).download(server.url("/slow"), file, {}, { _, _ -> })
                }
                assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS))
                kotlinx.coroutines.withTimeout(1500) {
                    while (file.length() == 0L) kotlinx.coroutines.delay(10)
                    transfer.cancelAndJoin()
                    while (client.dispatcher.runningCallsCount() != 0) kotlinx.coroutines.delay(10)
                }
                assertFalse(file.exists())
            } finally { file.delete(); client.dispatcher.cancelAll() }
        }
    }

    private val note = "6a93d59e0000000020032745"
    private fun html(id: String = note, count: Int = 2) = "<script>window.__SETUP_SERVER_STATE__={\"LAUNCHER_SSR_STORE_PAGE_DATA\":{\"noteData\":{\"noteId\":\"$id\",\"type\":\"normal\",\"imageList\":[" +
        (1..count).joinToString(",") { "{\"fileId\":\"notes_pre_post/image$it\",\"width\":2000,\"height\":3000}" } + "]}}}</script>"
    private fun source(server: MockWebServer) = XhsSource(OkHttpClient.Builder().followRedirects(false).build(), server.url("/"))
    private fun XhsSource.blockingResolve(link: String, page: Int, total: Int) = runBlocking { resolve(link, page, total) }
    @Test fun selectsCurrentImageAndRetainsDimensionsAndFormatIndependentPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(html()))
            val image = source(server).blockingResolve(server.url("/discovery/item/$note").toString(), 2, 2)
            assertEquals("notes_pre_post/image2", image.fileId)
            assertEquals(2000, image.width); assertEquals(3000, image.height)
            assertEquals("/notes_pre_post/image2", image.url.encodedPath)
            assertNull(image.url.query)
        }
    }
    @Test fun mismatchedImageCountAndPageFailRatherThanSelectAnotherImage() {
        MockWebServer().use { server ->
            for ((page, total) in listOf(1 to 3, 0 to 2, 3 to 2)) {
                server.enqueue(MockResponse().setBody(html()))
                assertThrows(QuickImportError::class.java) { source(server).blockingResolve(server.url("/discovery/item/$note").toString(), page, total) }
            }
        }
    }
    @Test fun wrongNoteAndLoginPageAreRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(html("000000000000000000000001")))
            assertThrows(QuickImportError::class.java) { source(server).blockingResolve(server.url("/discovery/item/$note").toString(), 1, 2) }
            server.enqueue(MockResponse().setBody(html()))
            assertThrows(QuickImportError::class.java) { source(server).blockingResolve(server.url("/login").toString(), 1, 2) }
        }
    }
    @Test fun unexpectedRedirectHostIsRejectedBeforeFetchingIt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://unrelated.example/file"))
            assertThrows(QuickImportError::class.java) { source(server).blockingResolve(server.url("/share").toString(), 1, 2) }
        }
    }
    @Test fun traversalFileIdentifierAndOversizePageAreRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(html().replace("notes_pre_post/image1", "../secret")))
            assertThrows(QuickImportError::class.java) { source(server).blockingResolve(server.url("/discovery/item/$note").toString(), 1, 2) }
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            assertThrows(QuickImportError::class.java) { source(server).blockingResolve(server.url("/discovery/item/$note").toString(), 1, 2) }
        }
    }
    @Test fun shareTextExtractsOnlySupportedUrls() {
        assertEquals("https://xhslink.cn/o/abcdef", XhsSource.shareUrl("帖子 https://xhslink.cn/o/abcdef 复制后打开"))
        assertNull(XhsSource.shareUrl("https://not-xiaohongshu.com/explore/x"))
    }
    @Test fun defaultClientReusesConnectionAcrossSourceInstances() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(html()))
            server.enqueue(MockResponse().setBody(html()))
            runBlocking {
                XhsSource(fixture = server.url("/")).resolve(server.url("/discovery/item/$note").toString(), 1, 2)
                XhsSource(fixture = server.url("/")).resolve(server.url("/discovery/item/$note").toString(), 2, 2)
            }
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(1, server.takeRequest().sequenceNumber)
        }
    }
}
