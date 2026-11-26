package com.example.filebridge

import android.content.Context
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("filebridge", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "local") ?: "local") }
    var base by remember { mutableStateOf(prefs.getString("base", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
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
    val repo = remember(base, token) {
        val url = base.ifBlank { "http://127.0.0.1:8765" }
        FileRepository(ctx, ApiClient.create(url), token)
    }
    val scope = rememberCoroutineScope()

    fun reportError(msg: String) {
        error = msg
        DevLog.e("UI", msg)
    }

    suspend fun refresh() {
        try {
            busy = true
            if (mode == "local") {
                val r = local.list(parent)
                dirs = r.dirs
                files = r.files
            } else {
                if (base.isBlank()) {
                    dirs = emptyList()
                    files = emptyList()
                    error = "还没填网关地址。点「设置」填，或切回「本机文件」直接用。"
                    busy = false
                    return
                }
                val r = repo.list(parent)
                dirs = r.dirs
                files = r.files
            }
            if (error.isNotEmpty() && !error.startsWith("还没填")) error = ""
            DevLog.i("LIST", "[$mode] /$parent · ${dirs.size} 目录 · ${files.size} 文件")
        } catch (e: Exception) {
            reportError("刷新失败：${e.message}")
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        DevLog.init(ctx)
        DevLog.i("APP", "MainActivity 创建 · 默认模式=$mode · 零配置本机文件已就绪")
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
                if (mode == "local") {
                    if (action == "overwrite" && item != null) {
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("读不到所选文件")
                        local.saveText(item.rel, String(bytes, Charsets.UTF_8))
                    } else {
                        local.importFrom(uri, name, parent)
                    }
                } else {
                    if (action == "overwrite" && item != null) repo.overwrite(uri, item)
                    else repo.upload(uri, name, parent)
                }
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
                title = { Text(if (mode == "local") "FileBridge · 本机 /$parent" else "FileBridge · 网关 /$parent") },
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
                    text = if (mode == "local")
                        "本机模式不需要任何地址、IP、路由器或 Token，下面只给「云网关」备用。"
                    else
                        "云网关是可选高级功能：需要一台常开电脑/云主机跑 server/。不会配就切回「本机文件」。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("网关地址（可留空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("API Token（可留空）") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        prefs.edit().putString("base", base).putString("token", token).apply()
                        showCfg = false
                        DevLog.i("CFG", "保存配置 · 网关=${DevLog.sanitize(base)} · Token=[REDACTED]")
                        scope.launch { refresh() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存") }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { up() }, modifier = Modifier.weight(1f)) { Text("↑ 上级") }
                Button(onClick = { pick("import") }, modifier = Modifier.weight(1f)) {
                    Text(text = if (mode == "local") "导入文件" else "上传")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == "local") {
                    Button(onClick = { newFileOpen = true }, modifier = Modifier.weight(1f)) { Text("新建文件") }
                }
                Button(onClick = { newDirOpen = true }, modifier = Modifier.weight(1f)) { Text("新建目录") }
                Button(onClick = {
                    scope.launch {
                        try {
                            trashItems = if (mode == "local") local.trash() else repo.trash()
                            trashOpen = true
                        } catch (e: Exception) {
                            reportError("回收站读取失败：${e.message}")
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Text("回收站") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    logSummary = DevLog.summary()
                    devlogOpen = true
                }, modifier = Modifier.fillMaxWidth()) { Text("导出开发者日志") }
            }

            Text(
                text = if (mode == "local")
                    "打开即用 · 文件存在手机里 · 不需要网络、路由器或电脑"
                else
                    "云网关模式 · 连不上就切回「本机文件」",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            if (error.isNotEmpty()) {
                Text(text = error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(dirs) { d ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        parent = joinPath(parent, d.name)
                        DevLog.i("NAV", "进入 /$parent")
                    }) {
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
                                            if (mode == "local") {
                                                ctx.startActivity(local.viewIntent(local.fileOfRel(f.rel), f.mime))
                                            } else {
                                                ctx.startActivity(repo.viewIntent(repo.localFile(f), f.mime))
                                            }
                                        } catch (e: Exception) {
                                            reportError("打开失败：${e.message}")
                                        }
                                    }
                                }) { Text("打开") }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            editText = if (mode == "local") local.readText(f.rel) else repo.readText(f)
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
                                            if (mode == "local") {
                                                ctx.startActivity(local.shareIntent(local.fileOfRel(f.rel)))
                                            } else {
                                                ctx.startActivity(local.shareIntent(repo.localFile(f)))
                                            }
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
            title = { Text("选择模式") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("· 本机文件（推荐）：打开就能用，不需要网络、路由器、电脑，文件存在手机里。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("· 云网关：可选高级功能，需要一台常开的电脑或云主机跑服务端。不会配就别选。")
                }
            },
            confirmButton = {
                Column {
                    Button(onClick = {
                        mode = "local"
                        prefs.edit().putString("mode", "local").apply()
                        parent = ""
                        modeDialog = false
                        DevLog.i("CFG", "切换到本机文件模式")
                    }, modifier = Modifier.fillMaxWidth()) { Text("本机文件（推荐）") }
                    Button(onClick = {
                        mode = "gateway"
                        prefs.edit().putString("mode", "gateway").apply()
                        parent = ""
                        modeDialog = false
                        DevLog.i("CFG", "切换到云网关模式")
                    }, modifier = Modifier.fillMaxWidth()) { Text("云网关（高级）") }
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
                        text = "记录运行详细状态：生命周期、目录切换、每个接口的耗时与状态码、文件操作、异常堆栈、设备信息。导出内容自动打码 Token 与签名参数。",
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
                            if (mode == "local") local.saveText(target.rel, editText)
                            else repo.saveText(target, editText)
                            editItem = null
                            refresh()
                        } catch (e: Exception) {
                            reportError("保存失败：${e.message}")
                        }
                    }
                }) { Text("保存") }
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
                            if (mode == "local") local.delete(target.rel) else repo.delete(target.id)
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
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            if (mode == "local") local.rename(target.rel, renameText)
                            else repo.rename(target.id, renameText)
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

    // ---------------- 新建文件 / 目录 ----------------
    if (newFileOpen) {
        AlertDialog(
            onDismissRequest = { newFileOpen = false },
            title = { Text("新建文本文件") },
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
                            local.createText(newFileName, parent, "")
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
            text = {
                OutlinedTextField(value = newDirName, onValueChange = { newDirName = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            if (mode == "local") local.mkdir(newDirName, parent)
                            else repo.mkdir(newDirName, parent)
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
                                        if (mode == "local") local.restore(t.id) else repo.restore(t.id)
                                        trashItems = if (mode == "local") local.trash() else repo.trash()
                                        refresh()
                                    } catch (e: Exception) {
                                        reportError("恢复失败：${e.message}")
                                    }
                                }
                            }) { Text("恢复") }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        if (mode == "local") local.purge(t.id) else repo.purgeTrash(t.id)
                                        trashItems = if (mode == "local") local.trash() else repo.trash()
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
