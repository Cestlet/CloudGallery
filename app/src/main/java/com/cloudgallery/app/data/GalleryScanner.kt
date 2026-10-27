package com.cloudgallery.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

class GalleryScanner(private val database: GalleryDatabase) {
    suspend fun scan(provider: StorageProvider, allowNetwork: () -> Boolean = { true }, onProgress: (String) -> Unit) {
        val source = provider.sourceId
        val dao = database.gallery()
        var state = dao.state(source)
        if (state == null || state.finished) {
            state = ScanState(source, UUID.randomUUID().toString(), lastSuccess = state?.lastSuccess ?: 0)
            database.withTransaction { dao.saveState(state); dao.enqueue(PendingDirectory(source, provider.rootPath)) }
        }
        val run = state.run
        while (true) {
            currentCoroutineContext().ensureActive()
            val pending = dao.next(source) ?: break
            check(allowNetwork()) { "等待 Wi-Fi，可在设置 → 网络中允许移动数据同步" }
            onProgress("扫描 ${pending.path} · 第 ${pending.page} 页")
            val page = provider.list(pending.path, pending.page, refresh = pending.page == 1)
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                dao.putAlbums((listOf(pending.path) + page.files.filter { it.directory }.map { join(pending.path, it.name) })
                    .map { IndexedAlbum(source, it, run) })
                val images = page.files.filter { !it.directory && galleryImageFile(it.name) }
                dao.putMedia(images.map { file -> IndexedMedia(source, join(pending.path, file.name), file.name,
                    pending.path, file.modified, file.size, file.thumb, run, file.remoteId) })
                page.files.filter { it.directory }.forEach { dao.enqueue(PendingDirectory(source, join(pending.path, it.name))) }
                if (page.more) dao.advance(pending.copy(page = pending.page + 1))
                else dao.completeDirectory(source, pending.path)
            }
        }
        currentCoroutineContext().ensureActive()
        database.withTransaction {
            // A failure anywhere keeps previous entries and the persisted queue intact.
            dao.prune(source, run)
            dao.pruneAlbums(source, run)
            dao.saveState(state.copy(finished = true, lastSuccess = System.currentTimeMillis()))
        }
        onProgress("同步完成")
    }
    private fun join(parent: String, name: String) = parent.trimEnd('/') + "/" + name
}
