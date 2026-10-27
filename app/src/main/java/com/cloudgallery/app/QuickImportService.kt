package com.cloudgallery.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.cloudgallery.app.data.*
import kotlinx.coroutines.*

/** Only semantic ACTION_CLICK targets. Gesture/coordinate fallbacks are forbidden. */
class QuickImportService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val chord = VolumeChord()
    private val keyTrace = ArrayDeque<String>()
    private val captureTrace = ArrayDeque<String>()
    private var captureStarted = 0L
    private var captureSession = 0L
    private var capture: Job? = null
    private val graph get() = (application as GalleryApplication).services

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        recordKeys("service-connected; waiting-for-volume-keys")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { capture?.cancel(); chord.reset() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in setOf(VolumeChord.UP, VolumeChord.DOWN)) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (!graph.preferences.state.value.quickImport) { chord.reset(); return false }
        if (event.repeatCount == 0) recordKeys("${event.eventTime}: ${if (event.keyCode == VolumeChord.UP) "UP" else "DOWN"} ${if (event.action == KeyEvent.ACTION_DOWN) "pressed" else "released"}")
        // Volume UI can briefly replace the active root between presses. Always
        // process releases and recognize the chord before inspecting any windows.
        val triggered = chord.accept(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.eventTime, event.repeatCount > 0)
        if (triggered) {
            recordKeys("chord-recognized")
            if (capture?.isActive == true) recordKeys("capture-already-running")
            else capture = scope.launch {
                // Wait only for a transient root; never click without fresh XHS validation.
                val root = withTimeoutOrNull(240) {
                    var current = xhsRoot()
                    while (current == null) { delay(40); current = xhsRoot() }
                    current
                }
                if (root == null) recordKeys("chord-rejected: no-foreground-xhs-window")
                else { recordKeys("foreground-xhs-confirmed"); captureCurrent() }
            }
        }
        return false
    }
    private fun recordKeys(message: String) {
        if (keyTrace.size >= 12) keyTrace.removeFirst()
        keyTrace.addLast(message)
        graph.preferences.prefs.edit().putString("quickKeyDiagnostic",
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}, app ${BuildConfig.VERSION_NAME}\n" +
                "session=${System.currentTimeMillis()}, chordWindow=250ms\n" + keyTrace.joinToString("\n")).apply()
    }
    private fun recordCapture(message: String) {
        if (captureTrace.size >= 16) captureTrace.removeFirst()
        captureTrace.addLast("+${SystemClock.uptimeMillis() - captureStarted}ms $message")
        graph.preferences.prefs.edit().putString("quickCaptureDiagnostic",
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}, app ${BuildConfig.VERSION_NAME}\n" +
                "capture=$captureSession\n" + captureTrace.joinToString("\n")).apply()
    }
    private fun xhsRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == XHS) return active
        val top = windows.sortedByDescending { it.layer }
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { it.root }.firstOrNull { it?.packageName?.toString() != "com.android.systemui" }
        return top?.takeIf { allowsXhsForeground(active?.packageName?.toString(), it.packageName?.toString()) }
    }
    private data class Tree(val actual: List<AccessibilityNodeInfo>, val metadata: List<ImportActionNode>)
    private fun tree(root: AccessibilityNodeInfo): Tree {
        val actual = mutableListOf<AccessibilityNodeInfo>()
        val metadata = mutableListOf<ImportActionNode>()
        fun visit(node: AccessibilityNodeInfo, depth: Int): Int {
            if (actual.size >= 1000 || depth > 40) throw QuickImportError("页面节点过多，已停止操作")
            val index = actual.size
            actual.add(node)
            metadata.add(ImportActionNode())
            val children = (0 until node.childCount).mapNotNull { node.getChild(it) }.map { visit(it, depth + 1) }
            metadata[index] = ImportActionNode(listOfNotNull(node.text?.toString(), node.contentDescription?.toString()),
                node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
                node.isEnabled, node.isVisibleToUser, node.packageName?.toString().orEmpty(), children,
                node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty())
            return index
        }
        visit(root, 0)
        return Tree(actual, metadata)
    }
    private fun page(root: AccessibilityNodeInfo): XhsImagePage? = tree(root).actual
        .filter { it.isVisibleToUser }.mapNotNull { xhsImagePage(it.contentDescription?.toString().orEmpty()) }
        .distinct().singleOrNull()
    private fun diagnostic(stage: String, root: AccessibilityNodeInfo, metadata: List<ImportActionNode> = tree(root).metadata) {
        val info = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}, app ${BuildConfig.VERSION_NAME}\n" +
            "capture=$captureSession, time=${System.currentTimeMillis()}, stage=$stage\n" + importActionDiagnostic(metadata)
        graph.preferences.prefs.edit().putString("quickDiagnostic", info).apply()
    }
    private fun target(root: AccessibilityNodeInfo, action: ImportAction): AccessibilityNodeInfo? {
        val tree = tree(root)
        return importActionTarget(tree.metadata, action)?.let { tree.actual[it] }
    }
    private suspend fun click(action: ImportAction, expectedPage: XhsImagePage? = null) {
        val deadline = SystemClock.uptimeMillis() + 360
        do {
            currentCoroutineContext().ensureActive()
            if (!graph.preferences.state.value.quickImport) throw QuickImportError("快速导图已关闭")
            val current = xhsRoot()
            if (current != null && current.refresh()) {
                val observedPage = if (expectedPage != null) page(current) else null
                if (expectedPage != null && observedPage != null && observedPage != expectedPage)
                    throw QuickImportError("图片页已变化，已停止操作")
                if (expectedPage != null && observedPage == null) { delay(40); continue }
                // Always select from the latest complete tree. A previously selected
                // AccessibilityNodeInfo identity is not stable during menu animations.
                val snapshot = tree(current)
                val fresh = importActionTarget(snapshot.metadata, action)?.let { snapshot.actual[it] }
                if (fresh != null) {
                    val clicked = fresh.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    diagnostic("${action.name.lowercase()}-click", current, snapshot.metadata)
                    recordCapture("${action.name.lowercase()} ACTION_CLICK=$clicked")
                    if (!clicked)
                        throw QuickImportError("小红书不支持该按钮的无障碍点击，已停止操作")
                    return
                }
            }
            delay(40)
        } while (SystemClock.uptimeMillis() < deadline)
        recordCapture("${action.name.lowercase()} click-target-timeout")
        throw QuickImportError("按钮尚未稳定或无法确认，已停止操作")
    }
    private suspend fun awaitTarget(action: ImportAction, timeout: Long): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + timeout
        var recorded = false
        do {
            currentCoroutineContext().ensureActive()
            if (!graph.preferences.state.value.quickImport) throw QuickImportError("快速导图已关闭")
            xhsRoot()?.let {
                val found = target(it, action)
                if (!recorded || found != null) {
                    diagnostic(action.name.lowercase(), it)
                    recorded = true
                }
                found
            }?.let { return it }
            delay(40)
        } while (SystemClock.uptimeMillis() < end)
        xhsRoot()?.let { diagnostic("${action.name.lowercase()}-timeout", it) }
        recordCapture("${action.name.lowercase()} target-not-found")
        return null
    }
    private suspend fun captureCurrent() {
        var id: String? = null
        val started = SystemClock.uptimeMillis()
        captureStarted = started
        captureSession = System.currentTimeMillis()
        captureTrace.clear()
        recordCapture("capture-start")
        try {
            val root = xhsRoot() ?: throw QuickImportError("请在小红书图片页使用双音量键")
            diagnostic("image", root)
            val initial = page(root) ?: throw QuickImportError("当前页面不是可识别的图片页")
            recordCapture("image-page=${initial.index}/${initial.total}")
            if (target(root, ImportAction.COPY) != null) throw QuickImportError("请先关闭分享菜单，再使用双音量键")
            val config = graph.preferences.state.value
            val account = withContext(Dispatchers.IO) { graph.store.loadBaidu() } ?: throw QuickImportError("请先连接百度网盘")
            if (config.quickDestination.isBlank()) throw QuickImportError("请先选择快速导图目标目录")
            id = withContext(Dispatchers.IO) {
                if (graph.quickImports.pending().size >= 20) throw QuickImportError("导入队列已满")
                graph.quickImports.create(account.id, config.quickDestination, initial, config.quickSaveLocal)
            }
            val before = xhsRoot() ?: throw QuickImportError("已离开小红书")
            if (page(before) != initial) throw QuickImportError("图片页已变化，停止操作")
            click(ImportAction.SHARE, initial)
            withContext(Dispatchers.IO) { graph.quickImports.stageCapture(id, "分享菜单已打开，正在复制链接") }
            awaitTarget(ImportAction.COPY, 1200)
                ?: throw QuickImportError("无法确认独立复制链接按钮，已停止操作")
            click(ImportAction.COPY)
            delay(60)
            val after = withTimeoutOrNull(320) {
                var current = xhsRoot()
                while (current == null) { delay(40); current = xhsRoot() }
                current
            } ?: throw QuickImportError("复制后离开小红书，已停止操作")
            val afterPage = page(after)
            if (afterPage != null && afterPage != initial) throw QuickImportError("图片页已变化，停止导入")
            // Back only when the same validated copy sheet remains in the foreground.
            if (target(after, ImportAction.COPY) != null) {
                recordCapture("close-copy-sheet=${performGlobalAction(GLOBAL_ACTION_BACK)}")
            }
            withContext(Dispatchers.IO) { graph.quickImports.stageCapture(id, "已点击复制链接，等待系统打开取链接窗口") }
            startActivity(Intent(this, QuickImportCaptureActivity::class.java).putExtra("task", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            recordCapture("clipboard-window-requested")
            android.util.Log.i("QuickImport", "Clipboard capture requested in ${SystemClock.uptimeMillis() - started} ms")
            val completed = withTimeoutOrNull(4500) {
                while (withContext(Dispatchers.IO) { graph.quickImports.get(id)?.status } == "capturing") delay(80)
                true
            } == true
            if (!completed && withContext(Dispatchers.IO) {
                graph.quickImports.failCapture(id, "系统未完成取链接窗口交接，请重新按双音量键")
            }) quickFeedback(this, false)
            val finalTask = withContext(Dispatchers.IO) { graph.quickImports.get(id) }
            recordCapture("handoff status=${finalTask?.status ?: "missing"}" +
                if (finalTask?.status == "failed") "; ${finalTask.detail}" else "")
        } catch (error: Exception) {
            val reason = if (error is QuickImportError) error.reason else "取链接被中断，请重试"
            recordCapture("stopped: $reason")
            val failed = id?.let { task -> withContext(NonCancellable + Dispatchers.IO) { graph.quickImports.failCapture(task, reason) } } ?: true
            if (failed) {
                graph.preferences.prefs.edit().putString("quickStatus", reason).apply()
                quickFeedback(this, false)
            }
            android.util.Log.w("QuickImport", "Capture stopped after ${SystemClock.uptimeMillis() - started} ms: ${error.javaClass.simpleName}")
            if (error is CancellationException) throw error
        }
    }
    companion object { private const val XHS = "com.xingin.xhs" }
}
