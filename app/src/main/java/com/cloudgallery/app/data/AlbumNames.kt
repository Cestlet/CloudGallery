package com.cloudgallery.app.data

fun albumFolderName(input: String): String = input.trim().also { name ->
    require(name.isNotEmpty()) { "请输入相册名称" }
    require(name != "." && name != ".." && name.none { it.isISOControl() || it in "/\\:*?\"<>|" }) { "相册名称不能包含 /、\\、:、*、?、\"、<、>、|" }
    require(name.toByteArray(Charsets.UTF_8).size <= 255) { "相册名称过长" }
}
