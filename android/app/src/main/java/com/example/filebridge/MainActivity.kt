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
    var name = "upload_${System.currentTimeMillis()}"
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
    var base by remember { mutableStateOf(prefs.getString("base", "http://10.0.2.2:8765")!!) }
    var token by remember { mutableStateOf(prefs.getString("token", "demo-token-change-me")!!) }
    var showCfg by remember { mutableStateOf(false) }
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
    var trashOpen by remember { mutableStateOf(false) }
    var trashItems by remember { mutableStateOf<List<TrashItem>>(emptyList()) }
    var devlogOpen by remember { mutableStateOf(false) }
    var logSummary by remember { mutableStateOf<DevLog.Summary?>(null) }
    var exportedPath by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, FileItem?>>("upload" to null) }

    val repo = remember(base, token) { FileRepository(ctx, ApiClient.create(base), token) }
    val scope = rememberCoroutineScope()

    fun reportError(msg: String) {
        error = msg
        DevLog.e("UI", msg)
    }

    suspend fun refresh() {
        try {
            busy = true
            val r = repo.list(parent)
            dirs = r.dirs
            files = r.files
            error = ""
            DevLog.i("LIST", "目录 /$parent · ${r.dirs.size} 个子目录 · ${r.files.size} 个文件")
        } catch (e: Exception) {
            reportError("刷新失败：${e.message}")
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        DevLog.init(ctx)
        DevLog.i("APP", "MainActivity 创建 · 界面启动")
        DevLog.i("CFG", "服务地址=${DevLog.sanitize(base)} · Token=[REDACTED]")
    }
    DisposableEffect(Unit) {
        onDispose { DevLog.i("APP", "界面销毁 / 退到后台") }
    }
    LaunchedEffect(repo, parent) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            DevLog.w("FILE", "用户取消了文件选择")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                busy = true
                val name = queryName(ctx, uri)
                val (action, item) = pending
                DevLog.i("UI", "选择文件 $name · 动作=$action · 目录=/$parent")
                if (action == "overwrite" && item != null) repo.overwrite(uri, item)
                else repo.upload(uri, name, parent)
                refresh()
            } catch (e: Exception) {
                reportError("上传失败：${e.message}")
                busy = false
            }
        }
    }

    /** SAF：把已生成的 MD 另存到用户选的位置 */
    val saveMd = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri: Uri? ->
        if (uri == null) {
            DevLog.w("LOG", "用户取消了另存为")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val src = File(exportedPath)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    src.inputStream().use { it.copyTo(os) }
                } ?: error("无法打开输出流")
                DevLog.i("LOG", "开发者日志另存为成功 · ${DevLog.sanitize(uri.toString())}")
            } catch (e: Exception) {
                reportError("另存为失败：${e.message}")
            }
        }
    }

    fun pick(action: String, item: FileItem? = null) {
        pending = action to item
        DevLog.d("UI", "打开文件选择器 · 动作=$action")
        picker.launch("*/*")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FileBridge · /$parent") },
                actions = {
                    TextButton(onClick = {
                        logSummary = repo.devLogSummary()
                        devlogOpen = true
                        DevLog.i("UI", "打开开发者日志面板")
                    }) { Text("日志") }
                    TextButton(onClick = { showCfg = !showCfg }) { Text("设置") }
                    TextButton(onClick = {
                        DevLog.i("UI", "手动刷新")
                        scope.launch { refresh() }
                    }) { Text("刷新") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            if (showCfg) {
                OutlinedTextField(
                    value = base, onValueChange = { base = it },
                    label = { Text("服务地址 http(s)://host:port") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("API Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        prefs.edit().putString("base", base).putString("token", token).apply()
                        showCfg = false
                        DevLog.i("CFG", "保存连接配置 · 服务地址=${DevLog.sanitize(base)} · Token=[REDACTED]")
                        scope.launch {
                            try { repo.health(); refresh() }
                            catch (e: Exception) { reportError("连接失败：${e.message}") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存并连接") }
                Spacer(Modifier.height(8.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        parent = parent.split("/").filter { it.isNotEmpty() }.dropLast(1).joinToString("/")
                        DevLog.i("NAV", "返回上级 -> /$parent")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("↑ 上级") }
                Button(onClick = { pick("upload") }, modifier = Modifier.weight(1f)) { Text("上传") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { newDirOpen = true }, modifier = Modifier.weight(1f)) { Text("新建目录") }
                Button(
                    onClick = {
                        scope.launch {
                            try { trashItems = repo.trash(); trashOpen = true }
                            catch (e: Exception) { reportError("回收站读取失败：${e.message}") }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("回收站") }
                Button(
                    onClick = {
                        logSummary = repo.devLogSummary()
                        devlogOpen = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("导出日志") }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(dirs) { d ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            parent = joinPath(parent, d.name)
                            DevLog.i("NAV", "进入目录 /$parent")
                        },
                    ) {
                        Text("📁  ${d.name}", Modifier.padding(14.dp))
                    }
                }
                items(files) { f ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(f.name, style = MaterialTheme.typography.titleMedium)
                            Text("${f.size} B · ${f.mime}", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            val local = repo.localFile(f)
                                            ctx.startActivity(repo.viewIntent(local, f.mime))
                                        } catch (e: Exception) { reportError("打开失败：${e.message}") }
                                    }
                                }) { Text("打开") }
                                TextButton(onClick = {
                                    scope.launch {
                                        try { editText = repo.readText(f); editItem = f }
                                        catch (e: Exception) { reportError("读取失败：${e.message}") }
                                    }
                                }) { Text("编辑") }
                                TextButton(onClick = { pick("overwrite", f) }) { Text("覆盖") }
                                TextButton(onClick = { renameItem = f; renameText = f.name }) { Text("改名") }
                                TextButton(onClick = { confirmDel = f }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 开发者日志面板 ----
    if (devlogOpen) {
        AlertDialog(
            onDismissRequest = { devlogOpen = false },
            title = { Text("开发者日志") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 340.dp)) {
                    val s = logSummary
                    Text(
                        "记录 APP 运行详细状态：生命周期、目录切换、每个接口的耗时与状态码、" +
                            "文件操作、异常堆栈、设备信息。导出内容自动打码 Token 与预签名参数。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (s != null) {
                        Text("本次会话 ${s.total} 条 · ERROR ${s.errors} · WARN ${s.warns}")
                        Text("HTTP ${s.httpCount} 次 · 失败 ${s.httpFailed} · 平均 ${s.avgMs} ms")
                        Spacer(Modifier.height(6.dp))
                        s.last.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text("暂无统计")
                    }
                    if (exportedPath.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("已生成：${File(exportedPath).name}", style = MaterialTheme.typography.bodySmall)
                        Text(exportedPath, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Column {
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val f = repo.exportDevLog()
                                    exportedPath = f.absolutePath
                                    logSummary = repo.devLogSummary()
                                    ctx.startActivity(repo.devLogShareIntent(f))
                                } catch (e: Exception) { reportError("导出失败：${e.message}") }
                            }
                        }) { Text("一键导出 MD") }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val f = repo.exportDevLog()
                                    exportedPath = f.absolutePath
                                    logSummary = repo.devLogSummary()
                                    saveMd.launch(f.name)
                                } catch (e: Exception) { reportError("导出失败：${e.message}") }
                            }
                        }) { Text("另存为…") }
                    }
                    Row {
                        TextButton(onClick = {
                            repo.clearDevLog()
                            logSummary = repo.devLogSummary()
                            exportedPath = ""
                        }) { Text("清空日志") }
                        TextButton(onClick = { devlogOpen = false }) { Text("关闭") }
                    }
                }
            },
        )
    }

    // ---- 其它弹窗 ----
    editItem?.let { target ->
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text("编辑 ${target.name}") },
            text = {
                OutlinedTextField(
                    value = editText, onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), maxLines = 20,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { repo.saveText(target, editText); editItem = null; refresh() }
                        catch (e: Exception) { reportError("保存失败（版本冲突请刷新）：${e.message}") }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editItem = null }) { Text("取消") } },
        )
    }

    confirmDel?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("删除 ${target.name}？") },
            text = { Text("默认移入回收站，可在回收站里恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { repo.delete(target.id); confirmDel = null; refresh() }
                        catch (e: Exception) { reportError("删除失败：${e.message}") }
                    }
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } },
        )
    }

    renameItem?.let { target ->
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { repo.rename(target.id, renameText); renameItem = null; refresh() }
                        catch (e: Exception) { reportError("改名失败：${e.message}") }
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameItem = null }) { Text("取消") } },
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
                        try { repo.mkdir(newDirName, parent); newDirOpen = false; newDirName = ""; refresh() }
                        catch (e: Exception) { reportError("创建失败：${e.message}") }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newDirOpen = false }) { Text("取消") } },
        )
    }

    if (trashOpen) {
        AlertDialog(
            onDismissRequest = { trashOpen = false },
            title = { Text("回收站") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 320.dp)) {
                    if (trashItems.isEmpty()) Text("回收站是空的")
                    trashItems.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) { Text(t.name) }
                            TextButton(onClick = {
                                scope.launch {
                                    try { repo.restore(t.id); trashItems = repo.trash(); refresh() }
                                    catch (e: Exception) { reportError("恢复失败：${e.message}") }
                                }
                            }) { Text("恢复") }
                            TextButton(onClick = {
                                scope.launch {
                                    try { repo.purgeTrash(t.id); trashItems = repo.trash() }
                                    catch (e: Exception) { reportError("删除失败：${e.message}") }
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
