package com.cloudgallery.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal class QuickImportCoordinator {
    private val slots = Semaphore(2)
    private val tasks = ImageKeyLocks()
    private val images = ImageKeyLocks()
    private val active = ConcurrentHashMap.newKeySet<String>()
    val cleanupLock = Mutex()
    fun activeTasks(): Set<String> = active.toSet()
    suspend fun <T> transfer(id: String, action: suspend () -> T): T = slots.withPermit {
        tasks.withKey(id) {
            active.add(id)
            try { action() } finally { active.remove(id) }
        }
    }
    suspend fun <T> publish(key: String, action: suspend () -> T): T = images.withKey(key, action)
}
