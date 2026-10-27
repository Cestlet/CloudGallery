package com.cloudgallery.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.cloudgallery.app.data.VolumeChord
import com.cloudgallery.app.data.xhsImagePage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Mock flavor only: observe metadata, never click, capture screens, read clipboard or upload. */
class XhsSourceProbeService : AccessibilityService() {
    private val chord = VolumeChord()
    private var lastReport = 0L
    private var keyCount = 0
    private var chordCount = 0
    private var receivedKeys = 0

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.xingin.xhs") return
        if (SystemClock.elapsedRealtime() - lastReport >= 1000) report()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        receivedKeys++
        File(filesDir, "xhs-key-probe.json").writeText(JSONObject().put("receivedVolumeEvents", receivedKeys)
            .put("xhsForeground", xhsRoot() != null).put("flags", serviceInfo.flags).toString())
        if (xhsRoot() == null) {
            chord.reset()
            return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            keyCount++
            if (chord.accept(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.eventTime, event.repeatCount > 0)) chordCount++
            report()
        }
        // Diagnostic mode does not consume either key or modify the user's volume settings.
        return false
    }

    private fun report() {
        val root = xhsRoot() ?: return
        lastReport = SystemClock.elapsedRealtime()
        val images = JSONArray()
        val identifiers = mutableSetOf<String>()
        val urlHosts = mutableSetOf<String>()
        val extraKeys = mutableSetOf<String>()
        var nodes = 0
        fun inspect(node: AccessibilityNodeInfo) {
            if (++nodes > 1000) return
            val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            values.forEach { value ->
                xhsImagePage(value)?.let { images.put(JSONObject().put("index", it.index).put("total", it.total)) }
                Regex("https?://[a-zA-Z0-9.-]+").findAll(value).forEach { urlHosts.add(it.value.substringAfter("://")) }
                Regex("/(?:explore|discovery/item)/([0-9a-f]{24})").findAll(value).forEach { identifiers.add(it.groupValues[1]) }
            }
            extraKeys.addAll(node.extras.keySet())
            extraKeys.addAll(node.availableExtraData)
            // Only retain a directly exposed note identifier; no arbitrary extra values or user text.
            for (key in listOf("noteId", "note_id")) {
                val value = node.extras.getString(key)
                if (value != null && Regex("[0-9a-f]{24}").matches(value)) identifiers.add(value)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { inspect(it) }
        }
        inspect(root)
        val json = JSONObject().put("nodeCount", nodes).put("images", images)
            .put("noteIds", JSONArray(identifiers.toList())).put("urlHosts", JSONArray(urlHosts.toList()))
            .put("extraKeys", JSONArray(extraKeys.toList())).put("volumeKeyEvents", keyCount).put("chords", chordCount)
            .put("serviceFlags", serviceInfo.flags)
        File(filesDir, "xhs-source-probe.json").writeText(json.toString(2))
    }

    private fun xhsRoot(): AccessibilityNodeInfo? {
        // Volume controls are a system overlay, not a change of the foreground application.
        val topApplication = windows.sortedByDescending { it.layer }
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root ?: rootInActiveWindow
        return topApplication?.takeIf { it.packageName?.toString() == "com.xingin.xhs" }
    }

    override fun onInterrupt() { chord.reset() }
}
