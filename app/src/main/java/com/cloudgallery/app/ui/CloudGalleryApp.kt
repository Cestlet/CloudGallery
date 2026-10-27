package com.cloudgallery.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cloudgallery.app.data.StorageProvider
import com.cloudgallery.app.BuildConfig
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cloudgallery.app.data.PhotoCache
import com.cloudgallery.app.GalleryState
import com.cloudgallery.app.safeError
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudgallery.app.GalleryViewModel
import com.cloudgallery.app.data.MediaItem
import com.cloudgallery.app.data.GalleryLayout
import com.cloudgallery.app.data.GalleryPosition
import com.cloudgallery.app.data.galleryItemKeys
import com.cloudgallery.app.data.resolveGalleryPosition
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.time.format.DateTimeFormatter

private val LocalProvider = staticCompositionLocalOf<StorageProvider?> { null }
private val LocalPhotoCache = staticCompositionLocalOf<PhotoCache> { error("Photo cache missing") }

@Composable
fun CloudGalleryApp(model: GalleryViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val layout by model.layout.collectAsStateWithLifecycle()
    val sort by model.sort.collectAsStateWithLifecycle()
    val uploadWork by model.uploadWork.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val systemLanguage = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val english = settings.language == "en" || settings.language == "system" && systemLanguage != "zh"
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, model) {
        val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.onForeground() }
        lifecycle.lifecycle.addObserver(listener)
        onDispose { lifecycle.lifecycle.removeObserver(listener) }
    }
    LaunchedEffect(state.selectionVersion) { if (state.selectionVersion > 0) { tab = 0; album = null; selected = null } }
    LaunchedEffect(state.provider?.sourceId) { selected = null }
    val ordered = remember(state.media, sort) { sort.arrange(state.media) }
    val albums = remember(ordered, state.albumPaths) {
        val grouped = ordered.groupBy { it.album }
        (state.albumPaths + grouped.keys).distinct().associateWith { grouped[it].orEmpty() }.toSortedMap()
    }
    val visible = if (album == null) ordered else albums[album].orEmpty()
    val browseScope = "${state.provider?.sourceId.orEmpty()}|${album ?: "photos"}|${layout.name}|${sort.name}"
    val uploadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.prepareUpload(uris, album ?: state.root)
    }
    val showingSync = state.busy && !state.silentSync
    CompositionLocalProvider(LocalProvider provides state.provider, LocalPhotoCache provides model.photoCache, LocalEnglish provides english) {
    GalleryTheme(settings.theme) {
    GalleryBackground {
        if (state.newAlbumParent != null) CreateAlbumDialog(state, model)
        if (state.uploadUris.isNotEmpty()) UploadDialog(state, model, settings.syncMobile)
        if (state.notice != null && tab != 2) AlertDialog(onDismissRequest = { model.notify(null) },
            title = { Text(label("提示", "Notice")) }, text = { Text(localizedNotice(state.notice!!)) },
            confirmButton = { TextButton(onClick = { model.notify(null) }) { Text(label("确定", "OK")) } })
        if (state.folderPicker) {
            FolderPicker(state, model)
        } else if (state.authBusy) {
            Authorization(state, model)
        } else if (selected != null && visible.isNotEmpty()) {
            val snapshot = remember(selected) { visible.toList() }
            Viewer(snapshot, selected!!, onClose = { selected = null }, onRemove = model::removePhoto, onView = model::recordView, downloading = state.downloading, onDownload = model::downloadPhoto, onNotice = model::notify)
        } else {
            BackHandler(enabled = album != null || tab != 0) {
                if (album != null) album = null else tab = 0
            }
            Scaffold(containerColor = Color.Transparent, bottomBar = {
                if (tab != 2 || settingsPage == null) GalleryNavigation(tab, onSelect = { tab = it; album = null; settingsPage = null })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (tab == 2 && settingsPage != null) IconButton(onClick = { settingsPage = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, label("返回设置", "Back to settings"))
                            }
                            if (album != null) IconButton(onClick = { album = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回相册")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(if (tab == 2 && settingsPage != null) settingsPageTitle(settingsPage!!) else album?.let(::albumTitle) ?: listOf(label("照片", "Photos"), label("相册", "Albums"), label("设置", "Settings"))[tab],
                                    style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (tab != 2 || settingsPage == null) Text(when (tab) {
                                    0 -> label("${visible.size} 张照片 · ${if (showingSync) "正在同步" else if (state.provider == null) "等待连接" else "云端图库"}", "${visible.size} photos · ${if (showingSync) "Syncing" else if (state.provider == null) "Not connected" else "Cloud gallery"}")
                                    1 -> if (album != null) label("${visible.size} 张照片", "${visible.size} photos") else label("${albums.size} 个相册 · 按文件夹整理", "${albums.size} albums · Grouped by folder")
                                    else -> label("让云端照片，随手可见", "Your cloud photos, within reach")
                                }, style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                            }
                            if (state.provider != null && tab != 2) IconButton(enabled = uploadWork.none { !it.state.isFinished }, onClick = {
                                if (uploadWork.isNotEmpty()) model.dismissUploadStatus()
                                uploadLauncher.launch(arrayOf("image/*"))
                            }) {
                                Icon(Icons.Outlined.Upload, label("上传图片", "Upload photos"), tint = GalleryBlue)
                            }
                            if (state.provider != null && tab != 2) GlassSurface(corner = 24) {
                                IconButton(onClick = { if (showingSync) model.pause() else model.refresh() }) {
                                    Icon(if (showingSync) Icons.Outlined.Pause else Icons.Outlined.Sync,
                                        if (showingSync) "暂停扫描" else "同步图库", tint = GalleryBlue)
                                }
                            }
                        }
                        if (BuildConfig.MOCK_BAIDU) Text(label("模拟数据", "Demo data"), style = MaterialTheme.typography.labelSmall, color = GalleryMuted)
                        if (showingSync) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp))
                        state.error?.let { Text(localizedNotice(it), modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    if (tab == 1 && state.provider != null) TextButton(onClick = { model.openCreateAlbum(album ?: state.root) },
                        modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label("新建相册", "New album"))
                    }
                    if (tab != 2 && uploadWork.isNotEmpty()) UploadStatus(uploadWork, model)
                    if (tab == 0 || tab == 1 && album != null) {
                        LayoutSelector(layout, model::setLayout)
                        SortSelector(sort, model::setSort)
                    }
                    when (tab) {
                        0 -> PhotoBrowser(visible, layout, sort.byDate, onSelect = { selected = it.id }, prefetch = model::prefetch, onConnect = { if (state.provider == null) tab = 2 else uploadLauncher.launch(arrayOf("image/*")) }, scope = browseScope, initialPosition = model.position(browseScope), onPosition = { model.savePosition(browseScope, it) })
                        1 -> if (album != null) PhotoBrowser(visible, layout, sort.byDate, onSelect = { selected = it.id }, prefetch = model::prefetch, onConnect = { if (state.provider == null) tab = 2 else uploadLauncher.launch(arrayOf("image/*")) }, scope = browseScope, initialPosition = model.position(browseScope), onPosition = { model.savePosition(browseScope, it) })
                            else Albums(albums, onOpen = { album = it })
                        else -> SettingsScreen(state, model, settingsPage, onPage = { settingsPage = it })
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun GalleryNavigation(tab: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.widthIn(max = 420.dp).fillMaxWidth(), corner = 32) {
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(label("照片", "Photos"), label("相册", "Albums"), label("设置", "Settings")).forEachIndexed { index, title ->
                    val selected = tab == index
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                        .background(if (selected) GalleryBlue.copy(alpha = .10f) else Color.Transparent)
                        .selectable(selected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(when (index) { 0 -> Icons.Outlined.PhotoLibrary; 1 -> Icons.Outlined.CollectionsBookmark; else -> Icons.Outlined.Settings },
                            null, Modifier.size(23.dp), tint = if (selected) GalleryBlue else GalleryMuted)
                        Text(title, style = MaterialTheme.typography.labelSmall, color = if (selected) GalleryBlue else GalleryMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutSelector(layout: GalleryLayout, onChoose: (GalleryLayout) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label("${layout.title}视图", when(layout) { GalleryLayout.GRID -> "Grid view"; GalleryLayout.WATERFALL -> "Waterfall view"; GalleryLayout.LIST -> "List view" }), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        GalleryLayout.entries.forEach { option ->
            val selected = layout == option
            Box(Modifier.padding(horizontal = 1.dp).clip(RoundedCornerShape(14.dp))
                .background(if (selected) GalleryBlue.copy(alpha = .10f) else Color.Transparent)
                .selectable(selected, role = Role.RadioButton, onClick = { onChoose(option) })
                .semantics { contentDescription = option.title }
                .size(48.dp), contentAlignment = Alignment.Center) {
                Icon(when (option) {
                    GalleryLayout.GRID -> Icons.Outlined.GridView
                    GalleryLayout.WATERFALL -> Icons.AutoMirrored.Outlined.ViewQuilt
                    GalleryLayout.LIST -> Icons.AutoMirrored.Outlined.ViewList
                }, null, Modifier.size(21.dp), tint = if (selected) GalleryBlue else GalleryMuted)
            }
        }
    }
}

@Composable
private fun PhotoBrowser(media: List<MediaItem>, layout: GalleryLayout, byDate: Boolean, onSelect: (MediaItem) -> Unit,
    prefetch: (List<MediaItem>) -> Unit, onConnect: () -> Unit, scope: String, initialPosition: GalleryPosition, onPosition: (GalleryPosition) -> Unit) {
    key(scope) {
    if (media.isEmpty() || layout == GalleryLayout.GRID) PhotoGrid(media, byDate, onSelect, prefetch, onConnect, initialPosition, onPosition)
    else if (layout == GalleryLayout.WATERFALL) PhotoWaterfall(media, byDate, onSelect, prefetch, initialPosition, onPosition)
    else PhotoList(media, byDate, onSelect, prefetch, initialPosition, onPosition)
    }
}

@Composable
private fun ObservePosition(state: Any, current: () -> GalleryPosition, save: (GalleryPosition) -> Unit) {
    val latestCurrent by rememberUpdatedState(current)
    val latestSave by rememberUpdatedState(save)
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(state) {
        snapshotFlow { latestCurrent() }.conflate().collect {
            latestSave(it)
            delay(200)
        }
    }
    DisposableEffect(state, lifecycle) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestSave(latestCurrent())
        }
        lifecycle.lifecycle.addObserver(listener)
        onDispose { lifecycle.lifecycle.removeObserver(listener); latestSave(latestCurrent()) }
    }
}

@Composable
private fun ObservePrefetch(media: List<MediaItem>, anchor: Any, visibleKeys: () -> List<Any>,
    prefetch: (List<MediaItem>) -> Unit) {
    val positions = remember(media) { media.mapIndexed { index, photo -> photo.id to index }.toMap() }
    LaunchedEffect(anchor, media) {
        var previousLast = -1
        snapshotFlow { visibleKeys() }.collect { keys ->
            val visible = keys.mapNotNull { positions[it] }
            if (visible.isNotEmpty()) {
                val first = visible.min()
                val last = visible.max()
                val backward = previousLast >= 0 && last < previousLast
                val start = if (backward) (first - 24).coerceAtLeast(0) else first
                val end = if (backward) last + 1 else (last + 25).coerceAtMost(media.size)
                prefetch(media.subList(start, end))
                previousLast = last
            }
        }
    }
}

@Composable
private fun DateHeader(date: java.time.LocalDate, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (date == java.time.LocalDate.of(1970, 1, 1)) "日期未知" else date.format(DateTimeFormatter.ofPattern(if (LocalEnglish.current) "MMM d, yyyy" else "yyyy年M月d日")), style = MaterialTheme.typography.titleMedium)
        Text(label("$count 张", "$count photos"), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
    }
}

@Composable
private fun PhotoWaterfall(media: List<MediaItem>, byDate: Boolean, onSelect: (MediaItem) -> Unit,
    prefetch: (List<MediaItem>) -> Unit, initialPosition: GalleryPosition, onPosition: (GalleryPosition) -> Unit) {
    val cache = LocalPhotoCache.current
    val keys = remember(media, byDate) { galleryItemKeys(media, byDate) }
    val start = remember { resolveGalleryPosition(initialPosition, keys) }
    val grid = rememberLazyStaggeredGridState(start.index, start.offset)
    ObservePosition(grid, { GalleryPosition(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, keys.getOrNull(grid.firstVisibleItemIndex)) }, onPosition)
    val dates = remember(media, byDate) { com.cloudgallery.app.data.galleryGroups(media, byDate) }
    ObservePrefetch(media, grid, { grid.layoutInfo.visibleItemsInfo.map { it.key } }, prefetch)
    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Adaptive(140.dp), state = grid,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
        dates.forEach { (date, photos) ->
            if (date != null) item(key = "date-$date", span = StaggeredGridItemSpan.FullLine, contentType = "date") { DateHeader(date, photos.size) }
            staggeredItems(photos, key = { it.id }, contentType = { "image" }) { photo ->
                var ratio by rememberSaveable(photo.id, photo.version) { mutableFloatStateOf(cache.aspectRatio(photo).coerceIn(.25f, 4f)) }
                MediaImage(photo, Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(photo) }.semantics { contentDescription = "${photo.album}，${photo.name}" },
                    crop = false, onAspectRatio = { ratio = it.coerceIn(0.25f, 4f) })
            }
        }
    }
}

@Composable
private fun PhotoList(media: List<MediaItem>, byDate: Boolean, onSelect: (MediaItem) -> Unit,
    prefetch: (List<MediaItem>) -> Unit, initialPosition: GalleryPosition, onPosition: (GalleryPosition) -> Unit) {
    val keys = remember(media, byDate) { galleryItemKeys(media, byDate) }
    val start = remember { resolveGalleryPosition(initialPosition, keys) }
    val list = rememberLazyListState(start.index, start.offset)
    ObservePosition(list, { GalleryPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, keys.getOrNull(list.firstVisibleItemIndex)) }, onPosition)
    val dates = remember(media, byDate) { com.cloudgallery.app.data.galleryGroups(media, byDate) }
    ObservePrefetch(media, list, { list.layoutInfo.visibleItemsInfo.map { it.key } }, prefetch)
    LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        dates.forEach { (date, photos) ->
            if (date != null) item(key = "date-$date", contentType = "date") { DateHeader(date, photos.size) }
            items(photos, key = { it.id }, contentType = { "image" }) { photo ->
                Card(onClick = { onSelect(photo) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        MediaImage(photo, Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(photo.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(albumTitle(photo.album), style = MaterialTheme.typography.bodySmall, color = GalleryMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(photo.date.toString(), style = MaterialTheme.typography.labelSmall, color = GalleryMuted)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = GalleryMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoGrid(media: List<MediaItem>, byDate: Boolean, onSelect: (MediaItem) -> Unit,
    prefetch: (List<MediaItem>) -> Unit, onConnect: () -> Unit, initialPosition: GalleryPosition, onPosition: (GalleryPosition) -> Unit) {
    if (media.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            GlassSurface(Modifier.size(88.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(40.dp), tint = GalleryBlue) }
            }
            Text(label("你的照片，在这里相遇", "Your photos, together"), Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
            Text(if (LocalProvider.current == null) label("连接网盘，选择想浏览的照片文件夹", "Connect your drive and choose a photo folder") else label("相册还没有图片，上传照片开始整理", "Upload photos to this empty album"), color = GalleryMuted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onConnect, modifier = Modifier.padding(top = 24.dp)) { Text(if (LocalProvider.current == null) label("连接网盘", "Connect drive") else label("上传图片", "Upload photos")) }
        }
        return
    }
    val keys = remember(media, byDate) { galleryItemKeys(media, byDate) }
    val start = remember { resolveGalleryPosition(initialPosition, keys) }
    val grid = rememberLazyGridState(start.index, start.offset)
    ObservePosition(grid, { GalleryPosition(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, keys.getOrNull(grid.firstVisibleItemIndex)) }, onPosition)
    ObservePrefetch(media, grid, { grid.layoutInfo.visibleItemsInfo.map { it.key } }, prefetch)
    val dates = remember(media, byDate) { com.cloudgallery.app.data.galleryGroups(media, byDate) }
    LazyVerticalGrid(state = grid, columns = GridCells.Adaptive(100.dp), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        dates.forEach { (date, photos) ->
            if (date != null) item(key = "date-$date", span = { GridItemSpan(maxLineSpan) }, contentType = "date") {
                DateHeader(date, photos.size)
            }
            items(photos, key = { it.id }, contentType = { "image" }) { photo ->
                MediaImage(photo, Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))
                    .clickable { onSelect(photo) }.semantics { contentDescription = "${photo.album}，${photo.name}" })
            }
        }
    }
}

@Composable
private fun Albums(albums: Map<String, List<MediaItem>>, onOpen: (String) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        items(albums.entries.toList(), key = { it.key }) { entry ->
            Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable { onOpen(entry.key) }) {
                if (entry.value.isEmpty()) Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                    .background(GalleryBlue.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.FolderOpen, label("空相册", "Empty album"), Modifier.size(52.dp), tint = GalleryBlue)
                } else MediaImage(entry.value.first(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                    .semantics { contentDescription = "${entry.key}封面" })
                Column(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(albumTitle(entry.key), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(label("${entry.value.size} 张照片", "${entry.value.size} photos"), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                    Text(entry.key, style = MaterialTheme.typography.labelSmall, color = GalleryMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Authorization(state: GalleryState, model: GalleryViewModel) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var browserError by remember { mutableStateOf(false) }
    BackHandler { model.cancelAuthorization() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        IconButton(onClick = model::cancelAuthorization) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "取消授权") }
        Icon(Icons.Outlined.Cloud, null, Modifier.size(48.dp), tint = GalleryBlue)
        Text(if (BuildConfig.MOCK_BAIDU) "模拟百度授权" else "连接百度网盘", style = MaterialTheme.typography.headlineLarge)
        state.authorization?.let { device ->
            var copied by remember(device.userCode) { mutableStateOf(false) }
            Text(label("在百度授权页面输入下方验证码，完成后返回这里，选择你的照片文件夹。", "Enter this code on the Baidu authorization page, then return to choose your photo folder."), color = GalleryMuted)
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "复制授权码") {
                    clipboard.setPrimaryClip(ClipData.newPlainText("百度网盘授权码", device.userCode))
                    copied = true
                }.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label("你的授权验证码", "Your authorization code"), style = MaterialTheme.typography.labelMedium, color = GalleryMuted)
                    Text(device.userCode, style = MaterialTheme.typography.headlineLarge, color = GalleryBlue)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (copied) Icons.Outlined.CheckCircle else Icons.Outlined.ContentCopy, null,
                            Modifier.size(16.dp), tint = GalleryBlue)
                        Text(if (copied) "已复制" else "点击复制", style = MaterialTheme.typography.labelMedium, color = GalleryBlue)
                    }
                }
            }
            Button(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUrl))) }
                catch (_: Exception) { browserError = true }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label("打开授权页面", "Open authorization page")) }
            if (browserError) Text(label("未找到可用浏览器，请安装浏览器后重试。", "No browser found. Install a browser and try again."))
            Text(label("等待授权完成…", "Waiting for authorization…"), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
        } ?: Text(label("正在获取授权验证码…", "Getting authorization code…"))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun FolderPicker(state: GalleryState, model: GalleryViewModel) {
    BackHandler { model.closeFolderPicker() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (state.uploadFolderPicker) label("选择上传目标文件夹", "Choose upload destination") else if (state.quickFolderPicker) label("选择导图目标文件夹", "Choose import destination") else if (state.backupFolderPicker) label("选择备份目标文件夹", "Choose backup destination") else label("选择照片文件夹", "Choose gallery folder"), style = MaterialTheme.typography.headlineMedium)
        GlassSurface(Modifier.fillMaxWidth(), corner = 20) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.FolderOpen, null, tint = GalleryBlue)
                Text(state.folderPath, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(if (state.uploadFolderPicker) label("所选图片将上传到此文件夹。", "Selected photos will be uploaded here.") else if (state.quickFolderPicker) label("小红书图片将上传到此文件夹。", "Imported XHS images will be uploaded to this folder.") else if (state.backupFolderPicker) label("手机照片将上传到此文件夹。", "Phone photos will be uploaded to this folder.") else label("包含此文件夹及子文件夹中的照片。", "Includes photos in this folder and its subfolders."), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
        if (state.folderPath != "/") TextButton(onClick = {
            model.browseFolder(state.folderPath.substringBeforeLast('/', "").ifEmpty { "/" })
        }, enabled = !state.folderLoading) { Text(label("返回上级目录", "Parent folder")) }
        if (state.folderLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.folderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { model.browseFolder(state.folderPath) }) { Text(label("重试读取目录", "Retry loading folder")) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.folders, key = { it.name }) { folder ->
                ListItem(headlineContent = { Text(folder.name) }, leadingContent = { Icon(Icons.Outlined.Folder, null, tint = GalleryBlue) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null, tint = GalleryMuted) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = !state.folderLoading) {
                        model.browseFolder(state.folderPath.trimEnd('/') + "/" + folder.name)
                    })
            }
            if (state.folderMore) item {
                TextButton(onClick = { model.browseFolder(state.folderPath, more = true) }, enabled = !state.folderLoading) { Text(label("加载更多目录", "Load more folders")) }
            }
            if (state.folders.isEmpty() && !state.folderLoading && !state.folderMore && state.folderError == null) item { Text(label("此目录没有子文件夹", "No subfolders")) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = model::closeFolderPicker, modifier = Modifier.heightIn(min = 52.dp)) { Text(label("取消", "Cancel")) }
            Button(onClick = model::chooseFolder, enabled = !state.folderLoading && state.folderError == null,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(label("使用此文件夹", "Use this folder")) }
        }
    }
}

@Composable
private fun Viewer(media: List<MediaItem>, initialId: String, onClose: () -> Unit, onRemove: (MediaItem) -> Unit, onView: (MediaItem) -> Unit, downloading: Set<String>, onDownload: (MediaItem) -> Unit, onNotice: (String?) -> Unit) {
    val context = LocalContext.current
    val permissionMessage = label("请允许保存到手机相册后重试", "Allow storage access to save photos")
    var pendingDownload by remember { mutableStateOf<MediaItem?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { allowed ->
        val photo = pendingDownload
        pendingDownload = null
        if (allowed && photo != null) onDownload(photo) else onNotice(permissionMessage)
    }
    var confirmRemoval by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context as? Activity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val wasLight = controller?.isAppearanceLightStatusBars
        val wasLightNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (wasLight != null) controller?.isAppearanceLightStatusBars = wasLight
            if (wasLightNavigation != null) controller?.isAppearanceLightNavigationBars = wasLightNavigation
        }
    }
    BackHandler { onClose() }
    val pager = rememberPagerState(initialPage = media.indexOfFirst { it.id == initialId }.coerceAtLeast(0),
        pageCount = { media.size })
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    val current = media[pager.currentPage]
    LaunchedEffect(current.id) { onView(current) }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false }, title = { Text(label("移到废纸篓？", "Move to trash?")) },
        text = { Text(label("从 App 图库移除，30 天内可恢复。网盘原文件保留。", "Remove from this gallery. Restore within 30 days; cloud originals are kept.")) },
        confirmButton = { TextButton(onClick = { onRemove(current); confirmRemoval = false; onClose() }) { Text(label("移除", "Remove")) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text(label("取消", "Cancel")) } })
    Column(Modifier.fillMaxSize().background(Color(0xFF0C0E13)).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = .10f)) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回图库", tint = Color.White) }
            }
            Column(Modifier.weight(1f)) {
                Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.date.toString(), color = Color(0xFFAAB2C2), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(enabled = current.id !in downloading, onClick = {
                if (com.cloudgallery.app.data.canSaveQuickImage(context)) onDownload(current)
                else { pendingDownload = current; permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            }) {
                if (current.id in downloading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Download, label("下载原图", "Download original"), tint = Color.White)
            }
            IconButton(onClick = { confirmRemoval = true }) { Icon(Icons.Outlined.DeleteOutline, label("移到废纸篓", "Move to trash"), tint = Color.White) }
            Text("${pager.currentPage + 1} / ${media.size}", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.weight(1f), key = { media[it].id }) { index ->
            ZoomableScene(media[index], active = index == pager.currentPage, onZoom = { if (index == pager.currentPage) zoomed = it })
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = Color(0xFFAAB2C2))
                Text(albumTitle(current.album), color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text(label("双指缩放 · 左右切换 · 双击复位", "Pinch to zoom · Swipe to browse · Double tap to reset"), color = Color(0xFFAAB2C2), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ZoomableScene(photo: MediaItem, active: Boolean, onZoom: (Boolean) -> Unit) {
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center) {
        MediaImage(photo, Modifier.fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
            .pointerInput(photo.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // Leave ordinary single-finger swipes to the pager.
                        if (event.changes.count { it.pressed } >= 2 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val pan = event.calculatePan()
                            val maxX = size.width * (scale - 1) / 2f
                            val maxY = size.height * (scale - 1) / 2f
                            offset = if (scale == 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            event.changes.forEach { it.consume() }
                            onZoom(scale > 1f)
                        }
                    } while (event.changes.any { it.pressed })
                }
            }.pointerInput(photo.id) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero; onZoom(false) }) }
            .semantics { contentDescription = photo.name }, full = active, allowFallback = active, crop = false, dark = true)
    }
}

@Composable
private fun MediaImage(photo: MediaItem, modifier: Modifier = Modifier, full: Boolean = false, allowFallback: Boolean = true,
    crop: Boolean = true, dark: Boolean = false, onAspectRatio: ((Float) -> Unit)? = null) {
    if (!photo.remote) { Scene(photo.scene, modifier); return }
    val provider = LocalProvider.current ?: return
    val cache = LocalPhotoCache.current
    var retry by remember(photo.id, full) { mutableIntStateOf(0) }
    var file by remember(photo.id, photo.version, full) {
        mutableStateOf(cache.cached(photo, full) ?: if (full) cache.cached(photo, false) else null)
    }
    var failure by remember(photo.id, photo.version, full) { mutableStateOf<String?>(null) }
    var done by remember(photo.id, photo.version, full) { mutableStateOf(false) }
    LaunchedEffect(photo.id, photo.version, photo.thumb, full, retry) {
        failure = null; done = false
        try {
            coroutineScope {
                var originalReady = false
                val preview = if (full && file == null) launch {
                    try {
                        val thumb = cache.load(provider, photo, false, allowOriginalFallback = false)
                        if (!originalReady && thumb != null) file = thumb
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { }
                } else null
                try {
                    val loaded = cache.load(provider, photo, full, allowOriginalFallback = allowFallback)
                    if (loaded != null) { originalReady = full; file = loaded }
                } finally { preview?.cancel() }
            }
        }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure = safeError(error) }
        done = true
    }
    Box(modifier.background(if (dark) Color(0xFF0C0E13) else Color(0xFFE8EDF5)), contentAlignment = Alignment.Center) {
        if (file != null) AsyncImage(model = coil.request.ImageRequest.Builder(LocalContext.current).data(file)
            .memoryCacheKey(file!!.absolutePath).crossfade(false).build(), contentDescription = photo.name, modifier = Modifier.fillMaxSize(),
            contentScale = if (full || !crop) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { result ->
                val size = result.painter.intrinsicSize
                if (size.width > 0 && size.height > 0 && size.width.isFinite() && size.height.isFinite()) {
                    cache.rememberAspectRatio(photo, size.width / size.height)
                    onAspectRatio?.invoke(size.width / size.height)
                }
            },
            onError = { file?.delete(); file = if (full) cache.cached(photo, false) else null; failure = "图片解码失败" })
        if (full && !done && failure == null && file != null) Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            color = Color(0xD92B303D), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                Text(label("正在加载原图", "Loading original"), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (failure != null) Surface(color = if (dark) Color(0xE62B303D) else Color.White.copy(alpha = .94f), shape = RoundedCornerShape(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(localizedNotice(failure!!), color = if (dark) Color.White else GalleryInk, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                TextButton(onClick = { retry++ }) { Text(label("重试", "Retry"), color = if (dark) Color(0xFFAEC6FF) else GalleryBlue) }
            }
        } else if (file == null) {
            if (!done) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Text(label("无缩略图\n点击查看原图", "No preview\nTap to view original"), style = MaterialTheme.typography.labelSmall, color = if (dark) Color.White else GalleryMuted)
        }
    }
}

/** Code-drawn local placeholders, deliberately independent of remote photo services. */
@Composable
private fun Scene(seed: Int, modifier: Modifier = Modifier) {
    val skies = listOf(0xFFBCD6D1, 0xFFE9C4A1, 0xFF9CAFC0, 0xFFD7C4B7, 0xFFAED6D9, 0xFFB8B8C5)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(skies[seed]), Color(0xFFF1E7CE))))
        drawCircle(Color(0xFFF8E6B2), w * 0.10f, Offset(w * (0.25f + seed * 0.075f), h * 0.28f))
        fun ridge(color: Color, baseline: Float, peak: Float, shift: Float) {
            val path = Path().apply {
                moveTo(0f, h * baseline); lineTo(w * (0.23f + shift), h * peak)
                lineTo(w * 0.52f, h * (baseline - 0.09f)); lineTo(w * 0.78f, h * (peak + 0.08f))
                lineTo(w, h * baseline); lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(path, color)
        }
        ridge(Color(0xFF809B91), 0.65f, 0.34f, seed * 0.02f)
        ridge(Color(0xFF4D7767), 0.82f, 0.56f, -0.10f)
        ridge(Color(0xFF294E45), 1f, 0.78f, 0.12f)
    }
}

@Composable
private fun CreateAlbumDialog(state: GalleryState, model: GalleryViewModel) {
    var name by rememberSaveable(state.newAlbumParent) { mutableStateOf("") }
    AlertDialog(onDismissRequest = model::dismissCreateAlbum,
        title = { Text(label("新建相册", "New album")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label("在网盘中创建文件夹", "Create a folder in your drive"), style = MaterialTheme.typography.bodySmall)
                Text(state.newAlbumParent.orEmpty(), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !state.creatingAlbum,
                    singleLine = true, label = { Text(label("相册名称", "Album name")) })
                state.albumError?.let { Text(localizedNotice(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.creatingAlbum) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { model.createAlbum(name) }, enabled = name.isNotBlank() && !state.creatingAlbum) { Text(label("创建", "Create")) } },
        dismissButton = { TextButton(onClick = model::dismissCreateAlbum, enabled = !state.creatingAlbum) { Text(label("取消", "Cancel")) } })
}
