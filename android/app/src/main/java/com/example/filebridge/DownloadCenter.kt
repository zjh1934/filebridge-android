package com.example.filebridge

/*
 * 下载中心（v1.8）
 *
 * 借鉴 Davio / Material Files / 云盘客户端的「传输中心」思路：
 *   统一队列、进度与速度、等待队列、失败可单个/批量重试、取消、清空已结束记录。
 * 代码全部自己写，不复制任何 GPL 源码。
 *
 * 落盘位置：系统公共 Download 目录下新建一个以软件名命名的文件夹
 *   Download/沙盒管理器/...
 * Android 10+ 走 MediaStore.Downloads（无需任何存储权限，分区存储合规）；
 * Android 9 及以下走公共目录 File + FileProvider 授权。
 */

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object DownloadCenter {

    const val FOLDER = "沙盒管理器"
    private const val MAX_CONCURRENT = 2
    private const val STATE_FILE = "downloads.json"

    enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    data class Task(
        val id: String,
        val rel: String,
        val name: String,
        val subDir: String = "",
        val sizeHint: Long = 0L,
        val status: Status = Status.QUEUED,
        val done: Long = 0L,
        val total: Long = 0L,
        val error: String = "",
        val uri: String = "",
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
    ) {
        val percent: Int
            get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val displayPath: String
            get() = "Download/$FOLDER" + (if (subDir.isEmpty()) "" else "/$subDir") + "/$name"
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** 显式取消标记：OkHttp 同步读循环不是协程挂起点，cancel() 不会自动抛异常，必须主动查 */
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val running = AtomicInteger(0)
    private val pumpLock = Mutex()
    private var appCtx: Context? = null

    // ---------------------------------------------------------------- 生命周期

    /** 在 App 启动时调一次：恢复历史记录，把被中断的任务标成可重试 */
    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        val f = File(ctx.filesDir, STATE_FILE)
        if (!f.isFile) return
        val list = mutableListOf<Task>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var st = try { Status.valueOf(o.optString("status", "QUEUED")) }
                        catch (_: Exception) { Status.QUEUED }
                if (st == Status.RUNNING || st == Status.QUEUED) {
                    st = Status.FAILED
                }
                list += Task(
                    id = o.optString("id"),
                    rel = o.optString("rel"),
                    name = o.optString("name"),
                    subDir = o.optString("subDir"),
                    sizeHint = o.optLong("sizeHint"),
                    status = st,
                    done = if (st == Status.DONE) o.optLong("done") else 0L,
                    total = if (st == Status.DONE) o.optLong("total") else 0L,
                    error = if (st == Status.FAILED) "应用重启，任务中断，点重试继续" else "",
                    uri = if (st == Status.DONE) o.optString("uri") else "",
                    startedAt = o.optLong("startedAt"),
                    finishedAt = o.optLong("finishedAt"),
                )
            }
        } catch (e: Exception) {
            DevLog.e("DL", "读取下载记录失败", e)
        }
        _tasks.value = list
    }

    private fun persist() {
        val ctx = appCtx ?: return
        try {
            val arr = JSONArray()
            for (t in _tasks.value) {
                arr.put(JSONObject()
                    .put("id", t.id).put("rel", t.rel).put("name", t.name)
                    .put("subDir", t.subDir).put("sizeHint", t.sizeHint)
                    .put("status", t.status.name).put("done", t.done)
                    .put("total", t.total).put("uri", t.uri)
                    .put("startedAt", t.startedAt).put("finishedAt", t.finishedAt))
            }
            File(ctx.filesDir, STATE_FILE).writeText(arr.toString())
        } catch (e: Exception) {
            DevLog.e("DL", "保存下载记录失败", e)
        }
    }

    /** 进度每 64KB 就回调一次，不能每次都写磁盘；persistNow=false 只刷新界面 */
    private fun patch(id: String, persistNow: Boolean = true, change: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.id == id) change(it) else it } }
        if (persistNow) persist()
    }

    // ---------------------------------------------------------------- 入队

    fun enqueue(rel: String, subDir: String = "", sizeHint: Long = 0L): Task {
        val t = Task(
            id = System.nanoTime().toString() + "-" + (0..999).random(),
            rel = rel,
            name = rel.substringAfterLast('/'),
            subDir = subDir.trim('/'),
            sizeHint = sizeHint,
        )
        _tasks.update { it + t }
        persist()
        DevLog.i("DL", "入队 /$rel → ${t.displayPath}")
        pump()
        return t
    }

    /** 下载整个文件夹：保留内部相对目录结构，避免一堆同名图互相覆盖 */
    fun enqueueFolder(folderRel: String, paths: List<String>): Int {
        val name = folderRel.substringAfterLast('/')
        var n = 0
        for (p in paths) {
            if (p.endsWith("/.gitkeep") || p.contains("/.git/")) continue
            val inner = p.removePrefix("$folderRel/")
            val sub = inner.substringBeforeLast('/', "")
            val target = if (sub.isEmpty()) name else "$name/$sub"
            // 用同一批量 id 前缀方便观察，但不做分组 UI
            val t = Task(
                id = System.nanoTime().toString() + "-" + (0..999).random(),
                rel = p,
                name = p.substringAfterLast('/'),
                subDir = target,
            )
            _tasks.update { it + t }
            n++
        }
        persist()
        DevLog.i("DL", "文件夹 /$folderRel 批量入队 $n 个文件")
        pump()
        return n
    }

    fun retry(id: String) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        if (t.status == Status.RUNNING || t.status == Status.QUEUED) return
        cancelledIds.remove(id)
        patch(id) { it.copy(status = Status.QUEUED, done = 0L, total = 0L,
            error = "", uri = "", startedAt = 0L, finishedAt = 0L) }
        pump()
    }

    fun cancel(id: String) {
        cancelledIds.add(id)
        jobs[id]?.cancel()
    }

    fun cancelAll() {
        for (id in _tasks.value.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
            .map { it.id }) cancel(id)
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.status == Status.QUEUED || it.status == Status.RUNNING } }
        persist()
    }

    fun remove(id: String) {
        jobs[id]?.cancel()
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun retryAllFailed() {
        val ids = _tasks.value.filter { it.status == Status.FAILED || it.status == Status.CANCELLED }
            .map { it.id }
        for (id in ids) {
            cancelledIds.remove(id)
            patch(id) { it.copy(status = Status.QUEUED, done = 0L, error = "") }
        }
        pump()
    }

    // ---------------------------------------------------------------- 队列

    private fun pump() {
        scope.launch {
            pumpLock.withLock {
                while (running.get() < MAX_CONCURRENT) {
                    // 立刻把状态改成 RUNNING，避免同一批循环里重复认领同一个任务
                    val next = _tasks.value.firstOrNull { it.status == Status.QUEUED } ?: break
                    patch(next.id) { it.copy(status = Status.RUNNING,
                        startedAt = System.currentTimeMillis(), error = "") }
                    running.incrementAndGet()
                    val job = scope.launch { runTask(next.id) }
                    jobs[next.id] = job
                    job.invokeOnCompletion { jobs.remove(next.id) }
                }
            }
        }
    }

    private suspend fun runTask(id: String) {
        val ctx = appCtx
        val gh = currentStore
        if (ctx == null || gh == null) {
            patch(id) { it.copy(status = Status.FAILED, error = "还没连上沙盒，先粘一次令牌",
                finishedAt = System.currentTimeMillis()) }
            running.decrementAndGet(); pump(); return
        }
        val t0 = _tasks.value.firstOrNull { it.id == id } ?: run {
            running.decrementAndGet(); return
        }
        var pendingUri: Uri? = null
        var sink: OutputStream? = null
        try {
            val mime = mimeOf(t0.name)
            val (uri, os) = createPending(ctx, t0.subDir, t0.name, mime)
            pendingUri = uri; sink = os
            val bytes = gh.downloadToSink(
                rel = t0.rel,
                totalHint = t0.sizeHint,
                isCancelled = { cancelledIds.contains(id) },
                sink = os,
                onProgress = { done, total ->
                    // 每 256KB 或到末尾才刷新一次，避免 20MB 文件触发几百次重组/写盘
                    val last = _tasks.value.firstOrNull { it.id == id }?.done ?: 0L
                    if (done - last >= 256 * 1024 || (total > 0 && done >= total)) {
                        patch(id, persistNow = false) { it.copy(done = done, total = total) }
                    }
                },
            )
            os.flush(); os.close(); sink = null
            finishPending(ctx, uri)
            val finalUri = uri.toString()
            patch(id) { it.copy(status = Status.DONE, done = bytes,
                total = if (it.total > 0) it.total else bytes,
                uri = finalUri, finishedAt = System.currentTimeMillis()) }
            DevLog.i("DL", "完成 /${t0.rel} → ${t0.displayPath} · $bytes 字节")
        } catch (e: CancellationException) {
            try { sink?.close() } catch (_: Exception) { }
            pendingUri?.let { discardPending(ctx, it) }
            patch(id) { it.copy(status = Status.CANCELLED, error = "已取消",
                finishedAt = System.currentTimeMillis()) }
            DevLog.i("DL", "取消 /${t0.rel}")
        } catch (e: Exception) {
            try { sink?.close() } catch (_: Exception) { }
            pendingUri?.let { discardPending(ctx, it) }
            val msg = e.message ?: e.toString()
            patch(id) { it.copy(status = Status.FAILED, error = msg.take(160),
                finishedAt = System.currentTimeMillis()) }
            DevLog.e("DL", "下载失败 /${t0.rel}：$msg", e)
        } finally {
            running.decrementAndGet()
            pump()
        }
    }

    /** MainActivity 在 token 变化时把当前 GitHubStore 交给下载中心 */
    @Volatile
    var currentStore: GitHubStore? = null

    // ---------------------------------------------------------------- 落盘

    private fun createPending(
        ctx: Context, subDir: String, name: String, mime: String,
    ): Pair<Uri, OutputStream> {
        val clean = name.replace('/', '_').replace('\\', '_')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER +
                    (if (subDir.isEmpty()) "" else "/$subDir")
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, clean)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: error("系统不允许写入 Download 目录")
            val os = ctx.contentResolver.openOutputStream(uri)
                ?: error("打不开下载输出流")
            return uri to os
        }
        // Android 9 及以下：公共目录 + FileProvider（Uri.fromFile 会触发 FileUriExposedException）
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER,
        )
        val dir = if (subDir.isEmpty()) base else File(base, subDir)
        if (!dir.exists() && !dir.mkdirs()) error("建不了目录：${dir.path}")
        val f = File(dir, clean)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return uri to FileOutputStream(f)
    }

    private fun finishPending(ctx: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cv = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, cv, null, null)
    }

    private fun discardPending(ctx: Context, uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.delete(uri, null, null)
            } else {
                // FileProvider uri 不暴露真实路径，取消时留空文件由系统覆盖，不做危险删除
            }
        } catch (e: Exception) {
            DevLog.e("DL", "清理未完成下载失败", e)
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val fromSys = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!fromSys.isNullOrBlank()) return fromSys
        return when (ext) {
            "md", "txt", "json", "log", "csv", "yml", "yaml", "py", "kt", "html" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    // ---------------------------------------------------------------- 打开 / 提示

    fun open(ctx: Context, t: Task) {
        if (t.uri.isEmpty()) return
        try {
            val uri = Uri.parse(t.uri)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(t.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            DevLog.e("DL", "打开已下载文件失败", e)
            Toast.makeText(ctx, "没有能打开这类文件的应用，去系统「下载」里找 $FOLDER 文件夹",
                Toast.LENGTH_LONG).show()
        }
    }
}
