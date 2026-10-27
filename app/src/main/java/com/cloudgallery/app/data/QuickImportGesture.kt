package com.cloudgallery.app.data

/** Recognizes overlapping presses. A held/repeating key is never a second press. */
internal class VolumeChord(private val windowMs: Long = 250) {
    private val held = mutableMapOf<Int, Long>()
    private var latched = false

    fun reset() { held.clear(); latched = false }

    fun accept(key: Int, down: Boolean, time: Long, repeated: Boolean = false): Boolean {
        if (key != UP && key != DOWN) return false
        if (!down) {
            held.remove(key)
            if (held.isEmpty()) latched = false
            return false
        }
        if (repeated || key in held) return false
        held[key] = time
        if (latched || held.size != 2) return false
        val difference = kotlin.math.abs(held.getValue(UP) - held.getValue(DOWN))
        if (difference > windowMs) return false
        latched = true
        return true
    }

    companion object { const val UP = 24; const val DOWN = 25 }
}

internal data class XhsImagePage(val index: Int, val total: Int)

/** Only bypass a known system volume window; a different foreground app blocks capture. */
internal fun allowsXhsForeground(activePackage: String?, topAppPackage: String?): Boolean =
    activePackage in setOf(null, "com.xingin.xhs", "com.android.systemui") && topAppPackage == "com.xingin.xhs"

/** Only matches the image description observed in XHS 9.33.4, not arbitrary page text. */
internal fun xhsImagePage(description: String): XhsImagePage? {
    val match = Regex("^图片[,，]第(\\d+)张[,，]共(\\d+)张(?:[,，].*)?$").matchEntire(description) ?: return null
    val index = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].toIntOrNull() ?: return null
    return if (total in 1..1000 && index in 1..total) XhsImagePage(index, total) else null
}
