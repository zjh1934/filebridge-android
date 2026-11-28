package com.example.filebridge

/*
 * 下载管理页面（v1.8）
 *
 * 借鉴云盘客户端的「传输中心」：统一队列、进度与字节数、按状态筛选、
 * 失败单个/批量重试、取消、清空已结束记录、完成项直接打开。
 * 全部用 Jetpack Compose 自写，未复制任何第三方源码。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun statusText(s: DownloadCenter.Status): String = when (s) {
    DownloadCenter.Status.QUEUED -> "等待中"
    DownloadCenter.Status.RUNNING -> "下载中"
    DownloadCenter.Status.DONE -> "已完成"
    DownloadCenter.Status.FAILED -> "失败"
    DownloadCenter.Status.CANCELLED -> "已取消"
}

private fun statusColor(s: DownloadCenter.Status): Color = when (s) {
    DownloadCenter.Status.RUNNING, DownloadCenter.Status.QUEUED -> FB.Accent
    DownloadCenter.Status.DONE -> FB.FileBlue
    DownloadCenter.Status.FAILED -> FB.Danger
    DownloadCenter.Status.CANCELLED -> FB.Slate
}

private fun fmtBytes(n: Long): String = when {
    n >= 1048576 -> "%.1f MB".format(n / 1048576.0)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val tasks by DownloadCenter.tasks.collectAsState()
    var filter by remember { mutableStateOf(0) } // 0 全部 1 进行中 2 已完成 3 失败

    val shown = when (filter) {
        1 -> tasks.filter { it.status == DownloadCenter.Status.RUNNING ||
                it.status == DownloadCenter.Status.QUEUED }
        2 -> tasks.filter { it.status == DownloadCenter.Status.DONE }
        3 -> tasks.filter { it.status == DownloadCenter.Status.FAILED ||
                it.status == DownloadCenter.Status.CANCELLED }
        else -> tasks
    }.asReversed()

    val active = tasks.count { it.status == DownloadCenter.Status.RUNNING ||
            it.status == DownloadCenter.Status.QUEUED }
    val done = tasks.count { it.status == DownloadCenter.Status.DONE }
    val failed = tasks.count { it.status == DownloadCenter.Status.FAILED ||
            it.status == DownloadCenter.Status.CANCELLED }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = FB.Surface,
        shape = FB.ShapeSheet,
        modifier = Modifier.fillMaxHeight(0.92f),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            // 标题 + 落盘位置说明
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = "下载管理", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "文件存到 系统下载 / ${DownloadCenter.FOLDER}/，可在系统「文件」里直接看到",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatPill("进行中 $active", FB.AccentSoft, FB.Accent)
                StatPill("已完成 $done", FB.AccentSoft, FB.FileBlue)
                StatPill("失败/取消 $failed", FB.DangerSoft, FB.Danger)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("全部", "进行中", "已完成", "失败").forEachIndexed { i, label ->
                    AssistChip(
                        onClick = { filter = i },
                        label = { Text(label) },
                        shape = FB.ShapePill,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (filter == i) FB.AccentSoft else FB.Surface),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { DownloadCenter.retryAllFailed() }, enabled = failed > 0) {
                    Text("重试全部失败")
                }
                TextButton(onClick = { DownloadCenter.cancelAll() }, enabled = active > 0) {
                    Text("全部取消")
                }
                TextButton(onClick = { DownloadCenter.clearFinished() },
                    enabled = done + failed > 0) {
                    Text("清空已结束记录")
                }
            }
            Text(
                text = "清空只移除这里的记录，不会删掉已经下载到系统目录里的文件。",
                style = MaterialTheme.typography.bodySmall, color = FB.Slate,
            )

            Spacer(Modifier.height(10.dp))
            if (shown.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "这里还没有下载任务", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(text = "在文件上点「⋯ → 下载到手机」，或整个文件夹批量下载。",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { t ->
                        TaskCard(t = t, ctx = ctx)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatPill(label: String, bg: Color, fg: Color) {
    Surface(shape = FB.ShapePill, color = bg) {
        Text(text = label, fontSize = 12.sp, color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun TaskCard(t: DownloadCenter.Task, ctx: android.content.Context) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FB.ShapeCard,
        color = FB.Paper,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = t.name, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = t.displayPath, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = FB.Slate)
                }
                Spacer(Modifier.width(8.dp))
                Surface(shape = FB.ShapePill, color = statusColor(t.status).copy(alpha = 0.12f)) {
                    Text(text = statusText(t.status), fontSize = 11.sp,
                        color = statusColor(t.status),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            when (t.status) {
                DownloadCenter.Status.RUNNING -> {
                    LinearProgressIndicator(
                        progress = { t.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = FB.Accent, trackColor = FB.AccentSoft,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${t.percent}% · ${fmtBytes(t.done)} / ${if (t.total > 0) fmtBytes(t.total) else "计算中"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DownloadCenter.Status.DONE -> {
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))
                        .background(FB.Accent))
                    Spacer(Modifier.height(6.dp))
                    Text(text = "已保存 · ${fmtBytes(t.done)}",
                        style = MaterialTheme.typography.bodySmall, color = FB.FileBlue)
                }
                DownloadCenter.Status.FAILED -> {
                    Text(text = t.error.ifBlank { "下载失败" },
                        style = MaterialTheme.typography.bodySmall, color = FB.Danger,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DownloadCenter.Status.CANCELLED, DownloadCenter.Status.QUEUED -> {
                    Text(text = if (t.status == DownloadCenter.Status.QUEUED)
                        "排队中，前面任务完成就轮到" else "已取消，可重试",
                        style = MaterialTheme.typography.bodySmall, color = FB.Slate)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (t.status) {
                    DownloadCenter.Status.RUNNING, DownloadCenter.Status.QUEUED ->
                        TextButton(onClick = { DownloadCenter.cancel(t.id) }) {
                            Text("取消", color = FB.Danger)
                        }
                    DownloadCenter.Status.FAILED, DownloadCenter.Status.CANCELLED -> {
                        TextButton(onClick = { DownloadCenter.retry(t.id) }) { Text("重试") }
                    }
                    DownloadCenter.Status.DONE -> {
                        Button(
                            onClick = { DownloadCenter.open(ctx, t) },
                            shape = FB.ShapePill,
                            colors = ButtonDefaults.buttonColors(containerColor = FB.Accent),
                        ) { Text("打开") }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { DownloadCenter.remove(t.id) }) {
                    Text("移除记录", color = FB.Slate)
                }
            }
        }
    }
}
