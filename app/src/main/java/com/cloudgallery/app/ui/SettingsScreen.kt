package com.cloudgallery.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudgallery.app.BuildConfig
import com.cloudgallery.app.GalleryState
import com.cloudgallery.app.GalleryViewModel
import com.cloudgallery.app.data.*

internal val LocalEnglish = staticCompositionLocalOf { false }
@Composable internal fun label(zh: String, en: String) = if (LocalEnglish.current) en else zh
@Composable internal fun localizedNotice(text: String): String {
    if (!LocalEnglish.current) return text
    return when(text) {
        "原图已保存到手机相册 · PixNexus Gallary" -> "Original saved to Photos · PixNexus Gallary"
        "请允许保存到手机相册后重试" -> "Allow storage access to save photos"
        "请输入相册名称" -> "Enter an album name"
        "相册名称过长" -> "Album name is too long"
        "同名文件或文件夹已存在，请更换相册名称" -> "A file or folder with this name already exists"
        "文件缓存已清除" -> "File cache cleared"
        "浏览记录已清除" -> "Browsing history cleared"
        "搜索索引已重建" -> "Search index rebuilt"
        "请先连接百度网盘" -> "Connect Baidu Netdisk first"
        "请先选择图库文件夹" -> "Choose a gallery folder first"
        "请先选择备份目标文件夹" -> "Choose a backup destination first"
        "请至少选择一个相册" -> "Choose at least one album"
        "等待备份" -> "Waiting for backup"
        else -> when {
            text.startsWith("相册名称不能包含") -> "Album names cannot contain /, \\, :, *, ?, \" , <, > or |"
            text.startsWith("下载失败：") -> "Download failed. Check your connection, storage permissions and network settings."
            text.startsWith("正在备份：") -> "Backing up: " + text.substringAfter('：')
            text.startsWith("备份已完成") -> "Backup completed" + text.substringAfter('·', "").let { if (it.isBlank()) "" else " · $it" }
            text.contains("权限") || text.contains("访问手机") -> "Allow full access to device photos and videos in Android settings."
            text.contains("Wi-Fi") || text.contains("移动数据") -> "Waiting for Wi-Fi. You can allow mobile data in Settings → Network."
            else -> "Unable to complete this request. Check your connection, permissions and settings."
        }
    }
}

@Composable
internal fun settingsPageTitle(page: String): String = when (page) {
    "backup" -> label("自动备份", "Auto backup")
    "quick" -> label("快速导图", "Quick import")
    "trash" -> label("废纸篓", "Trash")
    "theme" -> label("主题偏好", "Theme")
    "language" -> label("语言", "Language")
    "cache" -> label("缓存", "Cache")
    "network" -> label("网络", "Network")
    else -> label("关于", "About")
}

@Composable
internal fun SettingsScreen(state: GalleryState, model: GalleryViewModel, page: String?, onPage: (String?) -> Unit) {
    BackHandler(enabled = page != null) { onPage(null) }
    val settings by model.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var permissionAction by remember { mutableStateOf<String?>(null) }
    var cacheSize by remember { mutableLongStateOf(0) }
    var backupStatus by remember { mutableStateOf(model.backupStatus()) }
    var quickTasks by remember { mutableStateOf<List<QuickImportTask>>(emptyList()) }
    var quickService by remember { mutableStateOf(model.quickServiceEnabled()) }
    var quickStatus by remember { mutableStateOf(model.quickStatus()) }
    val localSavePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.updateSettings { it.copy(quickSaveLocal = true) }
        else model.notify("需要照片保存权限，当前仍只上传网盘")
    }
    LaunchedEffect(page) {
        while (page == "quick") {
            quickTasks = model.quickTasks(); quickService = model.quickServiceEnabled(); quickStatus = model.quickStatus()
            kotlinx.coroutines.delay(1000)
        }
    }
    LaunchedEffect(page, state.notice) { cacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { model.photoCache.sizeBytes() } }
    LaunchedEffect(settings.backup) { while (true) { backupStatus = model.backupStatus(); kotlinx.coroutines.delay(3000) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMediaPermission(context, settings.videos)) {
            if (permissionAction == "albums") { model.loadDeviceAlbums(); dialog = "albums" } else model.startBackup()
        } else model.notify("需要完整照片访问权限；你可以稍后在系统设置中授权")
        permissionAction = null
    }
    fun requestMedia(action: String) {
        if (hasMediaPermission(context, settings.videos)) {
            if (action == "albums") { model.loadDeviceAlbums(); dialog = "albums" } else model.startBackup()
        } else { permissionAction = action; permission.launch(mediaPermissions(settings.videos)) }
    }
    val scroll = androidx.compose.runtime.saveable.rememberSaveable(page, saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (page == null) {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Outlined.Cloud, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(state.accountName ?: label("尚未连接", "Not connected"), style = MaterialTheme.typography.titleLarge)
                            Text(label("百度网盘", "Baidu Netdisk"), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                        }
                        if (state.accountName != null) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    if (state.accountName == null) Button(onClick = model::beginBaiduAuthorization,
                        enabled = model.baiduConfigured, modifier = Modifier.fillMaxWidth()) {
                        Text(label("连接百度网盘", "Connect Baidu Netdisk"))
                    } else {
                        SettingItem(Icons.Outlined.FolderOpen, label("图库文件夹", "Gallery folder"), state.root ?: label("尚未选择", "Not selected"),
                            enabled = !state.busy, onClick = model::openFolderPicker)
                        TextButton(onClick = model::beginBaiduAuthorization, enabled = !state.busy) { Text(label("重新授权", "Authorize again")) }
                    }
                }
            }
            SettingSection(label("偏好设置", "Preferences")) {
                SettingItem(Icons.Outlined.Bolt, label("快速导图", "Quick import"),
                    label("双音量键收进网盘", "Import with both volume keys"), onClick = { onPage("quick") })
                SettingItem(Icons.Outlined.Backup, label("自动备份", "Auto backup"),
                    if (settings.backup) label("已开启", "On") else label("未开启", "Off"), onClick = { onPage("backup") })
                SettingItem(Icons.Outlined.DeleteOutline, label("废纸篓", "Trash"),
                    label("${state.trash.size} 项 · 保留 30 天", "${state.trash.size} items · Kept for 30 days"), onClick = { onPage("trash") })
                SettingItem(Icons.Outlined.Palette, label("主题偏好", "Theme"), when(settings.theme) {
                    "light" -> label("浅色", "Light"); "dark" -> label("深色", "Dark"); else -> label("跟随系统", "System default") }, onClick = { onPage("theme") })
                SettingItem(Icons.Outlined.Language, label("语言", "Language"), when(settings.language) {
                    "en" -> "English"; "zh" -> "简体中文"; else -> label("跟随系统", "System default") }, onClick = { onPage("language") })
                SettingItem(Icons.Outlined.Storage, label("缓存", "Cache"),
                    label("已使用 %.1f MB".format(cacheSize / 1048576.0), "%.1f MB used".format(cacheSize / 1048576.0)), onClick = { onPage("cache") })
                SettingItem(Icons.Outlined.Wifi, label("网络", "Network"), label("移动数据使用偏好", "Mobile data preferences"), onClick = { onPage("network") })
                SettingItem(Icons.Outlined.Info, label("关于", "About"), "PixNexus Gallary ${BuildConfig.VERSION_NAME}", onClick = { onPage("about") })
            }
        } else when (page) {
            "quick" -> {
                SettingSection(label("小红书收图", "Import from Xiaohongshu")) {
                    SettingToggle(Icons.Outlined.Bolt, label("快速导图", "Quick import"),
                        if (settings.quickImport) label("已开启", "On") else label("按双音量键，导入当前图片", "Press both volume keys to import the current image"), settings.quickImport) {
                        if (it) dialog = "quickDisclosure" else model.updateSettings { s -> s.copy(quickImport = false) }
                    }
                    SettingItem(Icons.Outlined.Accessibility, label("无障碍服务", "Accessibility service"),
                        if (quickService) label("已启用", "Enabled") else label("点击前往系统设置开启", "Enable in Android settings"),
                        onClick = { dialog = "quickDisclosure" })
                    SettingItem(Icons.Outlined.DriveFolderUpload, label("目标文件夹", "Destination folder"),
                        settings.quickDestination.ifBlank { state.root ?: label("尚未选择", "Not selected") }, onClick = model::chooseQuickFolder)
                    SettingToggle(Icons.Outlined.SaveAlt, label("同时保存到手机相册", "Also save to device album"),
                        label("关闭后只上传网盘，临时文件会在上传后清理", "When off, upload only; temporary files are removed after upload"), settings.quickSaveLocal) {
                        if (it && !canSaveQuickImage(context)) localSavePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else model.updateSettings { s -> s.copy(quickSaveLocal = it) }
                    }
                    SettingToggle(Icons.Outlined.SignalCellularAlt, label("使用移动数据导图", "Import over mobile data"),
                        label("下载高清图片并上传网盘，可能产生流量费用", "Download and upload full images; data charges may apply"), settings.quickMobile) {
                        model.updateSettings { s -> s.copy(quickMobile = it) }
                    }
                    SettingToggle(Icons.Outlined.Vibration, label("震动反馈", "Haptic feedback"),
                        label("接收后轻震，上传成功短震，失败双震", "Light vibration when accepted; short on success, twice on failure"), settings.quickVibration) {
                        model.updateSettings { s -> s.copy(quickVibration = it) }
                    }
                }
                val diagnosticCopied = label("导图诊断已复制", "Import diagnostics copied")
                SettingItem(Icons.Outlined.Info, label("复制导图诊断", "Copy import diagnostics"),
                    label("包含服务、按键、按钮结构与图片尺寸，不含帖子正文或链接", "Service, volume keys, controls and image dimensions; no note text or links"), onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PixNexus button diagnostic", model.quickDiagnostic()))
                        model.notify(diagnosticCopied)
                    })
                Text(label("在小红书图片页按双音量键。仅操作能明确识别的独立分享和复制链接按钮；不使用坐标点击，识别不明确就停止并提示失败。剪贴板会被替换，默认等待 Wi-Fi。",
                    "Press both volume keys on an XHS image page. Only clearly identified share and copy-link buttons are used. No coordinate taps; uncertain controls stop the import. Your clipboard is replaced; Wi-Fi is required by default."),
                    style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                Text(label("路径和本地保存选项用于之后的导图，已加入队列的任务保留当时的选择。", "Folder and local-save changes apply to new imports. Queued tasks keep their original choices."),
                    style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                Text(if (LocalEnglish.current) label("", "Import status is shown below. Unavailable sources fail without saving another image.") else quickStatus,
                    style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                SettingSection(label("最近导入", "Recent imports")) {
                    if (quickTasks.isEmpty()) Text(label("还没有导入记录", "No imports yet"), Modifier.padding(16.dp), color = GalleryMuted)
                    quickTasks.take(20).forEach { task ->
                        val status = when (task.status) {
                            "completed" -> label("已完成", "Completed"); "failed" -> label("失败", "Failed")
                            "cancelled" -> label("已取消", "Cancelled")
                            "paused" -> label("已暂停", "Paused"); "capturing" -> label("正在取链接", "Getting link")
                            "resolving" -> label("正在解析", "Resolving"); "downloading" -> label("正在下载", "Downloading")
                            "saving" -> label("正在保存相册", "Saving to album")
                            "uploading" -> label("正在上传", "Uploading"); else -> label("等待网络", "Waiting for network")
                        }
                        SettingItem(Icons.Outlined.Image, label("第 ${task.page} / ${task.total} 张 · $status", "Image ${task.page} / ${task.total} · $status"),
                            java.time.Instant.ofEpochMilli(task.created).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) + "\n" + task.destination + if (!LocalEnglish.current && task.detail.isNotBlank()) "\n${task.detail}" else "",
                            trailing = if (task.status in setOf("failed", "paused") && task.link.isNotBlank()) {
                                { TextButton(onClick = { model.retryQuickImport(task.id) }) { Text(label("重试", "Retry")) } }
                            } else if (task.status in setOf("queued", "resolving", "downloading", "saving", "uploading")) {
                                { TextButton(onClick = { model.cancelQuickImport(task.id) }) { Text(label("取消", "Cancel")) } }
                            } else null)
                    }
                }
            }
            "backup" -> {
                SettingSection(label("备份选项", "Backup options")) {
                    SettingToggle(Icons.Outlined.Backup, label("自动备份", "Auto backup"),
                        if (settings.backup) localizedNotice(backupStatus) else label("将手机照片安全上传到网盘", "Upload phone photos to your drive"), settings.backup) {
                        if (it) requestMedia("backup") else model.updateSettings { s -> s.copy(backup = false) }
            }
            SettingToggle(Icons.Outlined.PhotoLibrary, label("包含全部相册", "Include all albums"), label("备份所有可访问的手机相册", "Back up all accessible device albums"), settings.allAlbums) {
                model.updateSettings { s -> s.copy(allAlbums = it, backup = if (!it && s.albums.isEmpty()) false else s.backup) }
            }
            SettingItem(Icons.Outlined.CollectionsBookmark, label("选择相册", "Choose albums"),
                if (settings.allAlbums) label("全部相册已包含", "All albums included") else label("已选择 ${settings.albums.size} 个相册", "${settings.albums.size} albums selected"),
                enabled = !settings.allAlbums, onClick = { requestMedia("albums") })
            SettingToggle(Icons.Outlined.SignalCellularAlt, label("使用移动数据备份", "Back up over mobile data"), label("可能产生流量费用", "May incur data charges"), settings.backupMobile) {
                model.updateSettings { s -> s.copy(backupMobile = it) }
            }
            SettingToggle(Icons.Outlined.Videocam, label("包含视频", "Include videos"), label("视频与照片一起备份", "Back up videos alongside photos"), settings.videos) {
                model.updateSettings { s -> s.copy(videos = it, backup = if (it && !hasMediaPermission(context, true)) false else s.backup) }
            }
            SettingItem(Icons.Outlined.DriveFolderUpload, label("目标文件夹", "Destination folder"),
                settings.destination.ifBlank { label("选择网盘备份位置", "Choose a folder on your drive") }, onClick = model::chooseBackupFolder)
        }
        Text(label("默认仅在 Wi-Fi 下备份。关闭自动备份会停止后续上传，已上传的文件会保留。后台执行时间由系统调度。",
            "Backups use Wi-Fi by default. Turning backup off stops further uploads and keeps uploaded files. Android schedules background work."),
            style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
            }
            "trash" -> SettingSection(label("最近删除", "Recently removed")) {
                Text(label("从 App 移除的照片保留 30 天，可随时恢复。网盘原文件不会删除；到期后无法从废纸篓恢复。", "Photos removed from this app can be restored for 30 days. Cloud originals are kept. Restoration from trash is unavailable after expiry."),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                if (state.trash.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(40.dp), tint = GalleryMuted)
                        Text(label("废纸篓是空的", "Trash is empty"), color = GalleryMuted)
                    }
                }
                state.trash.forEach { entry ->
                    SettingItem(Icons.Outlined.Image, entry.name,
                        label("剩余 ${((entry.deleted + 30L * 86400000 - System.currentTimeMillis()) / 86400000 + 1).coerceIn(1, 30)} 天", "Kept for up to 30 days"),
                        trailing = { TextButton(onClick = { model.restorePhoto(entry) }) { Text(label("恢复", "Restore")) } })
                }
            }
            "theme", "language" -> {
                val options = if (page == "theme") listOf("system" to label("跟随系统", "System default"), "light" to label("浅色", "Light"), "dark" to label("深色", "Dark"))
                    else listOf("system" to label("跟随系统", "System default"), "en" to "English", "zh" to "简体中文")
                SettingSection(if (page == "theme") label("选择主题", "Choose theme") else label("选择语言", "Choose language")) {
                    options.forEach { (value, title) ->
                        val selected = value == if (page == "theme") settings.theme else settings.language
                        Row(Modifier.fillMaxWidth().selectable(selected, role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { model.updateSettings { if (page == "theme") it.copy(theme = value) else it.copy(language = value) } })
                            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected, onClick = null)
                            Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            "cache" -> {
                SettingSection(label("存储管理", "Storage management")) {
                    SettingItem(Icons.Outlined.CleaningServices, label("清除文件缓存", "Clear file cache"),
                        label("已使用 %.1f MB · 上限 256 MB".format(cacheSize / 1048576.0), "%.1f MB used · 256 MB limit".format(cacheSize / 1048576.0)), onClick = { dialog = "cache" })
                    SettingItem(Icons.Outlined.History, label("清除浏览记录", "Clear browsing history"), label("清除本机的查看记录", "Remove local viewing history"), onClick = { dialog = "history" })
                    SettingItem(Icons.Outlined.Sync, label("刷新文件夹信息", "Refresh folder information"), label("重新检查云端文件变化", "Check for changes on your drive"), enabled = !state.busy, onClick = { model.maintenance("refresh") })
                    SettingItem(Icons.AutoMirrored.Outlined.ManageSearch, label("重建搜索索引", "Rebuild search index"), label("从本地图库重新生成索引", "Recreate the index from your local gallery"), onClick = { model.maintenance("index") })
                }
            }
            "network" -> {
                SettingSection(label("移动数据", "Mobile data")) {
                    Text(label("允许以下任务使用移动数据", "Allow these tasks to use mobile data"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                    SettingToggle(Icons.Outlined.Sync, label("同步文件", "Sync files"), label("更新网盘文件索引", "Update the drive file index"), settings.syncMobile) { model.updateSettings { s -> s.copy(syncMobile = it) } }
                    SettingToggle(Icons.Outlined.HighQuality, label("播放原始内容", "Load original content"), label("查看原图时下载完整文件", "Download full files when viewing originals"), settings.originalMobile) { model.updateSettings { s -> s.copy(originalMobile = it) } }
                }
            }
            "about" -> {
                SettingSection(label("应用信息", "App information")) {
                    SettingItem(Icons.Outlined.Info, "PixNexus Gallary", label("版本 ${BuildConfig.VERSION_NAME}", "Version ${BuildConfig.VERSION_NAME}"))
                    SettingItem(Icons.Outlined.PrivacyTip, label("隐私政策", "Privacy policy"), label("了解数据如何存储与使用", "How your data is stored and used"), onClick = { dialog = "privacy" })
                }
            }
        }
        Text(label("授权信息仅在设备内加密保存", "Authorization is encrypted on this device"),
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp), style = MaterialTheme.typography.labelSmall, color = GalleryMuted)
    }
    state.notice?.let { notice -> AlertDialog(onDismissRequest = { model.notify(null) },
        title = { Text(label("提示", "Notice")) }, text = { Text(localizedNotice(notice)) },
        confirmButton = { TextButton(onClick = { model.notify(null) }) { Text(label("知道了", "OK")) } }) }
    dialog?.let { kind ->
        val close = { dialog = null }
        when (kind) {
            "quickDisclosure" -> AlertDialog(onDismissRequest = close,
                title = { Text(label("开启快速导图", "Enable quick import")) },
                text = { Text(label("此功能使用无障碍服务读取小红书图片页码，并在你按双音量键后打开分享菜单、点击复制链接。随后短暂获得窗口焦点读取该链接，下载当前高清图片并上传到所选网盘文件夹。剪贴板内容会被替换。不会自动点赞、关注、发消息或发布内容。请关闭其他监听音量键的无障碍服务。你可随时在这里关闭功能或在系统设置撤销服务。",
                    "This uses accessibility to read the image page and, only after you press both volume keys, open the share menu and copy the note link. A brief focus window reads that link, then the selected image is downloaded and uploaded to your drive folder. Your clipboard is replaced. It does not like, follow, message or publish. Disable other accessibility services that filter volume keys. You can turn this off here or revoke the service in Android settings.") ) },
                confirmButton = { TextButton(onClick = {
                    model.startQuickImport(); close()
                    if (state.accountName != null && (settings.quickDestination.isNotBlank() || !state.root.isNullOrBlank()))
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(label("同意并前往开启", "Agree and open settings")) } },
                dismissButton = { TextButton(onClick = close) { Text(label("取消", "Cancel")) } })
            "albums" -> {
                var chosen by remember { mutableStateOf(settings.albums) }
                AlertDialog(onDismissRequest = close, title = { Text(label("选择相册", "Choose albums")) },
                    text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        if (state.deviceAlbums.isEmpty()) Text(label("暂无可访问相册", "No accessible albums"))
                        state.deviceAlbums.forEach { (id, name) -> Row(Modifier.fillMaxWidth().clickable {
                            chosen = if (id in chosen) chosen - id else chosen + id
                        }, verticalAlignment = Alignment.CenterVertically) { Checkbox(id in chosen, null); Text(name) } }
                    } }, confirmButton = { TextButton(onClick = { model.updateSettings { it.copy(albums = chosen, backup = it.backup && chosen.isNotEmpty()) }; close() }) { Text(label("保存", "Save")) } },
                    dismissButton = { TextButton(onClick = close) { Text(label("取消", "Cancel")) } })
            }
            "cache", "history" -> AlertDialog(onDismissRequest = close,
                title = { Text(if (kind == "cache") label("清除文件缓存？", "Clear file cache?") else label("清除浏览记录？", "Clear browsing history?")) },
                text = { Text(if (kind == "cache") label("离线照片需要重新下载，网盘文件和授权不会受影响。", "Offline photos will need to be downloaded again. Drive files and authorization are kept.") else label("清除本机查看记录，不影响照片。", "Removes local viewing history. Photos are kept.")) },
                confirmButton = { TextButton(onClick = { model.maintenance(kind); close() }) { Text(label("清除", "Clear")) } },
                dismissButton = { TextButton(onClick = close) { Text(label("取消", "Cancel")) } })
            "privacy" -> AlertDialog(onDismissRequest = close, title = { Text(label("隐私政策", "Privacy policy")) },
                text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(label("PixNexus Gallary 直接连接百度网盘，不经过独立业务服务器。授权令牌与所选图库目录使用 Android Keystore 加密保存。\n\n照片索引、浏览记录、废纸篓记录和图片缓存存储在本机。开启自动备份后，App 会读取你允许访问的手机相册并上传照片；视频需单独开启。备份状态和去重记录保存在本机。手动上传仅访问你在系统选择器中选中的图片，上传前可选择目标目录；任务与临时副本保存在本机，上传成功后清理。开启快速导图后，无障碍服务仅在你按双音量键时读取小红书图片页码并操作分享菜单复制链接，随后短暂获得窗口焦点读取该链接。链接用于下载当前图片并上传到所选网盘目录，导入任务和临时文件保存在本机；只有开启“同时保存到手机相册”才会发布本地相册副本。成功上传后清理临时图片及任务中的链接。\n\n清除应用数据会移除本机记录和授权，不会删除已经上传的网盘文件。百度网盘的数据处理受百度的隐私政策约束。\n\n更新日期：2026-09-27", "PixNexus Gallary connects directly to Baidu Netdisk. Authorization tokens and the gallery folder are encrypted with Android Keystore.\n\nIndexes, viewing history, trash records and image caches stay on this device. Automatic backup reads and uploads device media when enabled with your permission. Videos require a separate option. Backup status and deduplication records stay locally. Manual uploads access only photos selected in the system picker. You choose the destination before uploading. Tasks and temporary copies stay locally and are cleared on success. When Quick import is enabled, accessibility reads the XHS image page and copies its share link only after you press both volume keys. A brief focus window reads that link to download and upload the current image to your chosen folder. Tasks and temporary files stay on this device. An album copy is published only if Also save to device album is enabled. Temporary images and task links are cleared after successful upload.\n\nClearing app data removes local records and authorization, but keeps uploaded drive files. Baidu handles cloud data under its own privacy policy.\n\nUpdated: 2026-09-27")
                 ) } }, confirmButton = { TextButton(onClick = close) { Text(label("知道了", "OK")) } })
        }
    }
}

@Composable private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, color = GalleryMuted)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.fillMaxWidth(), content = content) }
    }
}

@Composable private fun SettingItem(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true,
    onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else GalleryMuted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) GalleryInk else GalleryMuted)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
        }
        if (trailing != null) trailing() else if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, tint = GalleryMuted)
    }
}

@Composable private fun SettingToggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingItem(icon, title, subtitle, onClick = { onChange(!checked) }, trailing = { Switch(checked, onCheckedChange = onChange) })
}
