package com.example.filebridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.File
import java.io.FileOutputStream

/**
 * 把 REST 接口包装成 APP 能用的动作。
 * 关键点：手机上永远拿不到服务端路径，文件先下载到 filesDir/downloads，
 * 再通过 FileProvider 的 content:// URI 交给系统应用打开。
 *
 * 每个动作都过 track()：记录名称、耗时、异常堆栈到开发者日志。
 */
class FileRepository(
    private val ctx: Context,
    private val api: FileApi,
    private val key: String,
) {
    private val localDir = File(ctx.filesDir, "downloads").apply { mkdirs() }

    private suspend fun <T> track(name: String, block: suspend () -> T): T {
        val t0 = System.nanoTime()
        return try {
            val r = block()
            val ms = (System.nanoTime() - t0) / 1_000_000L
            DevLog.i("API", "$name OK · ${ms}ms")
            r
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            DevLog.e("API", "$name FAIL · ${ms}ms · ${e.message}", e)
            throw e
        }
    }

    suspend fun health() = track("health") { api.health() }
    suspend fun list(parent: String) = track("list($parent)") { api.list(parent, key) }
    suspend fun trash() = track("trash") { api.trash(key) }
    suspend fun restore(id: String) = track("restore($id)") { api.restore(id, key) }
    suspend fun purgeTrash(id: String) = track("purgeTrash($id)") { api.purgeTrash(id, key) }
    suspend fun delete(id: String, purge: Boolean = false) =
        track("delete($id, purge=$purge)") { api.delete(id, if (purge) 1 else 0, key) }
    suspend fun rename(id: String, name: String) =
        track("rename($id -> $name)") { api.patch(id, PatchBody(name = name), key) }
    suspend fun mkdir(name: String, parent: String) =
        track("mkdir($parent/$name)") { api.mkdir(DirBody(name, parent), key) }

    private fun bytesOf(uri: Uri): ByteArray {
        DevLog.d("FILE", "读取本地 Uri: ${uri.scheme}://${uri.path?.takeLast(60)}")
        return ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: run {
                DevLog.e("FILE", "openInputStream 返回空，Uri 不可读")
                error("无法读取所选文件")
            }
    }

    suspend fun upload(uri: Uri, name: String, parent: String): FileItem = track("upload($parent/$name)") {
        val data = bytesOf(uri)
        DevLog.d("FILE", "本地文件 $name · ${data.size} B")
        val body = RequestBody.create("application/octet-stream".toMediaType(), data)
        val part = MultipartBody.Part.createFormData("file", name, body)
        val p = RequestBody.create("text/plain".toMediaType(), parent)
        api.upload(part, p, key)
    }

    /** 用本地新版本覆盖服务端文件，带 sha256 乐观锁 */
    suspend fun overwrite(uri: Uri, item: FileItem): FileItem = track("overwrite(${item.name})") {
        val data = bytesOf(uri)
        val body = RequestBody.create("application/octet-stream".toMediaType(), data)
        val part = MultipartBody.Part.createFormData("file", item.name, body)
        DevLog.d("FILE", "覆盖写 ${item.name} · ${data.size} B · If-Match=${item.sha256.take(8)}…")
        api.update(item.id, part, item.sha256, key)
    }

    /** APP 内置纯文本编辑：读全文 -> 改 -> PUT 回写 */
    suspend fun readText(item: FileItem): String = track("readText(${item.name})") {
        val resp = api.download(item.id, key)
        if (!resp.isSuccessful) error("读取失败 ${resp.code()}")
        val text = resp.body()?.string() ?: ""
        DevLog.d("FILE", "读取 ${item.name} · ${text.length} 字符")
        text
    }

    suspend fun saveText(item: FileItem, text: String): FileItem = track("saveText(${item.name})") {
        val body = RequestBody.create("text/plain".toMediaType(), text.toByteArray())
        val part = MultipartBody.Part.createFormData("file", item.name, body)
        DevLog.d("FILE", "回写 ${item.name} · ${text.toByteArray().size} B")
        api.update(item.id, part, item.sha256, key)
    }

    /** 下载到 APP 私有目录，返回本地 File */
    suspend fun localFile(item: FileItem): File = track("localFile(${item.name})") {
        val resp = api.download(item.id, key)
        if (!resp.isSuccessful) error("下载失败 ${resp.code()}")
        val f = File(localDir, item.name)
        resp.body()!!.byteStream().use { input ->
            FileOutputStream(f).use { output -> input.copyTo(output) }
        }
        DevLog.d("FILE", "落盘 ${f.absolutePath} · ${f.length()} B")
        f
    }

    /** 用系统应用打开：content:// + 临时读授权 */
    fun viewIntent(file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        DevLog.i("OPEN", "交给系统应用打开 ${file.name} · mime=$mime · uri=${DevLog.sanitize(uri.toString())}")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 双保险：对能处理该 Intent 的应用逐个显式授权（需 Manifest 里声明 <queries>）
        val targets = ctx.packageManager.queryIntentActivities(intent, 0)
        DevLog.d("OPEN", "可处理的应用 ${targets.size} 个")
        targets.forEach { resolve ->
            ctx.grantUriPermission(
                resolve.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        return intent
    }

    // ---- 开发者日志 ----
    suspend fun exportDevLog(): File = track("exportDevLog") { DevLog.exportMarkdown(ctx) }
    fun devLogShareIntent(file: File): Intent = DevLog.shareIntent(ctx, file)
    fun devLogSummary() = DevLog.summary()
    fun clearDevLog() = DevLog.clear()
}
