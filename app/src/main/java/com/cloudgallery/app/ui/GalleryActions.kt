package com.cloudgallery.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.cloudgallery.app.GalleryState
import com.cloudgallery.app.GalleryViewModel
import com.cloudgallery.app.data.GallerySort

@Composable
internal fun SortSelector(sort: GallerySort, onChoose: (GallerySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label(sort.title, sort.english), style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ArrowDropDown, label("选择排序方式", "Choose sort order"))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            GallerySort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(label(option.title, option.english)) },
                    leadingIcon = { if (sort == option) Icon(Icons.Outlined.Check, label("已选择", "Selected")) else Spacer(Modifier.size(24.dp)) },
                    onClick = { onChoose(option); expanded = false })
            }
        }
    }
}

@Composable
internal fun UploadDialog(state: GalleryState, model: GalleryViewModel, defaultMobile: Boolean) {
    var mobile by rememberSaveable(state.uploadUris) { mutableStateOf(defaultMobile) }
    if (state.folderPicker) return
    AlertDialog(onDismissRequest = model::dismissUpload,
        title = { Text(label("上传 ${state.uploadUris.size} 张图片", "Upload ${state.uploadUris.size} photos")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label("目标文件夹", "Destination folder"), style = MaterialTheme.typography.labelMedium)
                Text(state.uploadDestination)
                OutlinedButton(onClick = model::chooseUploadFolder, enabled = !state.preparingUpload) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(label("更改文件夹", "Change folder"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label("允许使用移动数据", "Allow mobile data"), Modifier.weight(1f))
                    Switch(mobile, { mobile = it }, enabled = !state.preparingUpload)
                }
                Text(label("关闭时等待 Wi-Fi。上传会在后台继续，文件名附加标识以避免覆盖同名图片。若目标目录不在当前图库中，请切换图库目录查看。",
                    "When off, uploads wait for Wi-Fi. Uploads continue in the background. A file name suffix prevents overwriting existing photos. If the destination is outside this gallery, switch gallery folders to view it."), style = MaterialTheme.typography.bodySmall, color = GalleryMuted)
                if (state.preparingUpload) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { model.uploadSelected(mobile) }, enabled = !state.preparingUpload) { Text(label("开始上传", "Upload")) } },
        dismissButton = { TextButton(onClick = model::dismissUpload, enabled = !state.preparingUpload) { Text(label("取消", "Cancel")) } })
}

@Composable
internal fun UploadStatus(work: List<WorkInfo>, model: GalleryViewModel) {
    val active = work.lastOrNull { !it.state.isFinished } ?: work.lastOrNull() ?: return
    val data = if (active.state.isFinished) active.outputData else active.progress
    val done = data.getInt("done", 0)
    val total = data.getInt("total", 0)
    val text = when (active.state) {
        WorkInfo.State.SUCCEEDED -> label("已上传 $done 张图片", "Uploaded $done photos")
        WorkInfo.State.FAILED -> label("上传失败 · 已完成 $done/$total", "Upload failed · $done/$total completed")
        WorkInfo.State.RUNNING -> label("正在上传 $done/$total", "Uploading $done/$total")
        WorkInfo.State.CANCELLED -> label("上传已取消", "Upload cancelled")
        else -> label("等待网络后上传", "Waiting for network to upload")
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.labelMedium)
                val detail = data.getString("error") ?: data.getString("name")
                if (!detail.isNullOrBlank()) Text(localizedNotice(detail), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            if (active.state == WorkInfo.State.FAILED || active.state == WorkInfo.State.CANCELLED) TextButton(onClick = model::retryUpload) { Text(label("重试", "Retry")) }
            if (active.state.isFinished) IconButton(onClick = model::dismissUploadStatus) { Icon(Icons.Outlined.Close, label("关闭上传状态", "Dismiss upload status")) }
            else {
                CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = model::cancelUpload) { Icon(Icons.Outlined.Close, label("取消上传", "Cancel upload")) }
            }
        }
    }
}
