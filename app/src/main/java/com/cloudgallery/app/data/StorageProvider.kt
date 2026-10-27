package com.cloudgallery.app.data

import java.security.MessageDigest

private const val HEX = "0123456789abcdef"
fun digest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val result = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val unsigned = byte.toInt() and 0xff
        result[index * 2] = HEX[unsigned ushr 4]
        result[index * 2 + 1] = HEX[unsigned and 0xf]
    }
    return String(result)
}

fun normalizePath(input: String): String {
    val parts = input.trim().split('/').filter { it.isNotEmpty() }
    require(parts.none { it == "." || it == ".." }) { "目录路径不能包含 . 或 .." }
    return "/" + parts.joinToString("/")
}

data class RemoteFile(val name: String, val directory: Boolean, val size: Long, val modified: String, val thumb: String,
    val remoteId: String = "")
data class DirectoryPage(val files: List<RemoteFile>, val more: Boolean)

/** App-facing virtual mount. Implementations may use an embedded engine or a cloud API. */
interface StorageProvider {
    val sourceId: String
    val rootPath: String
    suspend fun list(path: String, page: Int, refresh: Boolean = false): DirectoryPage
    suspend fun createFolder(path: String) { error("此网盘暂不支持创建文件夹") }
    suspend fun original(path: String, remoteId: String = ""): String
    fun absolute(url: String): String
    fun downloadHeaders(url: String): Map<String, String> = emptyMap()
}
