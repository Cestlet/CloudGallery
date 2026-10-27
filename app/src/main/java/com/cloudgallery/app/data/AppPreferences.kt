package com.cloudgallery.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val theme: String = "system", val language: String = "system",
    val backup: Boolean = false, val allAlbums: Boolean = true,
    val albums: Set<String> = emptySet(), val backupMobile: Boolean = false,
    val videos: Boolean = false, val destination: String = "",
    val syncMobile: Boolean = false, val originalMobile: Boolean = false,
    val quickImport: Boolean = false, val quickDestination: String = "",
    val quickMobile: Boolean = false, val quickVibration: Boolean = true, val quickSaveLocal: Boolean = false
)

fun AppSettings.backupConfig() = copy(theme = "system", language = "system", syncMobile = false, originalMobile = false,
    quickImport = false, quickDestination = "", quickMobile = false, quickVibration = true, quickSaveLocal = false)

class AppPreferences(context: Context) {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    init {
        // Old opt-in covered an unsafe click implementation. Require explicit re-enablement once.
        if (!prefs.getBoolean("semanticImportMigrated", false)) prefs.edit()
            .putBoolean("semanticImportMigrated", true).putBoolean("quickImport", false).apply()
    }
    private fun read() = AppSettings(
        prefs.getString("theme", "system")!!, prefs.getString("language", "system")!!,
        prefs.getBoolean("backup", false), prefs.getBoolean("allAlbums", true),
        prefs.getStringSet("albums", emptySet())!!.toSet(), prefs.getBoolean("backupMobile", false),
        prefs.getBoolean("videos", false), prefs.getString("destination", "")!!,
        prefs.getBoolean("syncMobile", false), prefs.getBoolean("originalMobile", false),
        prefs.getBoolean("quickImport", false), prefs.getString("quickDestination", "")!!,
        prefs.getBoolean("quickMobile", false), prefs.getBoolean("quickVibration", true), prefs.getBoolean("quickSaveLocal", false))
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(mutable.value)
        check(prefs.edit().putString("theme", next.theme).putString("language", next.language)
            .putBoolean("backup", next.backup).putBoolean("allAlbums", next.allAlbums)
            .putStringSet("albums", next.albums).putBoolean("backupMobile", next.backupMobile)
            .putBoolean("videos", next.videos).putString("destination", next.destination)
            .putBoolean("syncMobile", next.syncMobile).putBoolean("originalMobile", next.originalMobile)
            .putBoolean("quickImport", next.quickImport).putString("quickDestination", next.quickDestination)
            .putBoolean("quickMobile", next.quickMobile).putBoolean("quickVibration", next.quickVibration)
            .putBoolean("quickSaveLocal", next.quickSaveLocal).commit())
        mutable.value = next
    }
}
