package com.cloudgallery.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudgallery.app.data.*
import com.cloudgallery.app.data.baidu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate
import java.time.OffsetDateTime

data class GalleryState(
    val media: List<MediaItem> = emptyList(), val albumPaths: List<String> = emptyList(),
    val newAlbumParent: String? = null, val creatingAlbum: Boolean = false, val albumError: String? = null,
    val downloading: Set<String> = emptySet(),
    val restoring: Boolean = true, val silentSync: Boolean = false,
    val provider: StorageProvider? = null, val busy: Boolean = false,
    val status: String = "添加网盘后，选择你想浏览的文件夹", val error: String? = null,
    val accountName: String? = null, val root: String? = null,
    val authBusy: Boolean = false, val authorization: DeviceAuthorization? = null,
    val folderPicker: Boolean = false, val folderPath: String = "/",
    val folders: List<RemoteFile> = emptyList(), val folderPage: Int = 1,
    val folderMore: Boolean = false, val folderLoading: Boolean = false,
    val folderError: String? = null, val selectionVersion: Int = 0,
    val uploadFolderPicker: Boolean = false, val uploadUris: List<android.net.Uri> = emptyList(),
    val uploadDestination: String = "", val preparingUpload: Boolean = false,
    val backupFolderPicker: Boolean = false, val quickFolderPicker: Boolean = false, val trash: List<TrashEntry> = emptyList(),
    val deviceAlbums: List<Pair<String, String>> = emptyList(), val notice: String? = null
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as GalleryApplication).services
    private val database = graph.database
    private val store = graph.store
    val photoCache = graph.photos
    val settings = graph.preferences.state
    private var indexedMedia: List<MediaItem> = emptyList()
    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val previous = settings.value
        graph.preferences.update(change)
        if (previous.backupConfig() != settings.value.backupConfig()) scheduleBackup(getApplication(), settings.value)
        if (account?.root != null && previous.syncMobile != settings.value.syncMobile) scheduleGallerySync(getApplication())
        if (previous.quickImport != settings.value.quickImport || previous.quickMobile != settings.value.quickMobile)
            viewModelScope.launch(Dispatchers.IO) { resumeQuickImports(getApplication(), replace = previous.quickMobile != settings.value.quickMobile) }
    }
    fun notify(message: String?) { mutable.value = mutable.value.copy(notice = message) }
    fun loadDeviceAlbums() = viewModelScope.launch {
        try {
            val albums = withContext(Dispatchers.IO) { deviceMedia(getApplication(), settings.value.videos)
                .distinctBy { it.bucket }.map { it.bucket to it.album }.sortedBy { it.second } }
            mutable.value = mutable.value.copy(deviceAlbums = albums)
        } catch (error: Exception) { notify(safeError(error)) }
    }
    fun backupStatus() = graph.preferences.prefs.getString("backupStatus", "等待备份")!!
    fun startBackup() {
        if (account == null) { notify("请先连接百度网盘"); return }
        if (settings.value.destination.isBlank()) { notify("请先选择备份目标文件夹"); return }
        if (!settings.value.allAlbums && settings.value.albums.isEmpty()) { notify("请至少选择一个相册"); return }
        if (!hasMediaPermission(getApplication(), settings.value.videos)) { notify("请允许访问手机照片和视频"); return }
        updateSettings { it.copy(backup = true) }
    }
    fun chooseBackupFolder() {
        if (account == null) { notify("请先连接百度网盘"); return }
        mutable.value = mutable.value.copy(uploadFolderPicker = false, backupFolderPicker = true, quickFolderPicker = false, folderPicker = true)
        browseFolder(settings.value.destination.ifBlank { "/" })
    }
    fun chooseQuickFolder() {
        if (account == null) { notify("请先连接百度网盘"); return }
        mutable.value = mutable.value.copy(uploadFolderPicker = false, backupFolderPicker = false, quickFolderPicker = true, folderPicker = true)
        browseFolder(settings.value.quickDestination.ifBlank { account?.root ?: "/" })
    }
    fun startQuickImport() {
        if (account == null) { notify("请先连接百度网盘"); return }
        val destination = settings.value.quickDestination.ifBlank { account?.root.orEmpty() }
        if (destination.isBlank()) { notify("请先选择图库文件夹"); return }
        updateSettings { it.copy(quickImport = true, quickDestination = destination) }
    }
    fun quickServiceEnabled(): Boolean {
        val manager = getApplication<Application>().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == QuickImportService::class.java.name && it.resolveInfo.serviceInfo.packageName == getApplication<Application>().packageName }
    }
    internal suspend fun quickTasks() = withContext(Dispatchers.IO) { graph.quickImports.expireCaptures(); graph.quickImports.recent() }
    fun quickDiagnostic(): String {
        val keys = graph.preferences.prefs.getString("quickKeyDiagnostic", "尚无服务连接或按键记录；请重新开启无障碍服务后测试。")!!
        val capture = graph.preferences.prefs.getString("quickCaptureDiagnostic", "尚无本版本的取链接流程记录。")!!
        val buttons = graph.preferences.prefs.getString("quickDiagnostic", "尚无图片页按钮记录。")!!
        val image = graph.preferences.prefs.getString("quickImageDiagnostic", "尚无图片下载尺寸记录。")!!
        return "按键与服务诊断\n$keys\n\n最近取链接流程\n$capture\n\n最近按钮诊断（可能来自上一次导图）\n$buttons\n\n最近下载尺寸诊断（可能来自另一篇笔记）\n$image"
    }
    fun quickStatus() = graph.preferences.prefs.getString("quickStatus", "双音量键保存当前小红书图片")!!
    fun retryQuickImport(id: String) = viewModelScope.launch(Dispatchers.IO) {
        if (!settings.value.quickImport) { withContext(Dispatchers.Main) { notify("请先开启快速导图") }; return@launch }
        val task = graph.quickImports.get(id) ?: return@launch
        if (task.link.isBlank()) { withContext(Dispatchers.Main) { notify("请回到小红书重新按双音量键") }; return@launch }
        graph.quickImports.update(id, "queued", "等待网络后重试")
        scheduleQuickImport(getApplication(), id, replace = true)
    }
    fun cancelQuickImport(id: String) = viewModelScope.launch(Dispatchers.IO) {
        if (!graph.quickImports.cancel(id)) return@launch
        androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("quick-import-$id")
    }
    private suspend fun applyTrash() {
        val source = mutable.value.provider?.sourceId ?: return
        val trash = withContext(Dispatchers.IO) { graph.records.trash(source) }
        val hidden = withContext(Dispatchers.IO) { graph.records.hiddenPaths(source) }
        if (mutable.value.provider?.sourceId != source) return
        mutable.value = mutable.value.copy(media = indexedMedia.filter { it.path !in hidden }, trash = trash)
    }
    fun removePhoto(photo: MediaItem) = viewModelScope.launch {
        val source = mutable.value.provider?.sourceId ?: return@launch
        withContext(Dispatchers.IO) { graph.records.remove(source, photo) }
        applyTrash()
    }
    fun restorePhoto(entry: TrashEntry) = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.records.restore(entry.source, entry.path) }
        applyTrash()
    }
    fun openCreateAlbum(parent: String?) {
        if (mutable.value.provider == null) { notify("请先选择图库文件夹"); return }
        mutable.value = mutable.value.copy(newAlbumParent = parent ?: mutable.value.root, albumError = null)
    }
    fun dismissCreateAlbum() {
        if (!mutable.value.creatingAlbum) mutable.value = mutable.value.copy(newAlbumParent = null, albumError = null)
    }
    fun createAlbum(input: String) = viewModelScope.launch {
        if (mutable.value.creatingAlbum) return@launch
        val provider = mutable.value.provider ?: return@launch
        val parent = mutable.value.newAlbumParent ?: return@launch
        try {
            val path = parent.trimEnd('/') + "/" + albumFolderName(input)
            check(settings.value.syncMobile || !(getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered) { "等待 Wi-Fi，可在设置 → 网络中允许移动数据同步" }
            mutable.value = mutable.value.copy(creatingAlbum = true, albumError = null)
            graph.scanLock.withLock {
                check(mutable.value.provider?.sourceId == provider.sourceId) { "图库已改变，请重试" }
                provider.createFolder(path)
                val dao = database.gallery()
                dao.putAlbums(listOf(IndexedAlbum(provider.sourceId, path, dao.state(provider.sourceId)?.run.orEmpty())))
            }
            if (mutable.value.provider?.sourceId == provider.sourceId) mutable.value = mutable.value.copy(newAlbumParent = null)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { mutable.value = mutable.value.copy(albumError = safeError(error)) }
        finally { mutable.value = mutable.value.copy(creatingAlbum = false) }
    }
    fun downloadPhoto(photo: MediaItem) = viewModelScope.launch {
        val provider = mutable.value.provider ?: return@launch
        if (photo.id in mutable.value.downloading) return@launch
        mutable.value = mutable.value.copy(downloading = mutable.value.downloading + photo.id)
        try {
            withContext(Dispatchers.IO) {
                check(canSaveQuickImage(getApplication())) { "请允许保存到手机相册后重试" }
                val original = photoCache.load(provider, photo, true) ?: error("未能取得原图，请重试")
                val copy = java.io.File.createTempFile("download-", ".image", getApplication<Application>().cacheDir)
                try {
                    original.copyTo(copy, overwrite = true)
                    val extension = when (readXhsImageInfo(copy).mime) {
                        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/heif" -> "heif"; "image/heic" -> "heic"
                        else -> error("图片格式不受当前设备支持")
                    }
                    val name = photo.name.substringBeforeLast('.').map { if (it.isISOControl() || it in "/\\") '_' else it }.joinToString("").take(100).ifBlank { "photo" }
                    saveQuickImage(getApplication(), copy, "$name.$extension", extension) { currentCoroutineContext().ensureActive() }
                } finally { copy.delete() }
            }
            notify("原图已保存到手机相册 · PixNexus Gallary")
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { notify("下载失败：${safeError(error)}") }
        finally { mutable.value = mutable.value.copy(downloading = mutable.value.downloading - photo.id) }
    }
    fun recordView(photo: MediaItem) = viewModelScope.launch(Dispatchers.IO) {
        mutable.value.provider?.sourceId?.let { graph.records.viewed(it, photo.path) }
    }
    fun maintenance(action: String) = viewModelScope.launch {
        try {
            when (action) {
                "cache" -> {
                    clearPrefetch()
                    androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("baidu-thumbnail-warmup")
                    withContext(Dispatchers.IO) { photoCache.clear() }
                    coil.Coil.imageLoader(getApplication()).memoryCache?.clear()
                    notify("文件缓存已清除")
                }
                "history" -> { withContext(Dispatchers.IO) { graph.records.clearHistory() }; notify("浏览记录已清除") }
                "refresh" -> { if (mutable.value.provider == null) notify("请先选择图库文件夹") else refresh() }
                "index" -> {
                    val source = mutable.value.provider?.sourceId ?: error("请先选择图库文件夹")
                    withContext(Dispatchers.IO) { graph.records.rebuild(source, mutable.value.media) }
                    notify("搜索索引已重建")
                }
            }
        } catch (error: Exception) { notify(safeError(error)) }
    }
    val baiduConfigured get() = graph.configured
    private val mutable = MutableStateFlow(GalleryState())
    val state = mutable.asStateFlow()
    private val uiPreferences = application.getSharedPreferences("gallery_ui", android.content.Context.MODE_PRIVATE)
    private val mutableLayout = MutableStateFlow(GalleryLayout.entries.firstOrNull {
        it.name == uiPreferences.getString("layout", null)
    } ?: GalleryLayout.GRID)
    val layout = mutableLayout.asStateFlow()
    private val mutableSort = MutableStateFlow(GallerySort.entries.firstOrNull {
        it.name == uiPreferences.getString("sort", null)
    } ?: GallerySort.NEWEST)
    val sort = mutableSort.asStateFlow()
    fun setSort(value: GallerySort) {
        mutableSort.value = value
        uiPreferences.edit().putString("sort", value.name).apply()
    }
    private val mutableUploadWork = MutableStateFlow<List<androidx.work.WorkInfo>>(emptyList())
    val uploadWork = mutableUploadWork.asStateFlow()
    private var uploadObserver: Job? = null
    private fun observeUpload(id: String) {
        uploadObserver?.cancel()
        uploadObserver = viewModelScope.launch {
            androidx.work.WorkManager.getInstance(getApplication()).getWorkInfosForUniqueWorkFlow("manual-upload-$id")
                .collect { mutableUploadWork.value = it }
        }
    }
    fun prepareUpload(uris: List<android.net.Uri>, destination: String?) {
        if (uris.isEmpty()) return
        if (account == null) { notify("请先连接百度网盘"); return }
        mutable.value = mutable.value.copy(uploadUris = uris.distinct(), uploadDestination = destination ?: account?.root ?: "/")
    }
    fun dismissUpload() { if (!mutable.value.preparingUpload) mutable.value = mutable.value.copy(uploadUris = emptyList()) }
    fun chooseUploadFolder() {
        mutable.value = mutable.value.copy(folderPicker = true, uploadFolderPicker = true, backupFolderPicker = false, quickFolderPicker = false)
        browseFolder(mutable.value.uploadDestination)
    }
    fun cancelUpload() {
        val id = uiPreferences.getString("uploadBatch", null) ?: return
        androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("manual-upload-$id")
    }
    fun retryUpload() {
        val id = uiPreferences.getString("uploadBatch", null) ?: return
        val mobile = uiPreferences.getBoolean("uploadMobile", false)
        scheduleManualUpload(getApplication(), id, mobile)
    }
    fun dismissUploadStatus() {
        uploadObserver?.cancel(); mutableUploadWork.value = emptyList()
        val id = uiPreferences.getString("uploadBatch", null)
        if (id != null) viewModelScope.launch(Dispatchers.IO) {
            graph.manualUploadLock.withLock {
                val file = uploadManifest(getApplication(), id)
                if (file.isFile) {
                    val batch = org.json.JSONObject(android.util.AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                    file.delete()
                    releaseUnusedUploadGrants(getApplication(), batch)
                }
            }
        }
        uiPreferences.edit().remove("uploadBatch").apply()
    }
    fun uploadSelected(mobile: Boolean) = viewModelScope.launch {
        if (mutable.value.preparingUpload) return@launch
        val saved = account ?: return@launch
        val uris = mutable.value.uploadUris.toList()
        if (uris.isEmpty()) return@launch
        val destination = normalizePath(mutable.value.uploadDestination)
        mutable.value = mutable.value.copy(preparingUpload = true)
        try {
            val id = java.util.UUID.randomUUID().toString()
            withContext(Dispatchers.IO) {
                graph.manualUploadLock.withLock {
                    check(store.loadBaidu()?.id == saved.id) { "账户已改变，请重新选择上传图片" }
                    val items = org.json.JSONArray()
                    try {
                        uris.forEachIndexed { index, uri ->
                            val name = selectedUploadName(getApplication(), uri)
                            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            items.put(org.json.JSONObject().put("uri", uri.toString()).put("name", name)
                                .put("path", destination.trimEnd('/') + "/" + uploadFileName(name, id, index)))
                        }
                    } catch (error: Exception) {
                        releaseUnusedUploadGrants(getApplication(), org.json.JSONObject().put("items", items))
                        throw error
                    }
                    writeUploadManifest(uploadManifest(getApplication(), id), org.json.JSONObject().put("account", saved.id)
                        .put("mobile", mobile).put("items", items))
                    // Save the banner identity before scheduling so process death cannot hide the queued batch.
                    uiPreferences.edit().putString("uploadBatch", id).putBoolean("uploadMobile", mobile).commit()
                    scheduleManualUpload(getApplication(), id, mobile)
                }
            }
            observeUpload(id)
            mutable.value = mutable.value.copy(uploadUris = emptyList())
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { notify(safeError(error)) }
        finally { mutable.value = mutable.value.copy(preparingUpload = false) }
    }
    fun position(scope: String): GalleryPosition = GalleryPosition(
        uiPreferences.getInt("position.$scope.index", 0), uiPreferences.getInt("position.$scope.offset", 0),
        uiPreferences.getString("position.$scope.anchor", null))
    fun savePosition(scope: String, position: GalleryPosition) {
        uiPreferences.edit().putInt("position.$scope.index", position.index)
            .putInt("position.$scope.offset", position.offset).putString("position.$scope.anchor", position.anchor).apply()
    }
    fun setLayout(value: GalleryLayout) {
        mutableLayout.value = value
        uiPreferences.edit().putString("layout", value.name).apply()
    }
    private var observer: Job? = null
    private var operation: Job? = null
    private var authJob: Job? = null
    private var folderJob: Job? = null
    private var folderRequest = 0
    private val prefetchJobs = mutableMapOf<String, Job>()
    private var account: BaiduAccount? = null
    private var lastRefresh = 0L

    init {
        uiPreferences.getString("uploadBatch", null)?.let(::observeUpload)
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    store.loadBaidu()
                }
                if (saved != null) {
                    account = saved
                    if (saved.root != null) {
                        attachNative(saved)
                        val sync = withContext(Dispatchers.IO) { database.gallery().state(mutable.value.provider!!.sourceId) }
                        lastRefresh = sync?.lastSuccess ?: 0
                        warmOpeningImages()
                        mutable.value = mutable.value.copy(restoring = false)
                        scheduleGallerySync(application)
                        if (needsAutomaticScan(lastRefresh, System.currentTimeMillis())) refresh(automatic = true)
                    } else {
                        mutable.value = mutable.value.copy(accountName = saved.name, status = "请选择照片文件夹")
                        openFolderPicker()
                    }
                }
                scheduleBackup(application, settings.value, immediate = false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = "无法读取已保存连接，请重新配置") }
            finally { mutable.value = mutable.value.copy(restoring = false) }
        }
    }

    private fun toMedia(entries: List<IndexedMedia>): List<MediaItem> = entries.map { entry ->
        val date = runCatching { OffsetDateTime.parse(entry.modified).toLocalDate() }.getOrDefault(LocalDate.of(1970, 1, 1))
        MediaItem(digest("${entry.source}|${entry.path}"), entry.name, entry.album, date,
            path = entry.path, thumb = entry.thumb, version = "${entry.modified}|${entry.size}", remote = true, remoteId = entry.remoteId, size = entry.size,
            modified = runCatching { OffsetDateTime.parse(entry.modified).toEpochSecond() }.getOrDefault(0))
    }
    private suspend fun attach(provider: StorageProvider, name: String, root: String) {
        indexedMedia = emptyList()
        observer?.cancelAndJoin()
        clearPrefetch()
        val version = mutable.value.selectionVersion
        val restoring = mutable.value.restoring
        val (media, trash, hidden) = withContext(Dispatchers.IO) {
            Triple(toMedia(database.gallery().observe(provider.sourceId).first()),
                graph.records.trash(provider.sourceId), graph.records.hiddenPaths(provider.sourceId))
        }
        indexedMedia = media
        mutable.value = GalleryState(provider = provider, accountName = name, media = media.filter { it.path !in hidden }, trash = trash,
            root = root, albumPaths = database.gallery().observeAlbums(provider.sourceId).first().map { it.path }, status = "本地图库已恢复", selectionVersion = version, restoring = restoring)
        observer = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(database.gallery().observe(provider.sourceId), database.gallery().observeAlbums(provider.sourceId)) { entries, albums ->
                withContext(Dispatchers.Default) { toMedia(entries) } to albums.map { it.path }
            }.distinctUntilChanged().collect { (media, albums) ->
                mutable.value = mutable.value.copy(albumPaths = albums)
                indexedMedia = media
                applyTrash()
            }
        }
    }
    private suspend fun attachNative(saved: BaiduAccount) {
        attach(graph.provider(saved, saved.root!!), saved.name, saved.root)
    }
    private suspend fun warmOpeningImages() {
        val state = mutable.value
        val source = state.provider?.sourceId ?: return
        val position = position("$source|photos|${layout.value.name}|${sort.value.name}")
        val ordered = sort.value.arrange(state.media)
        val anchor = ordered.indexOfFirst { it.id == position.anchor }.takeIf { it >= 0 }
            ?: position.index.coerceAtMost((state.media.size - 1).coerceAtLeast(0))
        val loader = coil.Coil.imageLoader(getApplication())
        // Bounded decode of disk hits only; a missing cache must never delay launch for a download.
        withTimeoutOrNull(300) {
            ordered.drop(anchor).take(15).mapNotNull { photo -> photoCache.cached(photo, false)?.let { photo to it } }
                .chunked(3).forEach { group -> coroutineScope {
                    group.map { (_, file) -> async {
                        loader.execute(coil.request.ImageRequest.Builder(getApplication()).data(file)
                            .memoryCacheKey(file.absolutePath).size(512).build())
                    } }.awaitAll()
                } }
        }
    }

    fun beginBaiduAuthorization() {
        if (!baiduConfigured) {
            mutable.value = mutable.value.copy(error = "此版本尚未开放百度登录")
            return
        }
        if (authJob?.isActive == true) return
        authJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(authBusy = true, error = null)
            try {
                val oauth = graph.oauth()
                val device = oauth.begin()
                mutable.value = mutable.value.copy(authorization = device)
                val tokens = oauth.awaitAuthorization(device)
                val identity = BaiduProvider("pending", "/", { tokens.accessToken }, BuildConfig.BAIDU_API.toHttpUrl()).identity()
                val saved = BaiduAccount(identity.first, identity.second, null, tokens)
                operation?.cancelAndJoin()
                observer?.cancelAndJoin()
                clearPrefetch()
                graph.accountLock.withLock { withContext(Dispatchers.IO) { store.saveBaidu(saved) } }
                graph.resetSession(saved.id)
                account = saved
                mutable.value = GalleryState(accountName = saved.name, status = "请选择照片文件夹", restoring = false,
                    selectionVersion = mutable.value.selectionVersion + 1)
                openFolderPicker()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = safeError(error), authorization = null) }
            finally { mutable.value = mutable.value.copy(authBusy = false) }
        }
    }
    fun cancelAuthorization() {
        authJob?.cancel()
        mutable.value = mutable.value.copy(authorization = null, authBusy = false)
    }
    fun openFolderPicker() {
        val saved = account ?: return
        mutable.value = mutable.value.copy(folderPicker = true, uploadFolderPicker = false, backupFolderPicker = false, quickFolderPicker = false)
        browseFolder(saved.root ?: "/")
    }
    fun closeFolderPicker() {
        folderRequest++
        folderJob?.cancel()
        mutable.value = mutable.value.copy(folderPicker = false, folderLoading = false, uploadFolderPicker = false, backupFolderPicker = false, quickFolderPicker = false)
    }
    fun browseFolder(path: String, more: Boolean = false) {
        val saved = account ?: return
        folderJob?.cancel()
        val request = ++folderRequest
        val normalized = normalizePath(path)
        val page = if (more) mutable.value.folderPage + 1 else 1
        folderJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(folderPath = normalized, folderLoading = true, folderError = null,
                folders = if (more) mutable.value.folders else emptyList())
            try {
                val result = graph.provider(saved, "/").list(normalized, page)
                mutable.value = mutable.value.copy(folders = (if (more) mutable.value.folders else emptyList()) + result.files.filter { it.directory },
                    folderPage = page, folderMore = result.more)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.value = mutable.value.copy(folderError = safeError(error)) }
            finally { if (request == folderRequest) mutable.value = mutable.value.copy(folderLoading = false) }
        }
    }
    fun chooseFolder() {
        val saved = account ?: return
        if (mutable.value.folderLoading || mutable.value.folderError != null) return
        val root = mutable.value.folderPath
        if (mutable.value.uploadFolderPicker) {
            mutable.value = mutable.value.copy(uploadDestination = root)
            closeFolderPicker()
            return
        }
        if (mutable.value.quickFolderPicker) {
            updateSettings { it.copy(quickDestination = root) }
            closeFolderPicker()
            return
        }
        if (mutable.value.backupFolderPicker) {
            updateSettings { it.copy(destination = root) }
            closeFolderPicker()
            return
        }
        val oldOperation = operation
        operation = viewModelScope.launch {
            oldOperation?.cancelAndJoin()
            try {
                val selected = graph.accountLock.withLock {
                    withContext(Dispatchers.IO) {
                        val latest = store.loadBaidu() ?: saved
                        check(latest.id == saved.id) { "账户已改变，请重新选择" }
                        latest.copy(root = root).also { store.saveBaidu(it) }
                    }
                }
                account = selected
                val version = mutable.value.selectionVersion + 1
                attachNative(selected)
                mutable.value = mutable.value.copy(selectionVersion = version)
                scheduleGallerySync(getApplication())
                lastRefresh = System.currentTimeMillis()
                runScan(mutable.value.provider!!)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = safeError(error)) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun onForeground() {
        if (!mutable.value.restoring && account?.root != null && !mutable.value.authBusy && !mutable.value.folderPicker &&
            needsAutomaticScan(lastRefresh, System.currentTimeMillis())) refresh(automatic = true)
    }
    fun refresh(automatic: Boolean = false) {
        val provider = mutable.value.provider ?: return
        if (operation?.isActive == true) {
            if (!automatic) mutable.value = mutable.value.copy(silentSync = false)
            return
        }
        lastRefresh = System.currentTimeMillis()
        operation = viewModelScope.launch {
            try { runScan(provider, silent = automatic && mutable.value.media.isNotEmpty()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(error = if (automatic && mutable.value.media.isNotEmpty()) null else safeError(error), status = "暂时无法更新，已保留本地图库")
            } finally { mutable.value = mutable.value.copy(busy = false, silentSync = false) }
        }
    }
    private suspend fun runScan(provider: StorageProvider, silent: Boolean = false) {
        val network = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        check(settings.value.syncMobile || !network.isActiveNetworkMetered) { "文件同步仅在 Wi-Fi 下进行，可在设置 → 网络中允许移动数据" }
        mutable.value = mutable.value.copy(busy = true, silentSync = silent, error = null)
        graph.scanLock.withLock {
            GalleryScanner(database).scan(provider, allowNetwork = { settings.value.syncMobile || !network.isActiveNetworkMetered }) { message ->
                if (mutable.value.provider?.sourceId == provider.sourceId) mutable.value = mutable.value.copy(status = message)
            }
        }
        if (account?.root != null && mutable.value.provider?.sourceId == provider.sourceId) {
            scheduleThumbnailWarmup(getApplication(), provider.sourceId)
        }
    }
    fun prefetch(photos: List<MediaItem>) {
        val provider = mutable.value.provider ?: return
        val desired = photos.filter { it.thumb.isNotBlank() }.take(48).associateBy { "${it.id}|${it.version}" }
        (prefetchJobs.keys - desired.keys).forEach { prefetchJobs.remove(it)?.cancel() }
        desired.forEach { (key, photo) ->
            if (key !in prefetchJobs && photoCache.cached(photo, false) == null) {
                prefetchJobs[key] = viewModelScope.launch {
                    try { photoCache.load(provider, photo, false, allowOriginalFallback = false, prefetch = true) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { }
                }
            }
        }
    }
    private fun clearPrefetch() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
    }
    fun pause() { operation?.cancel(); mutable.value = mutable.value.copy(busy = false, status = "已暂停") }
}

fun safeError(error: Exception): String = when (error) {
    is java.net.UnknownHostException -> "无法解析服务器地址"
    is java.net.SocketTimeoutException -> "网络超时，请重试"
    is javax.net.ssl.SSLException -> "HTTPS 连接失败，请检查证书"
    is java.io.IOException -> "网络或缓存读写失败，请重试"
    else -> error.message?.takeIf { !it.contains("http") && it.length < 120 } ?: "请求失败，请检查连接配置"
}
