package com.example.filebridge

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 零配置本机文件管理器。
 *
 * 不需要路由器、不需要电脑、不需要 IP、不需要 Token、不需要网关：
 * 文件直接存在 APP 自己的私有目录 filesDir/MyFiles 里，打开 APP 就能用。
 * 通过「导入」把手机里别处的文件复制进来，通过「分享 / 另存为」把文件发出去。
 *
 * 目录结构与网关版一致（ListResp / FileItem），所以界面可以复用。
 */
class LocalStore(private val ctx: Context) {

    private val root = File(ctx.filesDir, "MyFiles").apply { mkdirs() }
    private val trashDir = File(ctx.filesDir, ".trash").apply { mkdirs() }
    private val index = File(ctx.filesDir, ".trash_index.json")

    private fun relOf(parent: String, name: String) =
        listOf(parent.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")

    /** 防穿越：解析后必须仍在 root 内 */
    private fun fileOf(rel: String): File {
        val f = File(root, rel).canonicalFile
        val r = root.canonicalFile
        if (f != r && !f.path.startsWith(r.path + File.separator)) {
            error("非法路径：$rel")
        }
        return f
    }

    private fun sha(f: File): String = try {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        d.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "md", "txt", "json", "log", "csv", "xml", "yml", "yaml" -> "text/plain"
                else -> "application/octet-stream"
            }
    }

    private fun item(f: File, rel: String): FileItem = FileItem(
        id = rel,
        name = f.name,
        parent = rel.substringBeforeLast('/', ""),
        rel = rel,
        type = if (f.isDirectory) "dir" else "file",
        size = if (f.isDirectory) 0L else f.length(),
        sha256 = if (f.isDirectory) "" else sha(f),
        mime = if (f.isDirectory) "inode/directory" else mimeOf(f.name),
        updated_at = f.lastModified().toDouble(),
    )

    fun rootLabel(): String = "本机文件 · ${root.absolutePath}"

    fun list(parent: String): ListResp {
        val p = parent.trim('/')
        val dir = fileOf(p)
        if (!dir.exists()) dir.mkdirs()
        val dirs = mutableListOf<FileItem>()
        val files = mutableListOf<FileItem>()
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(".")) return@forEach   // 隐藏回收站等内部目录
            val rel = relOf(p, f.name)
            if (f.isDirectory) dirs += item(f, rel) else files += item(f, rel)
        }
        DevLog.d("LOCAL", "列目录 /$p · ${dirs.size} 目录 · ${files.size} 文件")
        return ListResp(
            parent = p,
            dirs = dirs.sortedBy { it.name },
            files = files.sortedBy { it.name },
        )
    }

    fun mkdir(name: String, parent: String): FileItem {
        val clean = cleanName(name)
        val f = fileOf(relOf(parent, clean))
        if (f.exists()) error("「$clean」已存在")
        if (!f.mkdirs()) error("创建失败")
        DevLog.i("LOCAL", "新建目录 /${relOf(parent, clean)}")
        return item(f, relOf(parent, clean))
    }

    fun createText(name: String, parent: String, content: String = ""): FileItem {
        val clean = cleanName(name)
        val f = fileOf(relOf(parent, clean))
        if (f.exists()) error("「$clean」已存在")
        f.writeText(content, Charsets.UTF_8)
        DevLog.i("LOCAL", "新建文件 /${relOf(parent, clean)} · ${content.length} 字符")
        return item(f, relOf(parent, clean))
    }

    fun readText(rel: String): String {
        val f = fileOf(rel)
        if (!f.isFile) error("不是文件")
        val s = f.readText(Charsets.UTF_8)
        DevLog.d("LOCAL", "读取 /$rel · ${s.length} 字符")
        return s
    }

    fun saveText(rel: String, text: String): FileItem {
        val f = fileOf(rel)
        f.writeText(text, Charsets.UTF_8)
        DevLog.i("LOCAL", "保存 /$rel · ${text.toByteArray().size} B")
        return item(f, rel)
    }

    fun rename(rel: String, newName: String): FileItem {
        val clean = cleanName(newName)
        val src = fileOf(rel)
        if (!src.exists()) error("文件不存在")
        val dst = fileOf(relOf(rel.substringBeforeLast('/', ""), clean))
        if (dst.exists() && dst != src) error("目标已存在")
        if (!src.renameTo(dst)) error("改名失败")
        DevLog.i("LOCAL", "改名 /$rel → /${relOf(rel.substringBeforeLast('/', ""), clean)}")
        return item(dst, relOf(rel.substringBeforeLast('/', ""), clean))
    }

    // ---------------- 回收站 ----------------
    private fun loadTrash(): MutableList<JSONObject> {
        if (!index.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(index.readText(Charsets.UTF_8))
            (0 until arr.length()).map { arr.getJSONObject(it) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveTrash(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        index.writeText(arr.toString(), Charsets.UTF_8)
    }

    fun delete(rel: String) {
        val f = fileOf(rel)
        if (!f.exists()) error("文件不存在")
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val dst = File(trashDir, "${id}_${f.name}")
        if (!f.renameTo(dst)) error("移入回收站失败")
        val list = loadTrash()
        list += JSONObject().apply {
            put("id", id)
            put("name", f.name)
            put("rel", rel)
            put("deleted_at", System.currentTimeMillis())
            put("trash_name", dst.name)
        }
        saveTrash(list)
        DevLog.i("LOCAL", "移入回收站 /$rel (id=$id)")
    }

    fun trash(): List<TrashItem> = loadTrash().map {
        TrashItem(
            id = it.optString("id"),
            name = it.optString("name"),
            rel = it.optString("rel"),
            parent = it.optString("rel").substringBeforeLast('/', ""),
            type = "file",
            deleted_at = it.optLong("deleted_at").toDouble(),
        )
    }.sortedByDescending { it.deleted_at ?: 0.0 }

    fun restore(id: String) {
        val list = loadTrash()
        val rec = list.firstOrNull { it.optString("id") == id } ?: error("回收站里没有这条")
        val src = File(trashDir, rec.optString("trash_name"))
        val dst = fileOf(rec.optString("rel"))
        dst.parentFile?.mkdirs()
        if (dst.exists()) error("原位置已有同名文件")
        if (!src.renameTo(dst)) error("恢复失败")
        list.remove(rec)
        saveTrash(list)
        DevLog.i("LOCAL", "从回收站恢复 /${rec.optString("rel")}")
    }

    fun purge(id: String) {
        val list = loadTrash()
        val rec = list.firstOrNull { it.optString("id") == id } ?: error("回收站里没有这条")
        File(trashDir, rec.optString("trash_name")).delete()
        list.remove(rec)
        saveTrash(list)
        DevLog.i("LOCAL", "彻底删除 ${rec.optString("name")}")
    }

    // ---------------- 与手机其他位置互通 ----------------
    fun importFrom(uri: Uri, name: String, parent: String): FileItem {
        val clean = cleanName(name)
        var f = fileOf(relOf(parent, clean))
        if (f.exists()) {
            val stem = clean.substringBeforeLast('.', clean)
            val ext = clean.substringAfterLast('.', "")
            f = fileOf(relOf(parent, "${stem}_${System.currentTimeMillis()}${if (ext.isNotEmpty()) ".$ext" else ""}"))
        }
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("读不到所选文件")
        f.writeBytes(bytes)
        DevLog.i("LOCAL", "导入 $clean → /${relOf(parent, f.name)} · ${bytes.size} B")
        return item(f, relOf(parent, f.name))
    }

    fun fileOfRel(rel: String): File = fileOf(rel)

    fun viewIntent(f: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        DevLog.i("OPEN", "本机打开 ${f.name} · mime=$mime")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (mime == "application/octet-stream") "*/*" else mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.packageManager.queryIntentActivities(intent, 0).forEach { r ->
            ctx.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    fun shareIntent(f: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        DevLog.i("SHARE", "分享 ${f.name} · ${f.length()} B")
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(f.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(ctx.contentResolver, "file", uri)
        }
    }

    private fun cleanName(name: String): String {
        val n = File(name.replace('\\', '/')).name.trim()
        if (n.isEmpty() || n == "." || n == "..") error("文件名不合法")
        return n
    }
}
