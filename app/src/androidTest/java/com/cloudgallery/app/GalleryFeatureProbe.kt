package com.cloudgallery.app

import android.content.Context
import android.os.Bundle
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.room.Room
import com.cloudgallery.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.json.JSONObject

internal fun checkGalleryFeatures(target: Context, test: Context, result: Bundle) = runBlocking {
    val name = "gallery-feature-probe-${UUID.randomUUID()}.db"
    val file = target.getDatabasePath(name)
    file.parentFile!!.mkdirs()
    val schema = JSONObject(test.assets.open("gallery-v2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        val tables = schema.getJSONArray("entities")
        for (i in 0 until tables.length()) {
            val entity = tables.getJSONObject(i)
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            val indices = entity.getJSONArray("indices")
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
        }
        db.execSQL("INSERT INTO media (source,path,name,album,modified,size,thumb,run,remoteId) VALUES ('old','/Photos/old.png','old.png','/Photos','2024-01-01T00:00:00Z',1,'','old-run','12')")
        db.version = 2
    }
    var database = Room.databaseBuilder(target, GalleryDatabase::class.java, name).addMigrations(albumMigration).build()
    val saved = mutableListOf<android.net.Uri>()
    val temp = File.createTempFile("gallery-probe-", ".image", target.cacheDir)
    try {
        check(database.gallery().observe("old").first().single().remoteId == "12") { "Migration lost existing media" }
        check(database.gallery().observeAlbums("old").first().single().path == "/Photos") { "Migration lost album" }
        var phase = 0
        val provider = object : StorageProvider {
            override val sourceId = "empty-folders"
            override val rootPath = "/Photos"
            override suspend fun list(path: String, page: Int, refresh: Boolean): DirectoryPage {
                if (phase == 1) error("simulated network failure")
                return DirectoryPage(if (phase == 0 && path == rootPath) listOf(RemoteFile("Empty", true, 0, "", "")) else emptyList(), false)
            }
            override suspend fun original(path: String, remoteId: String) = error("No images")
            override fun absolute(url: String) = url
        }
        GalleryScanner(database).scan(provider) { }
        check(database.gallery().observeAlbums(provider.sourceId).first().map { it.path }.toSet() == setOf("/Photos", "/Photos/Empty"))
        database.close()
        database = Room.databaseBuilder(target, GalleryDatabase::class.java, name).build()
        check(database.gallery().observeAlbums(provider.sourceId).first().size == 2) { "Empty albums did not survive restart" }
        phase = 1
        runCatching { GalleryScanner(database).scan(provider) { } }
        check(database.gallery().observeAlbums(provider.sourceId).first().size == 2) { "Failed scan removed cached albums" }
        phase = 2
        GalleryScanner(database).scan(provider) { }
        check(database.gallery().observeAlbums(provider.sourceId).first().single().path == "/Photos") { "Removed remote folder was not pruned" }
        check(canSaveQuickImage(target)) { "Grant storage permission through download UI first" }
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        for ((format, ext) in listOf(Bitmap.CompressFormat.PNG to "png", Bitmap.CompressFormat.JPEG to "jpg")) {
            temp.outputStream().use { check(bitmap.compress(format, 95, it)) }
            val uri = saveQuickImage(target, temp, "probe-${UUID.randomUUID()}.$ext", ext) { }
            saved.add(uri)
            check(target.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(temp.readBytes())) { "Original bytes changed" }
        }
        bitmap.recycle()
        val heif = File(target.filesDir, "heif-test.heic")
        check(heif.exists()) { "HEIF fixture is missing" }
        val heifUri = saveQuickImage(target, heif, "probe-${UUID.randomUUID()}.heic", "heic") { }
        saved.add(heifUri)
        check(target.contentResolver.openInputStream(heifUri)!!.use { it.readBytes() }.contentEquals(heif.readBytes()))
        val failedName = "probe-failed-${UUID.randomUUID()}.png"
        check(runCatching { saveQuickImage(target, temp, failedName, "png") { error("cancelled during copy") } }.isFailure)
        target.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(failedName), null)!!.use {
            check(it.count == 0) { "Failed save left incomplete MediaStore entry" }
        }
        result.putString("report", "v2→v3 retained media; empty albums persisted; failed scan retained albums; successful scan pruned removed folder; PNG/JPEG/HEIC saved byte-identically; failed save cleaned up")
    } finally {
        saved.forEach { target.contentResolver.delete(it, null, null) }
        temp.delete()
        database.close()
        target.deleteDatabase(name)
    }
}
