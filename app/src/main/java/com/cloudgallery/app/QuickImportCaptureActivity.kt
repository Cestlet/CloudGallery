package com.cloudgallery.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.cloudgallery.app.data.*
import java.util.concurrent.Executors

/** Brief transparent focus window for the freshly copied link; no screenshot or clipboard polling. */
class QuickImportCaptureActivity : Activity() {
    private var handled = false
    private var reading = false
    private var readDeadline = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val graph get() = (application as GalleryApplication).services
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        intent.getStringExtra("task")?.let { id ->
            graph.quickImports.get(id)?.takeIf { it.status == "capturing" }?.let {
                graph.quickImports.stageCapture(id, "取链接窗口已打开，正在等待焦点")
            }
        }
        handler.postDelayed({ if (!handled) fail("未能获得窗口焦点，请重试") }, 3000)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        if (!reading) {
            reading = true
            readDeadline = android.os.SystemClock.uptimeMillis() + 900
            readClipboard()
        }
    }
    private fun readClipboard() {
        if (handled || isFinishing) return
        if (!hasWindowFocus()) {
            if (android.os.SystemClock.uptimeMillis() < readDeadline) handler.postDelayed({ readClipboard() }, 40)
            else fail("未能获得窗口焦点，请重试")
            return
        }
        val id = intent.getStringExtra("task") ?: run { finish(); return }
        val clip = try { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip }
            catch (_: SecurityException) { null }
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().take(100_000)
        val url = XhsSource.shareUrl(text)
        val timestamp = clip?.description?.timestamp ?: 0
        val task = graph.quickImports.get(id) ?: run { finish(); return }
        if (url == null || timestamp < task.created) {
            if (android.os.SystemClock.uptimeMillis() < readDeadline) {
                handler.postDelayed({ readClipboard() }, 40)
                return
            }
            fail(if (timestamp == 0L) "系统未提供新复制的链接，请确认剪贴板权限后重试" else "未取得新复制的帖子链接，请重试")
            return
        }
        handled = true
        Executors.newSingleThreadExecutor().let { executor -> executor.execute {
            try {
                if (task.status != "capturing" || !graph.preferences.state.value.quickImport) {
                    if (graph.quickImports.failCapture(id, "导入已取消或快速导图已关闭")) quickFeedback(this, false)
                } else {
                    if (graph.quickImports.queueCapture(id, url)) {
                        graph.preferences.prefs.edit().putString("quickStatus", "图片已加入导入队列").apply()
                        // Keep the focus Activity alive until WorkManager has persisted
                        // the handoff. Finishing first can let a ROM kill the executor.
                        val operation = scheduleQuickImport(this, id)
                        if (operation != null) {
                            operation.result.get(2, java.util.concurrent.TimeUnit.SECONDS)
                            quickFeedback(this, true, accepted = true)
                        }
                    }
                }
            } catch (_: Exception) {
                if (graph.quickImports.failCapture(id, "取链接交接失败，请重新按双音量键")) quickFeedback(this, false)
                else if (graph.quickImports.get(id)?.status == "queued") {
                    // The link is already durable. Keep it for onForeground recovery.
                    graph.preferences.prefs.edit().putString("quickStatus", "链接已保存，打开图库后将继续导入").apply()
                }
            } finally { handler.post { closeWindow() }; executor.shutdown() }
        } }
    }
    private fun closeWindow() {
        finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
    private fun fail(reason: String) {
        handled = true
        val id = intent.getStringExtra("task")
        if (id != null) Executors.newSingleThreadExecutor().let { executor -> executor.execute {
            try { if (graph.quickImports.failCapture(id, reason)) quickFeedback(this, false) } finally { executor.shutdown() }
        } }
        finish()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!handled && !isChangingConfigurations) fail("取链接窗口已关闭，未完成读取，请重试")
        super.onDestroy()
    }
}
