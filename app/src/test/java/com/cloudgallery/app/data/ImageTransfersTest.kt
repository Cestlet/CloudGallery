package com.cloudgallery.app.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ImageTransfersTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun visibleAndPrefetchReadersShareOneDownload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("thumbnail").setBodyDelay(150, TimeUnit.MILLISECONDS))
            val target = temporary.root.resolve("shared-thumb")
            val locks = ImageKeyLocks()
            val client = OkHttpClient()
            val results = (1..12).map {
                async(Dispatchers.IO) {
                    locks.withKey("same-photo-version") {
                        if (!target.exists()) client.newCall(Request.Builder().url(server.url("/thumb")).build()).downloadTo(target, 1024)
                        target.readText()
                    }
                }
            }.awaitAll()
            assertEquals(List(12) { "thumbnail" }, results)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun scrolledAwayRequestCancelsWhileWaitingForHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/slow")).build())
            val transfer = launch(Dispatchers.IO) { call.downloadTo(temporary.root.resolve("cancelled-thumb"), 1024) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(1500) { transfer.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test fun oversizedResponseIsRejectedBeforeBeingCached() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("oversized"))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/thumb")).build())
            val target = temporary.root.resolve("too-large")
            assertTrue(runCatching { call.downloadTo(target, 4) }.isFailure)
            assertFalse(target.exists())
        }
    }
}
