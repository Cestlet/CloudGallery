package com.cloudgallery.app

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.flow.first

/** Explicit emulator-only ACTION_CLICK probe; no coordinate/social target clicks. */
class XhsActionProbe : Instrumentation() {
    private var operation = "inspect"
    override fun onCreate(arguments: Bundle?) {
        operation = arguments?.getString("operation") ?: "inspect"
        start()
    }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (size >= 1000 || depth > 40) return
            add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        root?.let { visit(it, 0) }
    }
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".mock")) { "Mock build required" }
            if (operation == "importPipeline") {
                checkImportPipeline(targetContext, result)
                finish(0, result)
                return
            }
            if (operation == "galleryFeatures") {
                checkGalleryFeatures(targetContext, context, result)
                finish(0, result)
                return
            }
            if (operation == "heif") {
                checkHeif(result)
                finish(0, result)
                return
            }
            if (operation == "importRecovery") {
                checkImportRecovery(result)
                finish(0, result)
                return
            }
            if (operation == "captureWindow") {
                checkCaptureWindow(result)
                finish(0, result)
                return
            }
            var root = uiAutomation.rootInActiveWindow
            val deadline = SystemClock.uptimeMillis() + 2000
            while (root?.packageName?.toString() != "com.xingin.xhs" && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                root = uiAutomation.rootInActiveWindow
            }
            check(root?.packageName?.toString() == "com.xingin.xhs") { "XHS must be foreground" }
            val before = nodes(root)
            val chosen = when (operation) {
                "share" -> before.singleOrNull { it.viewIdResourceName == "com.xingin.xhs:id/moreOperateIV" }
                "copyChild" -> before.singleOrNull { it.contentDescription?.toString() == "复制链接" && it.className?.toString() == "android.widget.Button" }
                    ?.let { nodes(it).singleOrNull { child -> child.isClickable && child.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } } }
                "copy", "copyLong" -> before.singleOrNull { it.contentDescription?.toString() == "复制链接" && it.className?.toString() == "android.widget.Button" }
                else -> null
            }
            val report = JSONObject().put("operation", operation)
            if (operation != "inspect") {
                check(chosen != null && chosen.isVisibleToUser && chosen.isEnabled)
                report.put("clickable", chosen.isClickable).put("actions", JSONArray(chosen.actionList.map { it.id }))
                report.put("result", chosen.performAction(if (operation == "copyLong") AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(300)
            }
            val after = nodes(uiAutomation.rootInActiveWindow)
            report.put("packageAfter", uiAutomation.rootInActiveWindow?.packageName?.toString())
            report.put("copyButtons", after.count { it.contentDescription?.toString() == "复制链接" })
            if (operation.startsWith("copy")) {
                val clip = (targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                val text = clip?.getItemAt(0)?.text?.toString().orEmpty()
                report.put("newClipboard", (clip?.description?.timestamp ?: 0) > System.currentTimeMillis() - 5000)
                report.put("hasSupportedLink", text.contains("xiaohongshu.com/") || text.contains("xhslink.com/") || text.contains("xhslink.cn/"))
                File(targetContext.filesDir, "xhs-probe-link.txt").writeText(text)
            }
            File(targetContext.filesDir, "xhs-action-probe.json").writeText(report.toString(2))
            result.putString("report", report.toString())
            finish(0, result)
        } catch (error: Exception) {
            result.putString("error", "${error.javaClass.simpleName}: ${error.message}")
            finish(1, result)
        }
    }
    private fun checkHeif(result: Bundle) = kotlinx.coroutines.runBlocking {
        val file = File(targetContext.filesDir, "heif-test.heic")
        check(file.isFile) { "Push the HEIF codec fixture first" }
        val database = androidx.room.Room.inMemoryDatabaseBuilder(targetContext, com.cloudgallery.app.data.GalleryDatabase::class.java).build()
        try {
            val provider = object : com.cloudgallery.app.data.StorageProvider {
                override val sourceId = "heif-codec-test"
                override val rootPath = "/"
                override suspend fun list(path: String, page: Int, refresh: Boolean) = com.cloudgallery.app.data.DirectoryPage(
                    listOf("sample.heic", "sample.HEIF").map { com.cloudgallery.app.data.RemoteFile(it, false, file.length(), "1", "") }, false)
                override suspend fun original(path: String, remoteId: String) = file.toURI().toString()
                override fun absolute(url: String) = url
            }
            com.cloudgallery.app.data.GalleryScanner(database).scan(provider) { }
            val rows = database.gallery().observe(provider.sourceId).first()
            check(rows.size == 2) { "HEIF files were excluded from index" }
            val decoded = coil.Coil.imageLoader(targetContext).execute(coil.request.ImageRequest.Builder(targetContext)
                .data(file).allowHardware(false).build())
            check(decoded is coil.request.SuccessResult) { "HEIF image loader failed: $decoded" }
            val info = com.cloudgallery.app.data.readXhsImageInfo(file)
            check(info.width > 0 && info.height > 0) { "HEIF metadata decoder failed" }
            val preview = com.cloudgallery.app.data.decodeGalleryPreview(file)
            check(maxOf(preview.width, preview.height) <= 768)
            check(preview.width > 0 && preview.height > 0)
            preview.recycle()
            result.putString("report", "indexed=${rows.size}, decoded=${decoded.drawable.intrinsicWidth}x${decoded.drawable.intrinsicHeight}")
        } finally { database.close() }
    }
    private fun checkImportRecovery(result: Bundle) {
        val databaseName = "import-recovery-${java.util.UUID.randomUUID()}.db"
        val store = com.cloudgallery.app.data.QuickImportStore(targetContext, databaseName)
        val image = File(targetContext.cacheDir, "import-recovery-${java.util.UUID.randomUUID()}.jpg")
        try {
            val page = com.cloudgallery.app.data.XhsImagePage(1, 13)
            val queued = store.create("fixture", "/Other", page, false)
            check(store.queueCapture(queued, "https://xhslink.cn/fixture"))
            check(!store.failCapture(queued, "late timeout"))
            store.stageCapture(queued, "late window")
            check(store.get(queued)?.status == "queued")
            check(store.cancel(queued))
            check(!store.update(queued, "downloading", runningOnly = true))
            check(!store.update(queued, "failed", "late network failure", runningOnly = true))
            check(store.get(queued)?.status == "cancelled")
            check(store.get(queued)?.link?.isEmpty() == true)
            val completed = store.create("fixture", "/Other", page, false)
            check(store.queueCapture(completed, "https://xhslink.cn/fixture"))
            check(store.update(completed, "completed", runningOnly = true))
            check(!store.cancel(completed))
            check(!store.update(completed, "paused", runningOnly = true))
            val failed = store.create("fixture", "/Other", page, false)
            check(store.failCapture(failed, "window closed"))
            check(!store.queueCapture(failed, "https://xhslink.cn/stale"))
            check(store.get(failed)?.link?.isEmpty() == true)
            val interrupted = store.create("fixture", "/Other", page, false)
            store.stageCapture(interrupted, "已点击复制链接，等待系统打开取链接窗口")
            store.writableDatabase.execSQL("UPDATE tasks SET created = ? WHERE id = ?", arrayOf(System.currentTimeMillis() - 31_000, interrupted))
            store.expireCaptures()
            check(store.get(interrupted)?.status == "failed")
            check(store.get(interrupted)?.detail?.contains("等待系统打开") == true)
            val bitmap = android.graphics.Bitmap.createBitmap(64, 32, android.graphics.Bitmap.Config.ARGB_8888)
            image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
            android.media.ExifInterface(image.absolutePath).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
            }
            val info = com.cloudgallery.app.data.readXhsImageInfo(image)
            check(info.mime == "image/jpeg" && info.orientation == 6)
            val preview = com.cloudgallery.app.data.decodeGalleryPreview(image, 32)
            check(preview.width == 16 && preview.height == 32) { "Preview orientation or size incorrect" }
            preview.recycle()
            check(com.cloudgallery.app.data.sufficientOrientedXhsDimensions(info.width, info.height, 32, 64, info.orientation))
            check(!com.cloudgallery.app.data.sufficientOrientedXhsDimensions(info.width, info.height, 320, 640, info.orientation))
            result.putString("report", "handoff/cancel/completion-races=passed, interrupted-stage=preserved, rotated-jpeg-preview=16x32, thumbnail=rejected")
        } finally { store.close(); targetContext.deleteDatabase(databaseName); image.delete() }
    }
    private fun checkCaptureWindow(result: Bundle) {
        val graph = (targetContext.applicationContext as GalleryApplication).services
        check(graph.preferences.state.value.quickImport) { "Enable Quick import in the mock app first" }
        startActivitySync(android.content.Intent(targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val id = graph.quickImports.create("capture-test-no-such-account", "/Other", com.cloudgallery.app.data.XhsImagePage(1, 13), false)
        try {
            runOnMainSync {
                (targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    android.content.ClipData.newPlainText("Capture regression", "https://xhslink.cn/fixture-no-network"))
                targetContext.startActivity(android.content.Intent(targetContext, QuickImportCaptureActivity::class.java)
                    .putExtra("task", id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val deadline = SystemClock.uptimeMillis() + 6000
            var task = graph.quickImports.get(id)!!
            while (task.status != "failed" && SystemClock.uptimeMillis() < deadline) { SystemClock.sleep(50); task = graph.quickImports.get(id)!! }
            // A deliberately mismatched account makes the worker stop before any source or upload request.
            check(task.status == "failed" && task.detail.contains("账户已改变")) { "Handoff did not reach worker: ${task.status}, ${task.detail}" }
            val work = androidx.work.WorkManager.getInstance(targetContext).getWorkInfosForUniqueWork("quick-import-$id")
                .get(2, java.util.concurrent.TimeUnit.SECONDS)
            check(work.isNotEmpty()) { "Handoff was not persisted" }
            result.putString("report", "focused-clipboard=received, handoff=persisted, worker=started, network=none")
        } finally {
            androidx.work.WorkManager.getInstance(targetContext).cancelUniqueWork("quick-import-$id").result.get(2, java.util.concurrent.TimeUnit.SECONDS)
            graph.quickImports.writableDatabase.delete("tasks", "id = ?", arrayOf(id))
        }
    }
}
