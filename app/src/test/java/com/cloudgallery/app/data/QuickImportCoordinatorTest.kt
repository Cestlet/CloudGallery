package com.cloudgallery.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class QuickImportCoordinatorTest {
    @Test fun distinctTasksOverlapButNeverExceedTwoTransfers() = runBlocking {
        val coordinator = QuickImportCoordinator()
        val count = AtomicInteger(); val peak = AtomicInteger()
        (1..6).map { id -> async {
            coordinator.transfer(id.toString()) {
                peak.updateAndGet { maxOf(it, count.incrementAndGet()) }
                delay(30); count.decrementAndGet()
            }
        } }.awaitAll()
        assertEquals(2, peak.get())
        assertTrue(coordinator.activeTasks().isEmpty())
    }
    @Test fun replacedTaskCannotWriteSameTemporaryFileConcurrently() = runBlocking {
        val coordinator = QuickImportCoordinator()
        val count = AtomicInteger(); val peak = AtomicInteger()
        (1..3).map { async {
            coordinator.transfer("same-task") {
                peak.updateAndGet { maxOf(it, count.incrementAndGet()) }
                delay(20); count.decrementAndGet()
            }
        } }.awaitAll()
        assertEquals(1, peak.get())
    }
    @Test fun duplicateImagesCannotPublishAlbumAndCloudConcurrently() = runBlocking {
        val coordinator = QuickImportCoordinator()
        val count = AtomicInteger(); val peak = AtomicInteger()
        (1..3).map { async {
            coordinator.publish("same-file") {
                peak.updateAndGet { maxOf(it, count.incrementAndGet()) }
                delay(20); count.decrementAndGet()
            }
        } }.awaitAll()
        assertEquals(1, peak.get())
    }
    @Test fun cancellationReleasesSlotAndActiveFileProtection() = runBlocking {
        val coordinator = QuickImportCoordinator()
        val started = CompletableDeferred<Unit>()
        val job = launch { coordinator.transfer("cancel") { started.complete(Unit); awaitCancellation() } }
        started.await(); assertTrue("cancel" in coordinator.activeTasks())
        job.cancelAndJoin()
        assertTrue(coordinator.activeTasks().isEmpty())
        withTimeout(1000) { coordinator.transfer("after") { } }
    }
}
