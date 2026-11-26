package com.example.filebridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

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

private const val GH_OWNER = "zjh1934"
private const val GH_REPO = "filebridge-android"
private const val GH_ROOT = "sandbox-files"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("filebridge", Context.MODE_PRIVATE) }
    // 默认就是「沙盒文件」，不是手机本地文件
    var mode by remember { mutableStateOf(prefs.getString("mode", "sandbox") ?: "sandbox") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var base by remember { mutableStateOf(prefs.getString("base", "") ?: "") }
    var showCfg by remember { mutableStateOf(false) }
    var modeDialog by remember { mutableStateOf(false) }
    var parent by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<FileItem?>(null) }
    var editText by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf<FileItem?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newDirOpen by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }
    var newFileOpen by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var trashOpen by remember { mutableStateOf(false) }
    var trashItems by remember { mutableStateOf<List<TrashItem>>(emptyList()) }
    var devlogOpen by remember { mutableStateOf(false) }
    var logSummary by remember { mutableStateOf<DevLog.Summary?>(null) }
    var exportedPath by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, FileItem?>>("import" to null) }

    val local = remember { LocalStore(ctx) }
    val gh = remember(token) { GitHubStore(ctx, GH_OWNER, GH_REPO, token, GH_ROOT) }
    val repo = remember(base, token) {
        FileRepository(ctx, ApiClient.create(base.ifBlank { "http://127.0.0.1:8765" }), token)
    }
    val scope = rememberCoroutineScope()

    fun reportError(msg: String) {
        error = msg
        DevLog.e("UI", msg)
    }

    // ---------- 三种后端统一分派 ----------
    suspend fun doList(p: String): ListResp = when (mode) {
        "sandbox" -> gh.list(p)
        "local" -> local.list(p)
        else -> if (base.isBlank()) ListResp(parent = p) else repo.list(p)
    }

    suspend fun doRead(f: FileItem): String = when (mode) {
        "sandbox" -> gh.readText(f.rel)
        "local" -> local.readText(f.rel)
        else -> repo.readText(f)
    }

    suspend fun doSave(f: FileItem, text: String) = when (mode) {
        "sandbox" -> gh.saveText(f.rel, text)
        "local" -> local.saveText(f.rel, text)
        else -> repo.saveText(f, text)
    }

    suspend fun doMkdir(name: String, p: String) = when (mode) {
        "sandbox" -> gh.mkdir(name, p)
        "local" -> local.mkdir(name, p)
        else -> repo.mkdir(name, p)
    }

    suspend fun doCreate(name: String, p: String) = when (mode) {
        "sandbox" -> gh.createText(name, p, "")
        "local" -> local.createText(name, p, "")
        else -> error("云网关模式请用「上传」新建文件")
    }

    suspend fun doRename(f: FileItem, name: String) = when (mode) {
        "sandbox" -> gh.rename(f.rel, name)
        "local" -> local.rename(f.rel, name)
        else -> repo.rename(f.id, name)
    }

    suspend fun doDelete(f: FileItem) = when (mode) {
        "sandbox" -> gh.delete(f.rel)
        "local" -> local.delete(f.rel)
        else -> repo.delete(f.id)
    }

    suspend fun doTrash(): List<TrashItem> = when (mode) {
        "sandbox" -> gh.trash()
        "local" -> local.trash()
        else -> repo.trash()
    }

    suspend fun doRestore(id: String) = when (mode) {
        "sandbox" -> gh.restore(id)
        "local" -> local.restore(id)
        else -> repo.restore(id)
    }

    suspend fun doPurge(id: String) = when (mode) {
        "sandbox" -> gh.purge(id)
        "local" -> local.purge(id)
        else -> repo.purgeTrash(id)
    }

    suspend fun doLocalFile(f: FileItem): File = when (mode) {
        "sandbox" -> gh.localFile(f.rel)
        "local" -> local.fileOfRel(f.rel)
        else -> repo.localFile(f)
    }

    suspend fun doImport(uri: Uri, name: String, action: String, item: FileItem?) {
        when (mode) {
            "sandbox" -> {
                if (action == "overwrite" && item != null) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("读不到所选文件")
                    gh.put(item.rel, bytes, "FileBridge: 覆盖 ${item.name}")
                } else gh.importFrom(uri, name, parent)
            }
            "local" -> {
                if (action == "overwrite" && item != null) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("读不到所选文件")
                    local.saveText(item.rel, String(bytes, Charsets.UTF_8))
                } else local.importFrom(uri, name, parent)
            }
            else -> {
                if (action == "overwrite" && item != null) repo.overwrite(uri, item)
                else repo.upload(uri, name, parent)
            }
        }
    }

    fun viewOf(f: File, mime: String): Intent = local.viewIntent(f, mime)
    fun shareOf(f: File): Intent = local.shareIntent(f)

    suspend fun refresh() {
        try {
            busy = true
            if (mode == "sandbox" && token.isBlank()) {
                dirs = emptyList()
                files = emptyList()
                error = "点顶栏「设置」把令牌粘一次，就能看到沙盒里的文件了。粘一次以后都不用再管。"
                busy = false
                return
            }
            if (mode == "gateway" && base.isBlank()) {
                dirs = emptyList()
                files = emptyList()
                error = "还没填网关地址。不会配就点顶栏「模式」切回「沙盒文件」。"
                busy = false
                return
            }
            val r = doList(parent)
            dirs = r.dirs
            files = r.files
            error = ""
            DevLog.i("LIST", "[$mode] /$parent · ${dirs.size} 目录 · ${files.size} 文件")
        } catch (e: Exception) {
            reportError("加载失败：${e.message}")
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        DevLog.init(ctx)
        DevLog.i("APP", "MainActivity 创建 · 默认模式=$mode · 目标：查看/编辑沙盒文件")
    }
    DisposableEffect(Unit) {
        onDispose { DevLog.i("APP", "界面销毁 / 退到后台") }
    }
    LaunchedEffect(mode, parent, base, token) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            DevLog.w("FILE", "用户取消了选择")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                busy = true
                val name = queryName(ctx, uri)
                val (action, item) = pending
                DevLog.i("UI", "选择 $name · 动作=$action · 模式=$mode")
                doImport(uri, name, action, item)
                refresh()
            } catch (e: Exception) {
                reportError("导入失败：${e.message}")
                busy = false
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

    fun up() {
        parent = parent.split("/").filter { it.isNotEmpty() }.dropLast(1).joinToString("/")
        DevLog.i("NAV", "返回上级 /$parent")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (mode) {
                            "sandbox" -> "FileBridge · 沙盒 /$parent"
                            "local" -> "FileBridge · 本机 /$parent"
                            else -> "FileBridge · 网关 /$parent"
                        }
                    )
                },
                actions = {
                    TextButton(onClick = {
                        logSummary = DevLog.summary()
                        devlogOpen = true
                    }) { Text("日志") }
                    TextButton(onClick = { modeDialog = true }) { Text("模式") }
                    TextButton(onClick = { showCfg = !showCfg }) { Text("设置") }
                    TextButton(onClick = { scope.launch { refresh() } }) { Text("刷新") }
                },
            )
        },
    ) { pad ->
        Column(modifier = Modifier.padding(pad).padding(12.dp)) {
            if (showCfg) {
                Text(
                    text = "连接的是仓库 $GH_OWNER/$GH_REPO 里的 $GH_ROOT/ 目录，里面就是沙盒的文件。" +
                        "地址已经替你填好了，你只需要把令牌粘在下面，粘一次即可。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("把令牌粘这里（只粘一次）") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("云网关地址（高级，可留空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        prefs.edit().putString("token", token).putString("base", base).apply()
                        showCfg = false
                        DevLog.i("CFG", "保存配置 · Token=[REDACTED] · 网关=${DevLog.sanitize(base)}")
                        scope.launch { refresh() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存，然后就能看到沙盒文件") }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { up() }, modifier = Modifier.weight(1f)) { Text("↑ 上级") }
                Button(onClick = { pick("import") }, modifier = Modifier.weight(1f)) {
                    Text(text = if (mode == "sandbox") "导入到沙盒" else "导入文件")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode != "gateway") {
                    Button(onClick = { newFileOpen = true }, modifier = Modifier.weight(1f)) { Text("新建文件") }
                }
                Button(onClick = { newDirOpen = true }, modifier = Modifier.weight(1f)) { Text("新建目录") }
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                trashItems = doTrash()
                                trashOpen = true
                            } catch (e: Exception) {
                                reportError("回收站读取失败：${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("回收站") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        logSummary = DevLog.summary()
                        devlogOpen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导出开发者日志") }
            }

            Text(
                text = when (mode) {
                    "sandbox" -> "沙盒文件 · 走 GitHub 仓库中转，不用电脑、路由器或 IP"
                    "local" -> "本机文件 · 手机本地（备用）"
                    else -> "云网关 · 需要常开服务器（高级）"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            if (error.isNotEmpty()) {
                Text(text = error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(dirs) { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            parent = joinPath(parent, d.name)
                            DevLog.i("NAV", "进入 /$parent")
                        },
                    ) {
                        Text(text = "📁  ${d.name}", modifier = Modifier.padding(14.dp))
                    }
                }
                items(files) { f ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = f.name, style = MaterialTheme.typography.titleMedium)
                            Text(text = "${f.size} B · ${f.mime}", style = MaterialTheme.typography.bodySmall)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            ctx.startActivity(viewOf(doLocalFile(f), f.mime))
                                        } catch (e: Exception) {
                                            reportError("打开失败：${e.message}")
                                        }
                                    }
                                }) { Text("打开") }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            editText = doRead(f)
                                            editItem = f
                                        } catch (e: Exception) {
                                            reportError("读取失败：${e.message}")
                                        }
                                    }
                                }) { Text("编辑") }
                                TextButton(onClick = { pick("overwrite", f) }) { Text("覆盖") }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            ctx.startActivity(shareOf(doLocalFile(f)))
                                        } catch (e: Exception) {
                                            reportError("分享失败：${e.message}")
                                        }
                                    }
                                }) { Text("分享") }
                                TextButton(onClick = { renameItem = f; renameText = f.name }) { Text("改名") }
                                TextButton(onClick = { confirmDel = f }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- 模式切换 ----------------
    if (modeDialog) {
        AlertDialog(
            onDismissRequest = { modeDialog = false },
            title = { Text("看哪种文件？") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("· 沙盒文件（默认，你要的）：查看和编辑沙盒里的文件，通过 GitHub 仓库中转。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("· 本机文件：手机本地文件，备用。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("· 云网关：需要常开电脑/服务器，高级。")
                }
            },
            confirmButton = {
                Column {
                    Button(
                        onClick = {
                            mode = "sandbox"
                            prefs.edit().putString("mode", "sandbox").apply()
                            parent = ""
                            modeDialog = false
                            DevLog.i("CFG", "切换到沙盒文件模式")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("沙盒文件（推荐）") }
                    Button(
                        onClick = {
                            mode = "local"
                            prefs.edit().putString("mode", "local").apply()
                            parent = ""
                            modeDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("本机文件（备用）") }
                    Button(
                        onClick = {
                            mode = "gateway"
                            prefs.edit().putString("mode", "gateway").apply()
                            parent = ""
                            modeDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("云网关（高级）") }
                    TextButton(onClick = { modeDialog = false }) { Text("取消") }
                }
            },
        )
    }

    // ---------------- 开发者日志 ----------------
    if (devlogOpen) {
        AlertDialog(
            onDismissRequest = { devlogOpen = false },
            title = { Text("开发者日志") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 340.dp)) {
                    Text(
                        text = "记录运行详细状态：生命周期、目录切换、每次 GitHub 请求的状态码与耗时、文件读写、异常堆栈、设备信息。导出内容自动打码令牌与签名参数。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val s = logSummary
                    if (s != null) {
                        Text(text = "本次 ${s.total} 条 · ERROR ${s.errors} · WARN ${s.warns}")
                        Text(text = "HTTP ${s.httpCount} 次 · 失败 ${s.httpFailed} · 平均 ${s.avgMs} ms")
                        Spacer(modifier = Modifier.height(6.dp))
                        s.last.forEach { Text(text = "· $it", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text(text = "暂无统计")
                    }
                    if (exportedPath.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "已生成：${File(exportedPath).name}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Column {
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val f = DevLog.exportMarkdown(ctx)
                                    exportedPath = f.absolutePath
                                    logSummary = DevLog.summary()
                                    ctx.startActivity(DevLog.shareIntent(ctx, f))
                                } catch (e: Exception) {
                                    reportError("导出失败：${e.message}")
                                }
                            }
                        }) { Text("一键导出 MD") }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val f = DevLog.exportMarkdown(ctx)
                                    exportedPath = f.absolutePath
                                    logSummary = DevLog.summary()
                                    saveMd.launch(f.name)
                                } catch (e: Exception) {
                                    reportError("导出失败：${e.message}")
                                }
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

    // ---------------- 编辑 ----------------
    editItem?.let { target ->
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text("编辑 ${target.name}") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    maxLines = 20,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            doSave(target, editText)
                            editItem = null
                            refresh()
                        } catch (e: Exception) {
                            reportError("保存失败：${e.message}")
                        }
                    }
                }) { Text("保存到沙盒") }
            },
            dismissButton = { TextButton(onClick = { editItem = null }) { Text("取消") } },
        )
    }

    // ---------------- 删除 ----------------
    confirmDel?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("删除 ${target.name}？") },
            text = { Text("默认移入回收站，可在回收站里恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            doDelete(target)
                            confirmDel = null
                            refresh()
                        } catch (e: Exception) {
                            reportError("删除失败：${e.message}")
                        }
                    }
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } },
        )
    }

    // ---------------- 改名 ----------------
    renameItem?.let { target ->
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            doRename(target, renameText)
                            renameItem = null
                            refresh()
                        } catch (e: Exception) {
                            reportError("改名失败：${e.message}")
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameItem = null }) { Text("取消") } },
        )
    }

    // ---------------- 新建 ----------------
    if (newFileOpen) {
        AlertDialog(
            onDismissRequest = { newFileOpen = false },
            title = { Text("新建沙盒文件") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("文件名，如 笔记.md") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            doCreate(newFileName, parent)
                            newFileOpen = false
                            newFileName = ""
                            refresh()
                        } catch (e: Exception) {
                            reportError("创建失败：${e.message}")
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newFileOpen = false }) { Text("取消") } },
        )
    }

    if (newDirOpen) {
        AlertDialog(
            onDismissRequest = { newDirOpen = false },
            title = { Text("新建目录") },
            text = { OutlinedTextField(value = newDirName, onValueChange = { newDirName = it }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            doMkdir(newDirName, parent)
                            newDirOpen = false
                            newDirName = ""
                            refresh()
                        } catch (e: Exception) {
                            reportError("创建失败：${e.message}")
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newDirOpen = false }) { Text("取消") } },
        )
    }

    // ---------------- 回收站 ----------------
    if (trashOpen) {
        AlertDialog(
            onDismissRequest = { trashOpen = false },
            title = { Text("回收站") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 320.dp)) {
                    if (trashItems.isEmpty()) Text(text = "回收站是空的")
                    trashItems.forEach { t ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.weight(1f)) { Text(text = t.name) }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        doRestore(t.id)
                                        trashItems = doTrash()
                                        refresh()
                                    } catch (e: Exception) {
                                        reportError("恢复失败：${e.message}")
                                    }
                                }
                            }) { Text("恢复") }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        doPurge(t.id)
                                        trashItems = doTrash()
                                    } catch (e: Exception) {
                                        reportError("删除失败：${e.message}")
                                    }
                                }
                            }) { Text("彻底删") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { trashOpen = false }) { Text("关闭") } },
        )
    }
}
