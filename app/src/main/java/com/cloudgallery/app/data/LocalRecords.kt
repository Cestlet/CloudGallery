package com.cloudgallery.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TrashEntry(val source: String, val path: String, val name: String, val deleted: Long)

/** Removal is local to the gallery; cloud originals are never destroyed. */
class LocalRecords(context: Context) : SQLiteOpenHelper(context, "local_records.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE trash(source TEXT, path TEXT, name TEXT, deleted INTEGER, PRIMARY KEY(source,path))")
        db.execSQL("CREATE TABLE history(source TEXT, path TEXT, viewed INTEGER, PRIMARY KEY(source,path))")
        db.execSQL("CREATE VIRTUAL TABLE search USING fts4(source, path, name, album)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun trash(source: String): List<TrashEntry> {
        return readableDatabase.rawQuery("SELECT source,path,name,deleted FROM trash WHERE source=? AND deleted>=? ORDER BY deleted DESC", arrayOf(source, (System.currentTimeMillis() - 30L * 86400000).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(TrashEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))) }
        }
    }
    fun hiddenPaths(source: String): Set<String> = readableDatabase.rawQuery("SELECT path FROM trash WHERE source=?", arrayOf(source)).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    fun remove(source: String, photo: MediaItem) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO trash VALUES(?,?,?,?)", arrayOf<Any>(source, photo.path, photo.name, System.currentTimeMillis()))
    }
    fun restore(source: String, path: String) { writableDatabase.delete("trash", "source=? AND path=?", arrayOf(source, path)) }
    fun viewed(source: String, path: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO history VALUES(?,?,?)", arrayOf<Any>(source, path, System.currentTimeMillis())) }
    fun clearHistory() { writableDatabase.delete("history", null, null) }
    fun rebuild(source: String, media: List<MediaItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("search", "source=?", arrayOf(source))
            media.forEach { db.execSQL("INSERT INTO search VALUES(?,?,?,?)", arrayOf(source, it.path, it.name, it.album)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
