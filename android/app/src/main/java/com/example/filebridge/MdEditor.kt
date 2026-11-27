package com.example.filebridge

/*
 * 内嵌 Markdown 编辑器
 *
 * 借鉴来源（只借鉴架构决策与界面结构，未复制任何源码）：
 *  - Markor (gsantner/markor, GPL-3.0)：文件优先的编辑器布局；用原生 EditText +
 *    Spannable 做语法着色而不是塞 WebView/CodeMirror（中文输入、长文滚动、内存都更好）；
 *    底部可横向滚动的格式化工具栏；列表自动续行；频繁异步落盘防「切后台被杀丢字」；
 *    编辑 / 预览双模式；离开时提示保存。
 *  - Markwon (noties/Markwon, Apache-2.0)：确认「Markdown → 原生 Spannable、无需 WebView」
 *    这条路线可行。但没有引入该库（会拖进 commonmark-java，把 1.7 MB 的包撑大），
 *    高亮与预览都在本文件里用 AnnotatedString 轻量实现。
 *  - Material Files (zhanghai/MaterialFiles, GPL-3.0+)：顶部面包屑 + 每行右侧 ⋯ 菜单的
 *    文件管理器结构（在 MainActivity 里落地）。
 *
 * 由于 Markor / Material Files 是 GPL，直接复制源码会带来许可证传染，因此全部逻辑
 * 用 Jetpack Compose 重写；本工程保留原有 MIT 声明，界面与交互思路注明出处。
 */

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 编辑器专用的几支颜色，不动 Theme.kt 的设计 token
private val MdCodeBg = Color(0xFFEDF2F0)
private val MdCodeInk = Color(0xFF1D5C52)
private val MdHit = Color(0xFFFFE08A)
private val MdQuote = Color(0xFF6A7A85)
private val MdLink = Color(0xFF1F6FA8)

/** 把 Markdown 源文本转成带语法高亮的 AnnotatedString（长度不变，可安全用于 VisualTransformation） */
fun mdHighlight(src: String, query: String = ""): AnnotatedString =
    AnnotatedString.Builder().apply {
        append(src)
        val n = src.length
        fun mark(span: SpanStyle, s: Int, e: Int) {
            if (s >= 0 && s < n && e > s) addStyle(span, s, e.coerceAtMost(n))
        }

        // 代码块（整段 mono + 浅底）
        Regex("(?s)```.*?(```|$)").findAll(src).forEach {
            mark(SpanStyle(color = MdCodeInk, background = MdCodeBg,
                fontFamily = FontFamily.Monospace), it.range.first, it.range.last + 1)
        }

        // 逐行：标题 / 引用 / 列表 / 分割线
        var off = 0
        for (line in src.split('\n')) {
            val a = off
            val b = (off + line.length).coerceAtMost(n)
            when {
                Regex("""^#{1,6}\s""").containsMatchIn(line) ->
                    mark(SpanStyle(color = FB.Accent, fontWeight = FontWeight.Bold), a, b)
                line.trimStart().startsWith(">") ->
                    mark(SpanStyle(color = MdQuote, fontWeight = FontWeight.Light), a, b)
                Regex("""^\s{0,8}([-*+]|\d+\.)\s""").containsMatchIn(line) -> {
                    val lead = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                    mark(SpanStyle(color = FB.Accent), a + lead, (a + lead + 2).coerceAtMost(b))
                }
                Regex("""^\s*(---|\*\*\*|___)\s*$""").matches(line) ->
                    mark(SpanStyle(color = FB.Slate, textDecoration = TextDecoration.LineThrough), a, b)
            }
            off = b + 1
        }

        // 行内：行内代码 / 粗体 / 斜体 / 链接 / 图片
        Regex("""`[^`\n]+`""").findAll(src).forEach {
            mark(SpanStyle(color = MdCodeInk, background = MdCodeBg,
                fontFamily = FontFamily.Monospace), it.range.first, it.range.last + 1)
        }
        Regex("""\*\*[^*\n]+\*\*|__[^_\n]+__""").findAll(src).forEach {
            mark(SpanStyle(fontWeight = FontWeight.Bold, color = FB.Ink), it.range.first, it.range.last + 1)
        }
        Regex("""(?<!\*)\*(?!\*)[^*\n]+\*(?!\*)|(?<!_)_(?!_)[^_\n]+_(?!_)""").findAll(src).forEach {
            mark(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                it.range.first, it.range.last + 1)
        }
        Regex("""!?\[[^\]\n]*\]\([^)\n]*\)""").findAll(src).forEach {
            mark(SpanStyle(color = MdLink, textDecoration = TextDecoration.Underline),
                it.range.first, it.range.last + 1)
        }

        // 查找命中：叠在最上层
        if (query.length >= 1) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(src).forEach {
                mark(SpanStyle(background = MdHit), it.range.first, it.range.last + 1)
            }
        }
    }.toAnnotatedString()

/** 轻量预览：不引入 WebView / 第三方解析库，逐行转成排版后的 AnnotatedString */
fun mdPreview(src: String): AnnotatedString {
    val b = AnnotatedString.Builder()
    var inCode = false
    for (line in src.lines()) {
        val t = line.trimEnd()
        if (t.trimStart().startsWith("```")) {
            inCode = !inCode
            continue
        }
        if (inCode) {
            b.pushStyle(SpanStyle(color = MdCodeInk, background = MdCodeBg,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)); b.append(t); b.pop()
            b.append("\n")
            continue
        }
        val head = Regex("""^(#{1,6})\s+(.*)$""").find(t)
        val quote = t.trimStart().startsWith(">")
        val li = Regex("""^\s*([-*+]|\d+\.)\s+(.*)$""").find(t)
        when {
            head != null -> {
                val level = head.groupValues[1].length
                b.pushStyle(SpanStyle(color = FB.Ink, fontWeight = FontWeight.Bold,
                    fontSize = when (level) { 1 -> 25.sp; 2 -> 21.sp; 3 -> 18.sp; else -> 16.sp }))
                b.append(head.groupValues[2]); b.pop()
            }
            quote -> {
                b.pushStyle(SpanStyle(color = MdQuote,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                b.append("│ " + t.trimStart().removePrefix(">").trim()); b.pop()
            }
            li != null -> {
                b.pushStyle(SpanStyle(color = FB.Ink))
                b.append("  " + (if (li.groupValues[1].matches(Regex("""\d+\."""))) li.groupValues[1] else "•") +
                        " " + li.groupValues[2]); b.pop()
            }
            Regex("""^\s*(---|\*\*\*|___)\s*$""").matches(t) -> {
                b.pushStyle(SpanStyle(color = FB.Line)); b.append("────────────"); b.pop()
            }
            else -> {
                b.pushStyle(SpanStyle(color = FB.Ink))
                b.append(t.replace("**", "").replace("__", "").replace("`", "")); b.pop()
            }
        }
        b.append("\n")
    }
    return b.toAnnotatedString()
}

/**
 * 全屏内嵌编辑器。改完点「保存到沙盒」直接 commit 到私有镜像仓；
 * 切后台/被杀时草稿自动落到本机私有目录，回来可一键恢复。
 */
@Composable
fun MdEditorScreen(
    item: FileItem,
    initialText: String,
    onSave: suspend (String) -> Unit,
    onClose: () -> Unit,
    onError: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tfv by remember { mutableStateOf(TextFieldValue(initialText)) }
    var savedText by remember { mutableStateOf(initialText) }
    var saving by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var exitAsk by remember { mutableStateOf(false) }
    var draftAvailable by remember { mutableStateOf<String?>(null) }
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }

    val dirty = tfv.text != savedText
    val draftFile = remember {
        File(ctx.filesDir, "drafts/" + item.rel.hashCode().toString() + ".md")
    }
    val hits = if (query.isEmpty()) 0 else
        Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(tfv.text).count()

    // Markor 的「永不丢字」：每次改动异步落一份草稿；进入时若发现草稿就提示恢复
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (draftFile.isFile && draftFile.length() > 0) {
                    val t = draftFile.readText()
                    if (t != initialText) draftAvailable = t
                }
            } catch (_: Exception) { }
        }
    }
    LaunchedEffect(tfv.text) {
        withContext(Dispatchers.IO) {
            try {
                draftFile.parentFile?.mkdirs()
                draftFile.writeText(tfv.text)
            } catch (_: Exception) { }
        }
    }

    fun applyEdit(nextText: String, sel: TextRange) {
        undoStack.add(tfv.text)
        if (undoStack.size > 120) undoStack.removeAt(0)
        redoStack.clear()
        tfv = TextFieldValue(nextText, TextRange(
            sel.start.coerceIn(0, nextText.length), sel.end.coerceIn(0, nextText.length)))
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(tfv.text)
        val prev = undoStack.removeAt(undoStack.lastIndex)
        tfv = TextFieldValue(prev, TextRange(prev.length))
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(tfv.text)
        val nxt = redoStack.removeAt(redoStack.lastIndex)
        tfv = TextFieldValue(nxt, TextRange(nxt.length))
    }

    /** 包裹/取消包裹选区：粗体、斜体、删除线、行内代码 */
    fun wrap(marker: String) {
        val t = tfv.text
        val s = tfv.selection.start.coerceIn(0, t.length)
        val e = tfv.selection.end.coerceIn(s, t.length)
        if (s == e) {
            val nt = t.substring(0, s) + marker + marker + t.substring(e)
            applyEdit(nt, TextRange(s + marker.length, s + marker.length))
            return
        }
        val inner = t.substring(s, e)
        if (inner.length >= marker.length * 2 &&
            inner.startsWith(marker) && inner.endsWith(marker)) {
            val bare = inner.removePrefix(marker).removeSuffix(marker)
            applyEdit(t.substring(0, s) + bare + t.substring(e), TextRange(s, s + bare.length))
        } else {
            val nt = t.substring(0, s) + marker + inner + marker + t.substring(e)
            applyEdit(nt, TextRange(s + marker.length, e + marker.length))
        }
    }

    /** 给光标所在行加/去前缀：标题、引用、列表 */
    fun prefixLine(prefix: String) {
        val t = tfv.text
        val caret = tfv.selection.start.coerceIn(0, t.length)
        val lineStart = t.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)) + 1
        val lineEnd = (t.indexOf('\n', caret)).let { if (it < 0) t.length else it }
        val line = t.substring(lineStart, lineEnd)
        val nt: String
        val delta: Int
        if (line.startsWith(prefix)) {
            nt = t.substring(0, lineStart) + line.removePrefix(prefix) + t.substring(lineEnd)
            delta = -prefix.length
        } else {
            // 已有别的前缀先剥掉，避免 ## 叠加
            val stripped = line.removePrefix("# ").removePrefix("## ")
                .removePrefix("> ").removePrefix("- ").removePrefix("* ")
            nt = t.substring(0, lineStart) + prefix + stripped + t.substring(lineEnd)
            delta = prefix.length - (line.length - stripped.length)
        }
        applyEdit(nt, TextRange((caret + delta).coerceIn(0, nt.length)))
    }

    fun insertAtCaret(s: String) {
        val t = tfv.text
        val c = tfv.selection.start.coerceIn(0, t.length)
        applyEdit(t.substring(0, c) + s + t.substring(c), TextRange(c + s.length))
    }

    fun replaceAll() {
        if (query.isEmpty()) return
        val nt = tfv.text.replace(Regex(Regex.escape(query), RegexOption.IGNORE_CASE),
            Regex.escapeReplacement(replaceText))
        applyEdit(nt, TextRange(nt.length))
    }

    suspend fun doSave(): Boolean {
        saving = true
        return try {
            onSave(tfv.text)
            savedText = tfv.text
            draftFile.parentFile?.mkdirs()
            withContext(Dispatchers.IO) { draftFile.writeText(tfv.text) }
            Toast.makeText(ctx, "已保存到沙盒（仓库已更新）", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            onError("保存失败：${e.message ?: e.toString()}")
            false
        } finally {
            saving = false
        }
    }

    BackHandler(enabled = true) {
        if (dirty) exitAsk = true else onClose()
    }

    Column(
        Modifier.fillMaxSize().background(FB.Paper).imePadding()
    ) {
        // ── 顶部：返回 / 文件名 / 未保存标记 / 保存（照 Markor 的顶栏）
        Surface(Modifier.fillMaxWidth(), color = FB.Surface, shadowElevation = 3.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (dirty) exitAsk = true else onClose() }) {
                        Text("‹ 返回", style = MaterialTheme.typography.titleSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(text = item.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1)
                        Text(
                            text = "/${item.rel} · ${tfv.text.length} 字 · ${tfv.text.lines().size} 行"
                                    + if (dirty) " · 未保存" else " · 已同步",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (dirty) FB.FileAmber else FB.Slate,
                            maxLines = 1,
                        )
                    }
                    if (dirty) {
                        Box(Modifier.size(8.dp).background(FB.FileAmber, RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = { scope.launch { doSave() } },
                        enabled = !saving,
                        shape = FB.ShapePill,
                        colors = ButtonDefaults.buttonColors(containerColor = FB.Accent),
                    ) { Text("保存到沙盒") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { preview = !preview },
                        label = { Text(if (preview) "编辑" else "预览") },
                        shape = FB.ShapePill,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (preview) FB.AccentSoft else FB.Surface))
                    AssistChip(onClick = { findOpen = !findOpen },
                        label = { Text("查找替换") }, shape = FB.ShapePill,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (findOpen) FB.AccentSoft else FB.Surface))
                    AssistChip(onClick = { undo() }, label = { Text("撤销") }, shape = FB.ShapePill)
                    AssistChip(onClick = { redo() }, label = { Text("重做") }, shape = FB.ShapePill)
                }
            }
        }

        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth(), color = FB.Accent)

        // ── 草稿恢复条
        draftAvailable?.let { d ->
            Surface(Modifier.fillMaxWidth(), color = FB.AccentSoft) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "发现上次没保存的草稿", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        tfv = TextFieldValue(d, TextRange(d.length))
                        draftAvailable = null
                    }) { Text("恢复草稿") }
                    TextButton(onClick = { draftAvailable = null }) { Text("忽略") }
                }
            }
        }

        // ── 查找 / 替换
        if (findOpen) {
            Surface(Modifier.fillMaxWidth(), color = FB.Surface) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            label = { Text("查找") }, singleLine = true,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            shape = FB.ShapeSmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = "$hits 处", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = replaceText, onValueChange = { replaceText = it },
                            label = { Text("替换为") }, singleLine = true,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            shape = FB.ShapeSmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { replaceAll() }) { Text("全部替换") }
                    }
                }
            }
        }

        // ── 正文：编辑 / 预览
        if (preview) {
            Text(
                text = mdPreview(tfv.text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            )
        } else {
            val transformation = VisualTransformation { src: AnnotatedString ->
                TransformedText(mdHighlight(src.text, query), OffsetMapping.Identity)
            }
            BasicTextField(
                value = tfv,
                onValueChange = { nv ->
                    val old = tfv.text
                    var next = nv
                    // Markor 的列表自动续行：回车后上一行是列表就补同样的标记
                    if (nv.text.length == old.length + 1) {
                        val c = nv.selection.start
                        if (c > 0 && c <= nv.text.length && nv.text[c - 1] == '\n') {
                            val ls = old.lastIndexOf('\n', (c - 2).coerceAtLeast(0)) + 1
                            val prevLine = if (ls < old.length) old.substring(ls, (c - 1).coerceAtMost(old.length)) else ""
                            val m = Regex("""^(\s*)([-*+]|\d+\.)\s+$""").find(prevLine)
                            if (m != null) {
                                val ins = m.groupValues[1] + m.groupValues[2] + " "
                                next = TextFieldValue(
                                    nv.text.substring(0, c) + ins + nv.text.substring(c),
                                    TextRange(c + ins.length),
                                )
                            }
                        }
                    }
                    undoStack.add(old)
                    if (undoStack.size > 120) undoStack.removeAt(0)
                    redoStack.clear()
                    tfv = next
                },
                modifier = Modifier
                    .weight(1f).fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .verticalScroll(rememberScrollState()),
                textStyle = TextStyle(color = FB.Ink, fontSize = 15.sp, lineHeight = 23.sp,
                    fontFamily = FontFamily.Monospace),
                visualTransformation = transformation,
                cursorBrush = SolidColor(FB.Accent),
            )
        }

        // ── 底部格式化工具栏（Markor 风格，横向滚动，长按不遮挡）
        if (!preview) {
            Surface(Modifier.fillMaxWidth(), color = FB.Surface, shadowElevation = 6.dp) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolBtn("H1") { prefixLine("# ") }
                    ToolBtn("H2") { prefixLine("## ") }
                    ToolBtn("H3") { prefixLine("### ") }
                    ToolBtn("B") { wrap("**") }
                    ToolBtn("I") { wrap("*") }
                    ToolBtn("S") { wrap("~~") }
                    ToolBtn("`") { wrap("`") }
                    ToolBtn("代码") { insertAtCaret("\n```\n\n```\n") }
                    ToolBtn("引用") { prefixLine("> ") }
                    ToolBtn("• 列表") { prefixLine("- ") }
                    ToolBtn("1. 列表") { prefixLine("1. ") }
                    ToolBtn("链接") { insertAtCaret("[文字](https://)") }
                    ToolBtn("分割线") { insertAtCaret("\n---\n") }
                    ToolBtn("表格") { insertAtCaret("\n| 列1 | 列2 |\n| --- | --- |\n|  |  |\n") }
                }
            }
        }
    }

    if (exitAsk) {
        AlertDialog(
            onDismissRequest = { exitAsk = false },
            title = { Text("还有没保存的修改") },
            text = { Text("草稿已经自动存了一份在手机里，下次打开还能恢复。要现在提交到仓库吗？") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        exitAsk = false
                        if (doSave()) onClose()
                    }
                }, shape = FB.ShapePill) { Text("保存并退出") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { exitAsk = false; onClose() }) { Text("放弃修改") }
                    TextButton(onClick = { exitAsk = false }) { Text("继续编辑") }
                }
            },
        )
    }
}

/** VisualTransformation 要求长度一一对应：mdHighlight 只上色、不改文本长度，
 *  所以直接用 OffsetMapping.Identity，光标与选区不会错位。 */

@Composable
private fun ToolBtn(label: String, onClick: () -> Unit) {
    Surface(
        shape = FB.ShapeSmall,
        color = FB.AccentSoft,
        modifier = Modifier.height(36.dp),
    ) {
        TextButton(
            onClick = onClick,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        ) { Text(text = label, fontSize = 12.sp, color = FB.Accent) }
    }
}
