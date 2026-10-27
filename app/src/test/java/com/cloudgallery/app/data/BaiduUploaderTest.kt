package com.cloudgallery.app.data

import com.cloudgallery.app.data.baidu.BaiduProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class BaiduUploaderTest {
    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun uploadsOnlyMissingBlocksAndChecksFinalFile() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(4 * 1024 * 1024 + 7) { (it % 127).toByte() }
            server.enqueue(MockResponse().setBody("""{"errno":0,"uploadid":"fixture","block_list":[1],"return_type":1}"""))
            server.enqueue(MockResponse().setBody("""{"errno":0,"md5":"${md5(bytes.copyOfRange(4*1024*1024,bytes.size))}"}"""))
            server.enqueue(MockResponse().setBody("""{"errno":0,"path":"/Backup/photo.jpg","size":${bytes.size}}"""))
            val provider = BaiduProvider("test", "/", { "fake-token" }, server.url("/"))
            BaiduUploader(provider) { bytes.inputStream() }.upload(bytes.size.toLong(), "/Backup/photo.jpg") {}
            val pre = server.takeRequest()
            assertEquals("precreate", pre.requestUrl!!.queryParameter("method"))
            assertTrue(pre.body.readUtf8().contains("block_list"))
            val slice = server.takeRequest()
            assertEquals("1", slice.requestUrl!!.queryParameter("partseq"))
            assertEquals("fake-token", slice.requestUrl!!.queryParameter("access_token"))
            assertEquals("create", server.takeRequest().requestUrl!!.queryParameter("method"))
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun instantUploadSkipsSliceAndCreate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":0,"return_type":2}"""))
            BaiduUploader(BaiduProvider("test", "/", { "fake" }, server.url("/"))) { "photo".byteInputStream() }
                .upload(5, "/photo.jpg") {}
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun invalidSliceChecksumNeverCreatesFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errno":0,"uploadid":"fixture","block_list":[0]}"""))
            server.enqueue(MockResponse().setBody("""{"errno":0,"md5":"incorrect"}"""))
            try {
                BaiduUploader(BaiduProvider("test", "/", { "fake" }, server.url("/"))) { "photo".byteInputStream() }
                    .upload(5, "/photo.jpg") {}
                fail("Invalid checksum must fail")
            } catch (_: IllegalStateException) { }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun changedPolicyStopsBeforeSendingBytes() = runBlocking {
        MockWebServer().use { server ->
            try {
                BaiduUploader(BaiduProvider("test", "/", { "fake" }, server.url("/"))) { "photo".byteInputStream() }
                    .upload(5, "/photo.jpg") { error("backup disabled") }
                fail("Policy change must stop upload")
            } catch (_: IllegalStateException) { }
            assertEquals(0, server.requestCount)
        }
    }
}
