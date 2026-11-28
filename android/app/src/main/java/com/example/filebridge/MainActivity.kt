package com.example.filebridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FileBridgeTheme { App() } }
    }
}

private const val GH_OWNER = "zjh1934"
// 数据仓（私有）与代码仓 filebridge-android 彻底分开：
// 这个仓库的根目录 = /data/user_persistent_data 的根目录，全量镜像，不只是几个中转文件。
private const val GH_REPO = "filebridge-persistent"
private const val GH_ROOT = ""

// ---------------------------------------------------------------- 小工具
private fun queryName(ctx: Context, uri: Uri): String {
    var name = "导入文件_${System.currentTimeMillis()}.txt"
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
    }
    return name
}

private fun joinPath(parent: String, name: String) =
    listOf(parent, name).filter { it.isNotEmpty() }.joinToString("/")

private fun fmtSize(n: Long): String = when {
    n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0)
    n >= 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}

private fun mimeGuess(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: when (ext) {
            "md", "txt", "json", "log", "csv", "xml", "yml", "yaml" -> "text/plain"
            else -> "application/octet-stream"
        }
}

/** 文件类型徽章：短标 + 颜色，颜色全部来自设计 token */
private fun badge(name: String): Pair<String, Color> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "md" -> "MD" to FB.Accent
        "txt", "log" -> "TXT" to FB.FileBlue
        "json", "yml", "yaml", "xml", "csv", "py", "kt", "java", "sh" -> "{ }" to FB.FileBlue
        "png", "jpg", "jpeg", "gif", "webp" -> "IMG" to FB.FileViolet
        "pdf" -> "PDF" to FB.Danger
        "zip", "rar", "7z", "tar", "gz" -> "ZIP" to FB.FileAmber
        else -> "• • •" to FB.Slate
    }
}

private fun shortType(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext.isEmpty()) "文件" else ext.uppercase() + " 文件"
}

private fun viewIntent(ctx: Context, f: File, mime: String): Intent {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    DevLog.i("OPEN", "打开 ${f.name} · mime=$mime")
    val m = if (mime == "application/octet-stream") "*/*" else mime
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.packageManager.queryIntentActivities(intent, 0).forEach { r ->
        ctx.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return intent
}

private fun shareIntent(ctx: Context, f: File): Intent {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    DevLog.i("SHARE", "分享 ${f.name} · ${f.length()} B")
    return Intent(Intent.ACTION_SEND).apply {
        type = mimeGuess(f.name)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, f.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(ctx.contentResolver, "file", uri)
    }
}

/** NetworkOnMainThreadException 等异常的 message 是 null，不兜底就会出现「：」后面一片空白 */
private fun msgOf(e: Exception): String =
    e.message?.takeIf { it.isNotBlank() } ?: e.toString()

private fun friendlyError(e: Exception): String {
    val m = msgOf(e)
    return when {
        m.contains("主线程") || m.contains("NetworkOnMainThread") ->
            "APP 内部调度出错，请升级到最新版本后重试。"
        m.contains("令牌无效") || m.contains("401") -> "令牌已经失效了，重新粘一次就能继续用。"
        m.contains("failed to connect") || m.contains("Unable to resolve") ||
            m.contains("timeout", ignoreCase = true) -> "手机现在连不上 GitHub，检查一下网络再试。"
        else -> "没能读到沙盒文件：$m"
    }
}

// ---------------------------------------------------------------- 可复用组件
@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun StatBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(shape = FB.ShapeCard, color = FB.AccentSoft, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(text = value, style = MaterialTheme.typography.titleLarge, color = FB.Accent)
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    detail: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(FB.ShapeSmall).clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(RoundedCornerShape(50))
                .background(if (danger) FB.Danger else FB.Accent)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) FB.Danger else FB.Ink,
            )
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FileCard(
    f: FileItem,
    onAction: () -> Unit,
    selected: Boolean = false,
    selectMode: Boolean = false,
    onToggle: () -> Unit = {},
) {
    val (tag, color) = badge(f.name)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = FB.ShapeCard,
        colors = CardDefaults.cardColors(containerColor = FB.Surface),
    ) {
        Row(
            Modifier.padding(14.dp)
                .clickable { if (selectMode) onToggle() else onAction() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode) {
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) FB.Accent else FB.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) { Text(text = if (selected) "✓" else "", fontSize = 14.sp, color = Color.White) }
                Spacer(Modifier.width(10.dp))
            }
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = tag, fontSize = 11.sp, color = color,
                    style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text = f.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = "${fmtSize(f.size)} · ${shortType(f.name)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            if (!selectMode) {
                Surface(
                    shape = FB.ShapeSmall,
                    color = FB.AccentSoft,
                    modifier = Modifier.clip(FB.ShapeSmall).clickable(onClick = onAction),
                ) {
                    Text(text = "⋯", fontSize = 15.sp, color = FB.Accent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    d: FileItem,
    onClick: () -> Unit,
    onAction: () -> Unit = {},
    selected: Boolean = false,
    selectMode: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(FB.ShapeSmall)
            .clickable { if (selectMode) onToggle() else onClick() }
            .padding(vertical = 13.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) FB.Accent else FB.AccentSoft),
                contentAlignment = Alignment.Center,
            ) { Text(text = if (selected) "✓" else "", fontSize = 14.sp, color = Color.White) }
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(FB.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "›", fontSize = 21.sp, color = FB.Accent)
        }
        Spacer(Modifier.width(14.dp))
        Text(text = d.name, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!selectMode) {
            Surface(
                shape = FB.ShapeSmall,
                color = FB.AccentSoft,
                modifier = Modifier.clip(FB.ShapeSmall).clickable(onClick = onAction),
            ) {
                Text(text = "⋯", fontSize = 14.sp, color = FB.Accent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        } else {
            Text(text = "选择", style = MaterialTheme.typography.bodySmall)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(FB.Line))
}

/** 网格视图单元：云盘式大图标 + 名称 + 大小，选中态明确 */
@Composable
private fun GridCell(
    f: FileItem,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onAction: () -> Unit,
) {
    val (tag, color) = badge(f.name)
    Card(
        modifier = Modifier.fillMaxWidth().padding(4.dp).clickable {
            if (selectMode) onToggle() else onClick()
        },
        shape = FB.ShapeCard,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) FB.AccentSoft else FB.Surface
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Text(text = tag, fontSize = 10.sp, color = color) }
                Spacer(Modifier.weight(1f))
                if (selectMode) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                            .background(if (selected) FB.Accent else FB.AccentSoft),
                        contentAlignment = Alignment.Center,
                    ) { Text(text = if (selected) "✓" else "", fontSize = 12.sp, color = Color.White) }
                } else {
                    Text(text = "⋯", fontSize = 15.sp, color = FB.Slate,
                        modifier = Modifier.clickable(onClick = onAction))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(text = f.name, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(text = if (f.type == "dir") "文件夹" else fmtSize(f.size),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- 主界面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("filebridge", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var parent by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var tokenDraft by remember { mutableStateOf(token) }
    var actionItem by remember { mutableStateOf<FileItem?>(null) }
    var createSheet by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<FileItem?>(null) }
    var editText by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf<FileItem?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newFileOpen by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newDirOpen by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }
    var trashOpen by remember { mutableStateOf(false) }
    var trashItems by remember { mutableStateOf<List<TrashItem>>(emptyList()) }
    var devlogOpen by remember { mutableStateOf(false) }
    var logSummary by remember { mutableStateOf<DevLog.Summary?>(null) }
    var exportedPath by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, FileItem?>>("import" to null) }
    // v1.6 云盘式交互：返回键、搜索、排序、视图、多选、详情、同步提示
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(0) }        // 0 名称 / 1 大小 / 2 类型
    var gridMode by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var infoItem by remember { mutableStateOf<FileItem?>(null) }
    var folderAction by remember { mutableStateOf<FileItem?>(null) }
    var confirmBatchDel by remember { mutableStateOf(false) }
    var confirmFolderDel by remember { mutableStateOf<FileItem?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var globalOpen by remember { mutableStateOf(false) }
    var globalHits by remember { mutableStateOf<List<String>>(emptyList()) }
    var globalBusy by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf(true) }
    // v1.7 内嵌 MD 编辑器 + 重命名
    var mdItem by remember { mutableStateOf<FileItem?>(null) }
    var mdText by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    // v1.8 下载中心
    var dlOpen by remember { mutableStateOf(false) }
    var pendingDl by remember { mutableStateOf<(() -> Unit)?>(null) }

    val gh = remember(token) { GitHubStore(ctx, GH_OWNER, GH_REPO, token, GH_ROOT) }
    val scope = rememberCoroutineScope()

    // Android 9 及以下写公共 Download 需要 WRITE_EXTERNAL_STORAGE（maxSdk 28）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingDl?.invoke()
        else Toast.makeText(ctx, "没给存储权限，下载会失败", Toast.LENGTH_SHORT).show()
        pendingDl = null
    }

    fun ensureDownload(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            action()
        } else if (ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingDl = action
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** 下载中心持有当前 GitHubStore；token 变化时会重新赋值 */
    LaunchedEffect(token) { DownloadCenter.currentStore = gh }
    LaunchedEffect(Unit) {
        DownloadCenter.init(ctx)
        DevLog.i("DL", "下载中心就绪 · 目标目录 Download/${DownloadCenter.FOLDER}/")
    }

    // 内嵌 MD 编辑器是全屏独立页：放在最前面，主界面的返回键与弹窗都不会干扰它
    mdItem?.let { f ->
        MdEditorScreen(
            item = f,
            initialText = mdText,
            onSave = { text -> gh.saveText(f.rel, text) },
            onClose = { mdItem = null },
            onError = { msg ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                DevLog.e("MD", msg)
            },
        )
        return
    }

    fun reportError(msg: String) {
        error = msg
        DevLog.e("UI", msg)
    }

    suspend fun refresh() {
        try {
            busy = true
            if (token.isBlank()) {
                dirs = emptyList<FileItem>()
                files = emptyList<FileItem>()
                error = ""
                busy = false
                return
            }
            val r = gh.list(parent)
            dirs = r.dirs
            files = r.files
            error = ""
            DevLog.i("LIST", "沙盒 /$parent · ${dirs.size} 目录 · ${files.size} 文件")
        } catch (e: Exception) {
            reportError(friendlyError(e))
        } finally {
            busy = false
        }
    }

    // 返回键：先退出多选/搜索，再逐级返回上一级目录；到根目录才询问是否退出。
    // 之前完全没有处理，系统返回键会直接杀掉 Activity —— 这是 v1.5 最明显的半成品感。
    BackHandler(enabled = true) {
        when {
            dlOpen -> dlOpen = false
            selectMode -> { selectMode = false; selected = emptySet() }
            searchOpen -> { searchOpen = false; query = "" }
            globalOpen -> globalOpen = false
            parent.isNotBlank() -> {
                parent = parent.substringBeforeLast('/', "")
                selected = emptySet()
                DevLog.i("NAV", "返回上一级 → /$parent")
            }
            else -> confirmExit = true
        }
    }

    LaunchedEffect(Unit) {
        DevLog.init(ctx)
        DevLog.i("APP", "界面启动 · 沙盒管理器 v1.8 · 内嵌 MD 编辑器 / 下载中心 / 改动即时提交仓库")
    }
    DisposableEffect(Unit) {
        onDispose { DevLog.i("APP", "界面退到后台") }
    }
    LaunchedEffect(token, parent) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            DevLog.w("FILE", "用户取消了选择")
        } else {
            scope.launch {
                try {
                    busy = true
                    val name = queryName(ctx, uri)
                    val (action, item) = pending
                    DevLog.i("UI", "选择 $name · 动作=$action")
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("读不到所选文件")
                    if (action == "overwrite" && item != null) {
                        gh.put(item.rel, bytes, "FileBridge: 覆盖 ${item.name}")
                    } else {
                        gh.importFrom(uri, name, parent)
                    }
                    refresh()
                } catch (e: Exception) {
                    reportError("导入失败：${msgOf(e)}")
                    busy = false
                }
            }
        }
    }

    val saveMd = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val src = File(exportedPath)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    src.inputStream().use { it.copyTo(os) }
                } ?: error("无法打开输出流")
                DevLog.i("LOG", "开发者日志另存为成功")
            } catch (e: Exception) {
                reportError("另存为失败：${msgOf(e)}")
            }
        }
    }

    fun pick(action: String, item: FileItem? = null) {
        pending = action to item
        picker.launch("*/*")
    }

    fun saveToken() {
        token = tokenDraft.trim()
        prefs.edit().putString("token", token).apply()
        showConnect = false
        DevLog.i("CFG", "保存令牌 · Token=[REDACTED]")
        scope.launch { refresh() }
    }

    val dlTasks by DownloadCenter.tasks.collectAsState()

    val segments = parent.split("/").filter { it.isNotEmpty() }

    fun toggleSel(rel: String) {
        selected = if (rel in selected) selected - rel else selected + rel
    }

    fun doBatchDelete() {
        scope.launch {
            try {
                busy = true
                val n = selected.size
                for (rel in selected) {
                    if (dirs.any { it.rel == rel }) gh.deleteFolder(rel) else gh.delete(rel)
                }
                selectMode = false
                selected = emptySet()
                confirmBatchDel = false
                DevLog.i("UI", "批量删除 $n 项 → 已提交仓库")
                refresh()
                Toast.makeText(ctx, "$n 项已移到回收站（仓库已更新）", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                reportError("批量删除失败：${msgOf(e)}")
                busy = false
            }
        }
    }

    val shownDirs = dirs.filter { it.name.contains(query, ignoreCase = true) }
        .let { l -> if (sortMode == 1) l.sortedByDescending { it.size } else l.sortedBy { it.name } }
    val shownFiles = files.filter { it.name.contains(query, ignoreCase = true) }
        .let { l -> when (sortMode) {
            1 -> l.sortedByDescending { it.size }
            2 -> l.sortedBy { shortType(it.name) }
            else -> l.sortedBy { it.name }
        } }
    val allShown: List<FileItem> = shownDirs + shownFiles

    // ------------------------------------------------------------ 页面骨架
    androidx.compose.material3.Scaffold(
        containerColor = FB.Paper,
        bottomBar = {
            if (selectMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = FB.Surface,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "已选 ${selected.size} 项",
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            selected = if (selected.size == allShown.size) emptySet()
                                       else allShown.map { it.rel }.toSet()
                        }) { Text("全选") }
                        TextButton(onClick = {
                            val picked = allShown.filter { it.rel in selected }
                            ensureDownload {
                                var n = 0
                                for (it in picked) {
                                    if (it.type == "dir") continue
                                    DownloadCenter.enqueue(it.rel, sizeHint = it.size)
                                    n++
                                }
                                Toast.makeText(ctx, "$n 个文件已加入下载队列", Toast.LENGTH_SHORT).show()
                            }
                            selectMode = false
                            selected = emptySet()
                        }) { Text("下载") }
                        if (selected.size == 1) {
                            TextButton(onClick = {
                                val t = allShown.firstOrNull { it.rel == selected.first() }
                                if (t != null) {
                                    renameItem = t
                                    renameText = t.name
                                    selectMode = false
                                    selected = emptySet()
                                }
                            }) { Text("重命名") }
                        }
                        Button(
                            onClick = { if (selected.isNotEmpty()) confirmBatchDel = true },
                            shape = FB.ShapePill,
                            colors = ButtonDefaults.buttonColors(containerColor = FB.Danger),
                        ) { Text("删除") }
                        TextButton(onClick = { selectMode = false; selected = emptySet() }) {
                            Text("取消")
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { createSheet = true },
                containerColor = FB.Accent,
                contentColor = Color.White,
            ) { Text(text = "＋ 添加", style = MaterialTheme.typography.labelLarge) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 顶部：标题 + 路径面包屑 + 状态（路径是这一版的主视觉）
            Column(
                Modifier.fillMaxWidth().background(FB.Paper)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(text = "沙盒管理器", style = MaterialTheme.typography.displaySmall)
                        Text(text = "在手机上查看和编辑沙盒持久区的文件",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        shape = FB.ShapePill,
                        color = if (token.isBlank()) FB.AccentSoft else FB.Surface,
                        modifier = Modifier.clip(FB.ShapePill).clickable {
                            tokenDraft = token
                            showConnect = true
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50))
                                .background(if (token.isBlank()) FB.FileAmber else FB.Accent))
                            Spacer(Modifier.width(7.dp))
                            Text(text = if (token.isBlank()) "去连接" else "已连接",
                                fontSize = 12.sp, color = FB.Ink)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Surface(
                    shape = FB.ShapeSmall,
                    color = FB.Surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "全部文件",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (parent.isEmpty()) FB.Accent else FB.Slate,
                        modifier = Modifier.clickable { parent = "" },
                    )
                    segments.forEachIndexed { i, seg ->
                        Text(text = " / ", style = MaterialTheme.typography.titleMedium,
                            color = FB.Line)
                        Text(
                            text = seg,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (i == segments.lastIndex) FB.Accent else FB.Slate,
                            modifier = Modifier.clickable {
                                parent = segments.take(i + 1).joinToString("/")
                            },
                        )
                    }
                }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (token.isBlank())
                        "还没连上仓库，粘一次令牌就能看到持久区里的全部文件。"
                    else
                        "连的是私有仓库 $GH_OWNER/$GH_REPO，它是跨对话持久区的完整镜像，当前目录 ${dirs.size + files.size} 项。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = FB.Accent,
                    trackColor = FB.AccentSoft,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    // 首次使用：连接卡替代藏在设置里的表单
                    if (token.isBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            shape = FB.ShapeCard,
                            colors = CardDefaults.cardColors(containerColor = FB.AccentSoft),
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                SectionTitle("先连上沙盒")
                                Text(
                                    text = "令牌能读写那个私有仓库。粘一次保存，之后打开就能看到持久区里的全部文件和目录，不用再填。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(14.dp))
                                OutlinedTextField(
                                    value = tokenDraft,
                                    onValueChange = { tokenDraft = it },
                                    label = { Text("令牌") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = FB.ShapeSmall,
                                    singleLine = true,
                                )
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = { saveToken() },
                                    shape = FB.ShapePill,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent),
                                ) { Text("保存并查看文件") }
                            }
                        }
                    } else if (error.isNotEmpty()) {
                        // 错误态：说清发生了什么 + 可行动的下一步
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            shape = FB.ShapeCard,
                            colors = CardDefaults.cardColors(containerColor = FB.DangerSoft),
                        ) {
                            Column(Modifier.padding(18.dp)) {
                                Text(text = "没能读到沙盒文件",
                                    style = MaterialTheme.typography.titleLarge, color = FB.Danger)
                                Spacer(Modifier.height(6.dp))
                                Text(text = error, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { scope.launch { refresh() } },
                                        shape = FB.ShapePill,
                                        colors = ButtonDefaults.buttonColors(containerColor = FB.Danger)
                                    ) { Text("重新试一次") }
                                    TextButton(onClick = { tokenDraft = token; showConnect = true }) {
                                        Text("检查令牌", color = FB.Danger)
                                    }
                                }
                            }
                        }
                    }
                }

                // 常用入口：一行小 chip，不再是一排挤在一起的按钮
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = { scope.launch { refresh() } },
                            label = { Text("刷新") },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(containerColor = FB.Surface),
                        )
                        AssistChip(
                            onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                            label = { Text(if (searchOpen) "关闭搜索" else "搜索") },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (searchOpen) FB.AccentSoft else FB.Surface),
                        )
                        AssistChip(
                            onClick = { sortMode = (sortMode + 1) % 3 },
                            label = { Text(when (sortMode) {
                                0 -> "排序·名称"; 1 -> "排序·大小"; else -> "排序·类型" }) },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(containerColor = FB.Surface),
                        )
                        AssistChip(
                            onClick = { gridMode = !gridMode },
                            label = { Text(if (gridMode) "列表" else "网格") },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(containerColor = FB.Surface),
                        )
                        AssistChip(
                            onClick = {
                                selectMode = !selectMode
                                if (!selectMode) selected = emptySet()
                            },
                            label = { Text(if (selectMode) "退出多选" else "多选") },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selectMode) FB.AccentSoft else FB.Surface),
                        )
                        AssistChip(
                            onClick = { dlOpen = true },
                            label = {
                                val active = dlTasks.count { it.status == DownloadCenter.Status.RUNNING ||
                                    it.status == DownloadCenter.Status.QUEUED }
                                Text(if (active > 0) "下载管理 · $active" else "下载管理")
                            },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (dlTasks.any {
                                    it.status == DownloadCenter.Status.RUNNING }) FB.AccentSoft else FB.Surface),
                        )
                        AssistChip(
                            onClick = {
                                globalOpen = true
                                scope.launch {
                                    try {
                                        globalBusy = true
                                        globalHits = gh.allPaths()
                                    } catch (e: Exception) {
                                        reportError("全仓索引失败：${msgOf(e)}")
                                    } finally { globalBusy = false }
                                }
                            },
                            label = { Text("全仓搜索") },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(containerColor = FB.Surface),
                        )
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    try {
                                        trashItems = gh.trash()
                                        trashOpen = true
                                    } catch (e: Exception) {
                                        reportError("回收站读取失败：${msgOf(e)}")
                                    }
                                }
                            },
                            label = { Text("回收站") },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(containerColor = FB.Surface),
                        )
                        AssistChip(
                            onClick = {
                                logSummary = DevLog.summary()
                                devlogOpen = true
                            },
                            label = { Text("运行日志") },
                            shape = FB.ShapePill,
                            colors = AssistChipDefaults.assistChipColors(containerColor = FB.Surface),
                        )
                    }
                }

                if (searchOpen) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("在当前目录里找文件") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            shape = FB.ShapeSmall,
                            singleLine = true,
                        )
                    }
                }

                if (syncNote && token.isNotBlank() && error.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            shape = FB.ShapeCard,
                            colors = CardDefaults.cardColors(containerColor = FB.AccentSoft),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(text = "改动会立刻提交到仓库",
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "编辑、新建、删除、改名都会即时写入私有镜像仓。沙盒里 AI 新写的文件，说一句「同步持久区」我就回灌，两边就一致了。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { syncNote = false }) { Text("知道了") }
                            }
                        }
                    }
                }

                if (gridMode) {
                    items(allShown.chunked(2)) { pair: List<FileItem> ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { it: FileItem ->
                                Box(Modifier.weight(1f)) {
                                    GridCell(
                                        f = it,
                                        selected = it.rel in selected,
                                        selectMode = selectMode,
                                        onClick = {
                                            if (it.type == "dir") parent = joinPath(parent, it.name)
                                            else actionItem = it
                                        },
                                        onToggle = { toggleSel(it.rel) },
                                        onAction = {
                                            if (it.type == "dir") folderAction = it
                                            else actionItem = it
                                        },
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    items(shownDirs) { d: FileItem ->
                        FolderRow(
                            d = d,
                            onClick = { parent = joinPath(parent, d.name) },
                            onAction = { folderAction = d },
                            selected = d.rel in selected,
                            selectMode = selectMode,
                            onToggle = { toggleSel(d.rel) },
                        )
                    }
                    items(shownFiles) { f: FileItem ->
                        FileCard(
                            f = f,
                            onAction = { actionItem = f },
                            selected = f.rel in selected,
                            selectMode = selectMode,
                            onToggle = { toggleSel(f.rel) },
                        )
                    }
                }

                if (token.isNotBlank() && error.isEmpty() && allShown.isEmpty() && !busy) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(text = "这个目录还是空的", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text(text = "新建一个文件，或者从手机里导入一个。",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = { newFileOpen = true }, shape = FB.ShapePill,
                                colors = ButtonDefaults.buttonColors(containerColor = FB.Accent)
                            ) { Text("新建文件") }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${shownDirs.size} 个文件夹 · ${shownFiles.size} 个文件"
                                + if (selectMode) " · 已选 ${selected.size}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                item { Spacer(Modifier.height(88.dp)) }   // 给悬浮按钮留位置
            }
        }
    }

    // ------------------------------------------------------------ v1.8 下载管理页面
    if (dlOpen) {
        DownloadSheet(onDismiss = { dlOpen = false })
    }

    // ------------------------------------------------------------ v1.6 新增交互
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("要退出沙盒管理器吗？") },
            text = { Text("已经在最顶层目录了。再按返回键会退出应用，改动都已经提交到仓库，不会丢。") },
            confirmButton = {
                Button(onClick = { (ctx as? android.app.Activity)?.finish() },
                    shape = FB.ShapePill) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("留在这") } },
        )
    }

    if (confirmBatchDel) {
        AlertDialog(
            onDismissRequest = { confirmBatchDel = false },
            title = { Text("把选中的 ${selected.size} 项移到回收站？") },
            text = { Text("会立刻提交到镜像仓，之后能在回收站里恢复。") },
            confirmButton = {
                Button(onClick = { doBatchDelete() }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Danger)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmBatchDel = false }) { Text("取消") } },
        )
    }

    folderAction?.let { d: FileItem ->
        ModalBottomSheet(
            onDismissRequest = { folderAction = null },
            containerColor = FB.Surface,
            shape = FB.ShapeSheet,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(text = d.name, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = "文件夹 · /${d.rel}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                ActionRow("进入", "查看这个目录里的内容") {
                    folderAction = null
                    parent = joinPath(parent, d.name)
                }
                ActionRow("下载整个文件夹", "递归下载里面所有文件，保留子目录结构") {
                    folderAction = null
                    scope.launch {
                        try {
                            busy = true
                            val paths = gh.listAllUnder(d.rel)
                            ensureDownload {
                                val n = DownloadCenter.enqueueFolder(d.rel, paths)
                                Toast.makeText(ctx, "「${d.name}」$n 个文件已加入下载队列",
                                    Toast.LENGTH_SHORT).show()
                                dlOpen = true
                            }
                        } catch (e: Exception) {
                            reportError("读取目录失败：${msgOf(e)}")
                        } finally { busy = false }
                    }
                }
                ActionRow("重命名文件夹", "整目录一次改名，单次 commit，不重传内容") {
                    folderAction = null
                    renameItem = d
                    renameText = d.name
                }
                ActionRow("详情", "看完整路径与仓库信息") {
                    folderAction = null
                    infoItem = d
                }
                ActionRow("删除整个目录", "里面所有文件一起进回收站，可整体恢复") {
                    folderAction = null
                    confirmFolderDel = d
                }
            }
        }
    }

    confirmFolderDel?.let { d: FileItem ->
        AlertDialog(
            onDismissRequest = { confirmFolderDel = null },
            title = { Text("删除目录「${d.name}」？") },
            text = { Text("会递归删除里面所有文件，并作为一条记录放进回收站，可整体恢复。操作会立即提交到仓库。") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            busy = true
                            val n = gh.deleteFolder(d.rel)
                            confirmFolderDel = null
                            DevLog.i("UI", "删除目录 /${d.rel} · $n 文件 → 仓库已更新")
                            refresh()
                            Toast.makeText(ctx, "目录已进回收站（$n 个文件）", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            reportError("删除目录失败：${msgOf(e)}")
                            busy = false
                        }
                    }
                }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Danger)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmFolderDel = null }) { Text("取消") } },
        )
    }

    infoItem?.let { f: FileItem ->
        AlertDialog(
            onDismissRequest = { infoItem = null },
            title = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text("路径：/${f.rel}", style = MaterialTheme.typography.bodySmall)
                    Text("类型：${if (f.type == "dir") "文件夹" else shortType(f.name)}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("大小：${fmtSize(f.size)}", style = MaterialTheme.typography.bodySmall)
                    Text("仓库 sha：${f.sha256.take(12)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("这个 sha 是 Git 内容指纹；保存后会变，可用来确认改动确实进了仓库。",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("path", f.rel))
                    Toast.makeText(ctx, "路径已复制", Toast.LENGTH_SHORT).show()
                    infoItem = null
                }, shape = FB.ShapePill) { Text("复制路径") }
            },
            dismissButton = { TextButton(onClick = { infoItem = null }) { Text("关闭") } },
        )
    }

    if (globalOpen) {
        ModalBottomSheet(
            onDismissRequest = { globalOpen = false },
            containerColor = FB.Surface,
            shape = FB.ShapeSheet,
        ) {
            var gq by remember { mutableStateOf("") }
            val hits = globalHits.filter { it.contains(gq, ignoreCase = true) }.take(120)
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(text = "全仓搜索", style = MaterialTheme.typography.titleLarge)
                Text(text = "一次读取整棵目录树（${globalHits.size} 个文件），按文件名找，不用一层层翻。",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = gq,
                    onValueChange = { gq = it },
                    label = { Text("输入文件名关键词") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = FB.ShapeSmall,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                if (globalBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = FB.Accent)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(hits) { path: String ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    parent = path.substringBeforeLast('/', "")
                                    query = ""
                                    searchOpen = false
                                    globalOpen = false
                                }.padding(vertical = 10.dp),
                            ) {
                                Text(text = path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(text = "/$path", style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(FB.Line))
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 底部动作面板
    if (createSheet) {
        ModalBottomSheet(
            onDismissRequest = { createSheet = false },
            containerColor = FB.Surface,
            shape = FB.ShapeSheet,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                SectionTitle("要添加什么？")
                Text(text = "新内容会直接放进当前的沙盒目录。",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                ActionRow("新建文件", "建一个空文本文件，之后可以点开编辑") {
                    createSheet = false; newFileOpen = true
                }
                ActionRow("新建目录", "把文件分门别类放好") {
                    createSheet = false; newDirOpen = true
                }
                ActionRow("从手机导入", "挑一个手机里的文件，复制进沙盒") {
                    createSheet = false; pick("import")
                }
            }
        }
    }

    actionItem?.let { f: FileItem ->
        ModalBottomSheet(
            onDismissRequest = { actionItem = null },
            containerColor = FB.Surface,
            shape = FB.ShapeSheet,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(text = f.name, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = "${fmtSize(f.size)} · ${shortType(f.name)}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                ActionRow("下载到手机", "存到系统下载 / 沙盒管理器/，下载管理页可看进度") {
                    actionItem = null
                    ensureDownload {
                        DownloadCenter.enqueue(f.rel, sizeHint = f.size)
                        Toast.makeText(ctx, "已加入下载队列：Download/${DownloadCenter.FOLDER}/${f.name}",
                            Toast.LENGTH_SHORT).show()
                        dlOpen = true
                    }
                }
                ActionRow("内嵌编辑器打开", "Markdown 高亮 / 预览 / 工具栏 / 查找替换 / 自动存草稿") {
                    actionItem = null
                    scope.launch {
                        try {
                            busy = true
                            mdText = gh.readText(f.rel)
                            mdItem = f
                        } catch (e: Exception) {
                            reportError("读取失败：${msgOf(e)}")
                        } finally { busy = false }
                    }
                }
                ActionRow("详情", "看完整路径、大小与仓库里的 sha") {
                    actionItem = null
                    infoItem = f
                }
                ActionRow("打开", "用手机上能打开这类文件的应用查看") {
                    actionItem = null
                    scope.launch {
                        try {
                            ctx.startActivity(viewIntent(ctx, gh.localFile(f.rel), f.mime))
                        } catch (e: Exception) { reportError("打开失败：${msgOf(e)}") }
                    }
                }
                ActionRow("编辑并保存", "改完点保存，内容写回沙盒") {
                    actionItem = null
                    scope.launch {
                        try {
                            editText = gh.readText(f.rel)
                            editItem = f
                        } catch (e: Exception) { reportError("读取失败：${msgOf(e)}") }
                    }
                }
                ActionRow("用别的文件覆盖", "从手机挑一个文件替换掉它") {
                    actionItem = null; pick("overwrite", f)
                }
                ActionRow("分享出去", "发到微信、文件管理器或任意位置") {
                    actionItem = null
                    scope.launch {
                        try {
                            ctx.startActivity(shareIntent(ctx, gh.localFile(f.rel)))
                        } catch (e: Exception) { reportError("分享失败：${msgOf(e)}") }
                    }
                }
                ActionRow("重命名", "只改文件名，不动内容；改完立刻提交仓库") {
                    actionItem = null; renameItem = f; renameText = f.name
                }
                ActionRow("移到回收站", "删错了还能在回收站里找回来", danger = true) {
                    actionItem = null; confirmDel = f
                }
            }
        }
    }

    // ------------------------------------------------------------ 连接设置
    if (showConnect) {
        AlertDialog(
            onDismissRequest = { showConnect = false },
            title = { SectionTitle("连接设置") },
            text = {
                Column {
                    Text(text = "地址已经替你填好了，这里只需要令牌。粘一次，以后打开就能直接看文件。",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        label = { Text("令牌") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = FB.ShapeSmall,
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { saveToken() }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent)
                ) { Text("保存并查看文件") }
            },
            dismissButton = { TextButton(onClick = { showConnect = false }) { Text("取消") } },
        )
    }

    // ------------------------------------------------------------ 编辑
    editItem?.let { target: FileItem ->
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { SectionTitle("编辑 · ${target.name}") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    shape = FB.ShapeSmall,
                    maxLines = 20,
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            gh.saveText(target.rel, editText)
                            editItem = null
                            refresh()
                        } catch (e: Exception) { reportError("保存失败：${msgOf(e)}") }
                    }
                }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent)
                ) { Text("保存到沙盒") }
            },
            dismissButton = { TextButton(onClick = { editItem = null }) { Text("取消") } },
        )
    }

    // ------------------------------------------------------------ 删除
    confirmDel?.let { target: FileItem ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { SectionTitle("把「${target.name}」移到回收站？") },
            text = { Text(text = "文件不会立刻消失，之后可以在回收站里恢复。",
                style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            gh.delete(target.rel)
                            confirmDel = null
                            refresh()
                        } catch (e: Exception) { reportError("删除失败：${msgOf(e)}") }
                    }
                }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Danger)
                ) { Text("移到回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } },
        )
    }

    // ------------------------------------------------------------ 重命名（文件 + 文件夹）
    renameItem?.let { target: FileItem ->
        val isDir = target.type == "dir"
        AlertDialog(
            onDismissRequest = { if (!renameBusy) renameItem = null },
            title = { SectionTitle(if (isDir) "重命名文件夹" else "重命名文件") },
            text = {
                Column {
                    Text(
                        text = if (isDir)
                            "整目录一次改名：走 Git Data API 单次 commit，里面所有文件路径跟着变，内容一个字节都不重传。同步脚本会识别成「删旧 + 加新」，自动回灌到沙盒持久区。"
                        else
                            "只改文件名，不动内容。改完立刻提交到私有镜像仓。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("新名称") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = FB.ShapeSmall,
                        singleLine = true,
                        enabled = !renameBusy,
                    )
                    if (isDir) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = "原名：/${target.rel}",
                            style = MaterialTheme.typography.bodySmall, color = FB.Slate)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !renameBusy,
                    onClick = {
                        val clean = renameText.trim()
                        if (clean.isEmpty() || clean == "." || clean == ".." ||
                            clean.contains('/') || clean.contains('\\')) {
                            reportError("名称不合法：不能为空，也不能带 / 或 \\")
                            return@Button
                        }
                        if (clean == target.name) {
                            renameItem = null
                            return@Button
                        }
                        scope.launch {
                            renameBusy = true
                            try {
                                val moved = gh.renameAny(target.rel, clean, isDir)
                                val oldRel = target.rel
                                val newRel = joinPath(oldRel.substringBeforeLast('/', ""), clean)
                                // 当前正停在被改名的目录里（或其子目录）时，把路径一起换掉
                                if (parent == oldRel || parent.startsWith("$oldRel/")) {
                                    parent = newRel + parent.removePrefix(oldRel)
                                }
                                renameItem = null
                                DevLog.i("UI", "重命名 /$oldRel → /$newRel · $moved 项 → 仓库已更新")
                                Toast.makeText(
                                    ctx,
                                    if (isDir) "目录已改名（$moved 个文件）" else "文件名已更新",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                refresh()
                            } catch (e: Exception) {
                                reportError("重命名失败：${msgOf(e)}")
                            } finally {
                                renameBusy = false
                            }
                        }
                    },
                    shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent),
                ) { Text(if (renameBusy) "处理中…" else "保存名字") }
            },
            dismissButton = {
                TextButton(onClick = { if (!renameBusy) renameItem = null }) { Text("取消") }
            },
        )
    }

    // ------------------------------------------------------------ 新建
    if (newFileOpen) {
        AlertDialog(
            onDismissRequest = { newFileOpen = false },
            title = { SectionTitle("新建文件") },
            text = {
                OutlinedTextField(value = newFileName, onValueChange = { newFileName = it },
                    label = { Text("文件名，例如 笔记.md") },
                    modifier = Modifier.fillMaxWidth(), shape = FB.ShapeSmall, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            gh.createText(newFileName, parent, "")
                            newFileOpen = false
                            newFileName = ""
                            refresh()
                        } catch (e: Exception) { reportError("创建失败：${msgOf(e)}") }
                    }
                }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent)
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newFileOpen = false }) { Text("取消") } },
        )
    }

    if (newDirOpen) {
        AlertDialog(
            onDismissRequest = { newDirOpen = false },
            title = { SectionTitle("新建目录") },
            text = {
                OutlinedTextField(value = newDirName, onValueChange = { newDirName = it },
                    label = { Text("目录名") },
                    modifier = Modifier.fillMaxWidth(), shape = FB.ShapeSmall, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            gh.mkdir(newDirName, parent)
                            newDirOpen = false
                            newDirName = ""
                            refresh()
                        } catch (e: Exception) { reportError("创建失败：${msgOf(e)}") }
                    }
                }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent)
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newDirOpen = false }) { Text("取消") } },
        )
    }

    // ------------------------------------------------------------ 运行日志
    if (devlogOpen) {
        AlertDialog(
            onDismissRequest = { devlogOpen = false },
            title = { SectionTitle("运行日志") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp)) {
                    Text(text = "记录这个 APP 的运行状态。出问题时导出一份发给我，就能定位是哪儿卡住了。令牌和签名会自动打码。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(14.dp))
                    val s = logSummary
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatBlock("${s?.total ?: 0} 条", "本次记录", Modifier.weight(1f))
                        StatBlock("${s?.errors ?: 0} 个", "错误", Modifier.weight(1f))
                        StatBlock("${s?.httpFailed ?: 0} 次", "请求失败", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    s?.last?.take(4)?.forEach {
                        Text(text = "· $it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (exportedPath.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(text = "已生成 ${File(exportedPath).name}",
                            style = MaterialTheme.typography.bodySmall, color = FB.Accent)
                    }
                }
            },
            confirmButton = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val f = DevLog.exportMarkdown(ctx)
                                    exportedPath = f.absolutePath
                                    logSummary = DevLog.summary()
                                    ctx.startActivity(DevLog.shareIntent(ctx, f))
                                } catch (e: Exception) { reportError("导出失败：${msgOf(e)}") }
                            }
                        }, shape = FB.ShapePill,
                            colors = ButtonDefaults.buttonColors(containerColor = FB.Accent)
                        ) { Text("导出 Markdown") }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val f = DevLog.exportMarkdown(ctx)
                                    exportedPath = f.absolutePath
                                    logSummary = DevLog.summary()
                                    saveMd.launch(f.name)
                                } catch (e: Exception) { reportError("导出失败：${msgOf(e)}") }
                            }
                        }) { Text("另存为…") }
                    }
                    Row {
                        TextButton(onClick = {
                            DevLog.clear()
                            logSummary = DevLog.summary()
                            exportedPath = ""
                        }) { Text("清空日志") }
                        TextButton(onClick = { devlogOpen = false }) { Text("关闭") }
                    }
                }
            },
        )
    }

    // ------------------------------------------------------------ 回收站
    if (trashOpen) {
        AlertDialog(
            onDismissRequest = { trashOpen = false },
            title = { SectionTitle("回收站") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 320.dp)) {
                    if (trashItems.isEmpty()) {
                        Text(text = "这里还是空的。删掉的文件会先放在这儿。",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    trashItems.forEach { t: TrashItem ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(text = t.name, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        gh.restore(t.id)
                                        trashItems = gh.trash()
                                        refresh()
                                    } catch (e: Exception) { reportError("恢复失败：${msgOf(e)}") }
                                }
                            }) { Text("恢复") }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        gh.purge(t.id)
                                        trashItems = gh.trash()
                                    } catch (e: Exception) { reportError("删除失败：${msgOf(e)}") }
                                }
                            }) { Text("彻底删", color = FB.Danger) }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(FB.Line))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { trashOpen = false }) { Text("关闭") } },
        )
    }
}
