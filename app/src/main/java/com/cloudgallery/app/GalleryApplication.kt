package com.cloudgallery.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.*
import com.cloudgallery.app.data.*
import com.cloudgallery.app.data.baidu.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.time.LocalDate
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

class GalleryApplication : Application() {
    val services by lazy { GalleryServices(this) }
    override fun onCreate() {
        super.onCreate()
        java.util.concurrent.Executors.newSingleThreadExecutor().let { executor ->
            executor.execute {
                try {
                    resumeQuickImports(this)
                    val prefs = services.preferences.prefs
                    if (!prefs.getBoolean("heifIndexUpdated", false) && services.store.loadBaidu()?.root != null) {
                        val network = if (services.preferences.state.value.syncMobile) NetworkType.CONNECTED else NetworkType.UNMETERED
                        WorkManager.getInstance(this).enqueueUniqueWork("heif-index-refresh", ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<GallerySyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(network).build()).build())
                        prefs.edit().putBoolean("heifIndexUpdated", true).apply()
                    }
                } finally { executor.shutdown() }
            }
        }
    }
}

class GalleryServices(context: Context) {
    val database = Room.databaseBuilder(context, GalleryDatabase::class.java, "gallery.db")
        .addMigrations(object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN remoteId TEXT NOT NULL DEFAULT ''")
            }
        }, albumMigration).build()
    val store = BaiduAccountStore(context)
    val preferences = AppPreferences(context)
    val records = LocalRecords(context)
    val photos = PhotoCache(context, preferences)
    val backupLock = Mutex()
    internal val manualUploadLock = Mutex()
    internal val quickImports = QuickImportStore(context)
    internal val quickCoordinator = QuickImportCoordinator()
    val scanLock = Mutex()
    val accountLock = Mutex()
    val configured get() = BuildConfig.BAIDU_APP_KEY.isNotBlank() && BuildConfig.BAIDU_SECRET.isNotBlank()
    fun oauth() = BaiduOAuth(BuildConfig.BAIDU_APP_KEY, BuildConfig.BAIDU_SECRET, BuildConfig.BAIDU_OAUTH.toHttpUrl())
    private val sessions = mutableMapOf<String, BaiduSession>()
    @Synchronized fun resetSession(id: String) { sessions.remove(id) }
    @Synchronized fun provider(account: BaiduAccount, root: String): BaiduProvider {
        val session = sessions.getOrPut(account.id) {
            BaiduSession(account.tokens, oauth(), persist = { tokens ->
                accountLock.withLock {
                    withContext(Dispatchers.IO) {
                        val latest = store.loadBaidu()
                        if (latest?.id == account.id) store.saveBaidu(latest.copy(tokens = tokens))
                    }
                }
            })
        }
        return BaiduProvider(account.id, root, { session.accessToken() }, BuildConfig.BAIDU_API.toHttpUrl())
    }
}

class GallerySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as GalleryApplication).services
        return try {
            graph.scanLock.withLock {
                if (!graph.configured) return@withLock Result.success()
                val network = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                if (!graph.preferences.state.value.syncMobile && network.isActiveNetworkMetered) return@withLock Result.retry()
                val account = graph.store.loadBaidu() ?: return@withLock Result.success()
                val root = account.root ?: return@withLock Result.success()
                GalleryScanner(graph.database).scan(graph.provider(account, root), allowNetwork = {
                    graph.preferences.state.value.syncMobile || !network.isActiveNetworkMetered
                }) { }
                scheduleThumbnailWarmup(applicationContext, graph.provider(account, root).sourceId)
                Result.success()
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}

/** Recent thumbnails only, on unmetered networks, with one reserved background transfer. */
class GalleryThumbnailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as GalleryApplication).services
        val expectedSource = inputData.getString("source") ?: return Result.failure()
        return try {
            if (!graph.configured) return Result.success()
            val account = graph.store.loadBaidu() ?: return Result.success()
            val root = account.root ?: return Result.success()
            val provider = graph.provider(account, root)
            if (provider.sourceId != expectedSource) return Result.success()
            val entries = graph.database.gallery().observe(expectedSource).first().take(1000)
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                if (!graph.photos.canWarmThumbnails()) return Result.retry()
                val active = graph.store.loadBaidu()
                if (active?.id != account.id || active.root != root) return Result.success()
                if (entry.thumb.isBlank()) continue
                val photo = MediaItem(digest("${entry.source}|${entry.path}"), entry.name, entry.album, LocalDate.of(1970, 1, 1),
                    path = entry.path, thumb = entry.thumb, version = "${entry.modified}|${entry.size}", remote = true, remoteId = entry.remoteId)
                try { graph.photos.load(provider, photo, false, allowOriginalFallback = false, warmup = true) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { /* A broken thumbnail must not stop the remaining warmup. */ }
            }
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
}

fun scheduleThumbnailWarmup(context: Context, source: String) {
    val request = OneTimeWorkRequestBuilder<GalleryThumbnailWorker>()
        .setInputData(workDataOf("source" to source))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("baidu-thumbnail-warmup", ExistingWorkPolicy.REPLACE, request)
}

fun scheduleGallerySync(context: Context) {
    val mobile = (context.applicationContext as GalleryApplication).services.preferences.state.value.syncMobile
    val request = PeriodicWorkRequestBuilder<GallerySyncWorker>(1, TimeUnit.HOURS)
        .setInitialDelay(1, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("baidu-gallery-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
}
