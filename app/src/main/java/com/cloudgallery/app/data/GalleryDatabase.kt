package com.cloudgallery.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media", primaryKeys = ["source", "path"], indices = [Index(value = ["source", "modified"])])
data class IndexedMedia(val source: String, val path: String, val name: String, val album: String,
    val modified: String, val size: Long, val thumb: String, val run: String,
    @ColumnInfo(defaultValue = "''") val remoteId: String = "")

@Entity(tableName = "albums", primaryKeys = ["source", "path"])
data class IndexedAlbum(val source: String, val path: String, val run: String)

@Entity(tableName = "pending", primaryKeys = ["source", "path"])
data class PendingDirectory(val source: String, val path: String, val page: Int = 1)

@Entity(tableName = "sync")
data class ScanState(@PrimaryKey val source: String, val run: String, val finished: Boolean = false,
    val lastSuccess: Long = 0)

@Dao
interface GalleryDao {
    @Query("SELECT * FROM media WHERE source = :source ORDER BY modified DESC, path ASC")
    fun observe(source: String): Flow<List<IndexedMedia>>
    @Query("SELECT * FROM albums WHERE source = :source ORDER BY path")
    fun observeAlbums(source: String): Flow<List<IndexedAlbum>>
    @Upsert suspend fun putAlbums(items: List<IndexedAlbum>)
    @Query("DELETE FROM albums WHERE source = :source AND run != :run")
    suspend fun pruneAlbums(source: String, run: String)
    @Upsert suspend fun putMedia(items: List<IndexedMedia>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(item: PendingDirectory)
    @Upsert suspend fun advance(item: PendingDirectory)
    @Query("SELECT * FROM pending WHERE source = :source ORDER BY path LIMIT 1")
    suspend fun next(source: String): PendingDirectory?
    @Query("DELETE FROM pending WHERE source = :source AND path = :path")
    suspend fun completeDirectory(source: String, path: String)
    @Query("SELECT * FROM sync WHERE source = :source") suspend fun state(source: String): ScanState?
    @Upsert suspend fun saveState(state: ScanState)
    @Query("DELETE FROM media WHERE source = :source AND run != :run")
    suspend fun prune(source: String, run: String)
}

val albumMigration = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS albums (source TEXT NOT NULL, path TEXT NOT NULL, run TEXT NOT NULL, PRIMARY KEY(source, path))")
        db.execSQL("INSERT OR IGNORE INTO albums (source, path, run) SELECT source, album, run FROM media")
    }
}

@Database(entities = [IndexedMedia::class, PendingDirectory::class, ScanState::class, IndexedAlbum::class], version = 3)
abstract class GalleryDatabase : RoomDatabase() { abstract fun gallery(): GalleryDao }
