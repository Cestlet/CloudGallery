package com.cloudgallery.app

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.cloudgallery.app.data.*
import kotlinx.coroutines.runBlocking

internal fun checkImportPipeline(context: Context, result: Bundle) = runBlocking {
    val graph = (context.applicationContext as GalleryApplication).services
    check(BuildConfig.MOCK_BAIDU && graph.preferences.state.value.quickImport)
    val account = graph.store.loadBaidu() ?: error("Mock account required")
    val destination = "/Other/Regression-${System.currentTimeMillis()}"
    val provider = graph.provider(account, "/")
    provider.createFolder(destination)
    val tasks = mutableListOf<String>()
    val start = SystemClock.elapsedRealtime()
    try {
        for (page in listOf(1, 2, 1)) {
            val id = graph.quickImports.create(account.id, destination, XhsImagePage(page, 2), false)
            tasks.add(id)
            check(graph.quickImports.queueCapture(id, BuildConfig.BAIDU_API + "discovery/item/000000000000000000000001"))
            scheduleQuickImport(context, id)!!.result.get(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = tasks.map { graph.quickImports.get(it)!! }
            check(status.none { it.status == "failed" }) { "Pipeline failed: ${status.map { it.detail }}" }
            if (status.all { it.status == "completed" }) break
            kotlinx.coroutines.delay(80)
        }
        check(tasks.all { graph.quickImports.get(it)?.status == "completed" }) { "Pipeline timed out" }
        check(tasks.all { graph.quickImports.get(it)?.extension == "png" || graph.quickImports.get(it)?.detail?.contains("已导入") == true })
        val files = provider.list(destination, 1).files
        check(files.size == 2 && files.all { it.name.endsWith(".png") }) { "Duplicate upload or missing image" }
        check(graph.preferences.prefs.getString("quickImageDiagnostic", "")!!.contains("source=2")) { "Expected full-size fallback was not used" }
        result.putString("report", "3 queued imports completed; undersized source rejected; explicit full-size fallback accepted; 2 unique cloud files; duration=${SystemClock.elapsedRealtime() - start}ms")
    } finally {
        tasks.forEach { id ->
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("quick-import-$id").result.get(3, java.util.concurrent.TimeUnit.SECONDS)
            graph.quickImports.writableDatabase.delete("tasks", "id = ?", arrayOf(id))
        }
    }
}
