package com.example.filebridge

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
private const val GH_REPO = "filebridge-android"
private const val GH_ROOT = "sandbox-files"

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

private fun friendlyError(e: Exception): String {
    val m = e.message.orEmpty()
    return when {
        m.contains("令牌无效") || m.contains("401") -> "令牌已经失效了，重新粘一次就能继续用。"
        m.contains("failed to connect") || m.contains("Unable to resolve") -> "手机现在连不上 GitHub，检查一下网络再试。"
        else -> "没能读到沙盒文件：$m"
    }
}

// ---------------------------------------------------------------- 可复用组件
@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun StatBlock(value: String, label: String) {
    Surface(shape = FB.ShapeCard, color = FB.AccentSoft, modifier = Modifier.weight(1f)) {
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
private fun FileCard(f: FileItem, onAction: () -> Unit) {
    val (tag, color) = badge(f.name)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = FB.ShapeCard,
        colors = CardDefaults.cardColors(containerColor = FB.Surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Surface(
                shape = FB.ShapeSmall,
                color = FB.AccentSoft,
                modifier = Modifier.clip(FB.ShapeSmall).clickable(onClick = onAction),
            ) {
                Text(text = "操作", fontSize = 12.sp, color = FB.Accent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun FolderRow(d: FileItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(FB.ShapeSmall).clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(FB.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "›", fontSize = 21.sp, color = FB.Accent)
        }
        Spacer(Modifier.width(14.dp))
        Text(text = d.name, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = "进入", style = MaterialTheme.typography.bodySmall)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(FB.Line))
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

    val gh = remember(token) { GitHubStore(ctx, GH_OWNER, GH_REPO, token, GH_ROOT) }
    val scope = rememberCoroutineScope()

    fun reportError(msg: String) {
        error = msg
        DevLog.e("UI", msg)
    }

    suspend fun refresh() {
        try {
            busy = true
            if (token.isBlank()) {
                dirs = emptyList()
                files = emptyList()
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

    LaunchedEffect(Unit) {
        DevLog.init(ctx)
        DevLog.i("APP", "界面启动 · v1.3 设计系统版 · 目标是查看和编辑沙盒文件")
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
                    reportError("导入失败：${e.message}")
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
                reportError("另存为失败：${e.message}")
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

    val segments = parent.split("/").filter { it.isNotEmpty() }

    // ------------------------------------------------------------ 页面骨架
    androidx.compose.material3.Scaffold(
        containerColor = FB.Paper,
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
                        Text(text = "沙盒文件", style = MaterialTheme.typography.displaySmall)
                        Text(text = "在手机上查看和编辑云端沙盒里的文件",
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
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (token.isBlank())
                        "还没连上仓库，粘一次令牌就能看到文件。"
                    else
                        "连的是 $GH_OWNER/$GH_REPO 的 $GH_ROOT 目录，这个目录里有 ${dirs.size + files.size} 项。",
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
                                    text = "把令牌粘在下面，点一次保存。之后每次打开都能直接看文件，不用再填。",
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
                            onClick = {
                                scope.launch {
                                    try {
                                        trashItems = gh.trash()
                                        trashOpen = true
                                    } catch (e: Exception) {
                                        reportError("回收站读取失败：${e.message}")
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

                items(dirs) { d -> FolderRow(d) { parent = joinPath(parent, d.name) } }
                items(files) { f -> FileCard(f) { actionItem = f } }

                if (token.isNotBlank() && error.isEmpty() && dirs.isEmpty() && files.isEmpty() && !busy) {
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

                item { Spacer(Modifier.height(88.dp)) }   // 给悬浮按钮留位置
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

    actionItem?.let { f ->
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
                ActionRow("打开", "用手机上能打开这类文件的应用查看") {
                    actionItem = null
                    scope.launch {
                        try {
                            ctx.startActivity(viewIntent(ctx, gh.localFile(f.rel), f.mime))
                        } catch (e: Exception) { reportError("打开失败：${e.message}") }
                    }
                }
                ActionRow("编辑并保存", "改完点保存，内容写回沙盒") {
                    actionItem = null
                    scope.launch {
                        try {
                            editText = gh.readText(f.rel)
                            editItem = f
                        } catch (e: Exception) { reportError("读取失败：${e.message}") }
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
                        } catch (e: Exception) { reportError("分享失败：${e.message}") }
                    }
                }
                ActionRow("改名", "只改名字，不动内容") {
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
    editItem?.let { target ->
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
                        } catch (e: Exception) { reportError("保存失败：${e.message}") }
                    }
                }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent)
                ) { Text("保存到沙盒") }
            },
            dismissButton = { TextButton(onClick = { editItem = null }) { Text("取消") } },
        )
    }

    // ------------------------------------------------------------ 删除
    confirmDel?.let { target ->
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
                        } catch (e: Exception) { reportError("删除失败：${e.message}") }
                    }
                }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Danger)
                ) { Text("移到回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } },
        )
    }

    // ------------------------------------------------------------ 改名
    renameItem?.let { target ->
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { SectionTitle("改成什么名字？") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(), shape = FB.ShapeSmall, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            gh.rename(target.rel, renameText)
                            renameItem = null
                            refresh()
                        } catch (e: Exception) { reportError("改名失败：${e.message}") }
                    }
                }, shape = FB.ShapePill,
                    colors = ButtonDefaults.buttonColors(containerColor = FB.Accent)
                ) { Text("保存名字") }
            },
            dismissButton = { TextButton(onClick = { renameItem = null }) { Text("取消") } },
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
                        } catch (e: Exception) { reportError("创建失败：${e.message}") }
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
                        } catch (e: Exception) { reportError("创建失败：${e.message}") }
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
                        StatBlock("${s?.total ?: 0} 条", "本次记录")
                        StatBlock("${s?.errors ?: 0} 个", "错误")
                        StatBlock("${s?.httpFailed ?: 0} 次", "请求失败")
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
                                } catch (e: Exception) { reportError("导出失败：${e.message}") }
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
                                } catch (e: Exception) { reportError("导出失败：${e.message}") }
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
                    trashItems.forEach { t ->
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
                                    } catch (e: Exception) { reportError("恢复失败：${e.message}") }
                                }
                            }) { Text("恢复") }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        gh.purge(t.id)
                                        trashItems = gh.trash()
                                    } catch (e: Exception) { reportError("删除失败：${e.message}") }
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
