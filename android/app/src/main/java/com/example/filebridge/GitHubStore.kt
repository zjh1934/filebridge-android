package com.example.filebridge

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「沙盒文件」后端 —— 把 GitHub 仓库当作 7x24 可用的文件中转站。
 *
 * 为什么可行：
 *   GitHub 不能跑常驻 Python 进程，但它的 Contents API 本身就是常驻的读写接口。
 *   沙盒侧把持久区文件 commit 进仓库的 sandbox-files/ 目录，
 *   手机 APP 直接通过 Contents API 列目录 / 读 / 编辑 / 删除，改完 commit 回去。
 *   下次对话再把仓库改动拉回沙盒，完成闭环。
 *
 * 不需要：电脑、路由器、光猫 IP、公网入口、常驻服务器。
 * 只需要：粘贴一次 Token（存本机，之后不用再填）。
 *
 * 注意：仓库是公开的，放进 sandbox-files/ 的内容别人也能看到，别放私密文件。
 */
class GitHubStore(
    private val ctx: Context,
    private val owner: String,
    private val repo: String,
    private val token: String,
    private val root: String = "sandbox-files",
    private val branch: String = "main",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val api = "https://api.github.com/repos/$owner/$repo/contents"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun headers(): Request.Builder = Request.Builder()
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "FileBridge-Android")

    private fun pathOf(rel: String): String =
        listOf(root.trim('/'), rel.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    private fun trashRelOf(rel: String): String =
        listOf(root.trim('/'), ".trash", rel.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    private fun call(req: Request): JSONObject? {
        val t0 = System.nanoTime()
        try {
            client.newCall(req).execute().use { resp ->
                val ms = (System.nanoTime() - t0) / 1_000_000L
                val body = resp.body?.string().orEmpty()
                DevLog.http(req.method, safeUrl(req.url.encodedPath), resp.code, ms,
                    if (resp.isSuccessful) null else body.take(160))
                if (!resp.isSuccessful) {
                    // 401 单独给一句人话
                    if (resp.code == 401) error("令牌无效或已过期，请在「设置」里重新粘贴")
                    if (resp.code == 404) return null
                    error("GitHub ${resp.code}：${body.take(160)}")
                }
                if (body.isBlank()) return JSONObject()
                return try {
                    if (body.trimStart().startsWith("[")) {
                        JSONObject().put("__array", JSONArray(body))
                    } else JSONObject(body)
                } catch (e: Exception) {
                    JSONObject()
                }
            }
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            DevLog.http(req.method, safeUrl(req.url.encodedPath), -1, ms, e.toString())
            DevLog.e("GH", "请求失败 · ${e.message}", e)
            throw e
        }
    }

    private fun safeUrl(p: String): String = DevLog.sanitize(p)

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md", "txt", "json", "log", "csv", "xml", "yml", "yaml", "sh", "py", "kt", "java", "html", "css", "sql" -> "text/plain"
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
            "pdf" -> "application/pdf"; "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun toItem(o: JSONObject, relBase: String): FileItem {
        val path = o.optString("path")
        val type = if (o.optString("type") == "dir") "dir" else "file"
        val rel = if (path.startsWith(root.trim('/') + "/")) path.removePrefix(root.trim('/') + "/") else path
        return FileItem(
            id = path,
            name = o.optString("name"),
            parent = rel.substringBeforeLast('/', ""),
            rel = rel,
            type = type,
            size = o.optLong("size", 0L),
            sha256 = o.optString("sha"),   // git blob sha，当 ETag 用
            mime = if (type == "dir") "inode/directory" else mimeOf(o.optString("name")),
            updated_at = 0.0,
        )
    }

    /** 列目录：GET /contents/{path} */
    fun list(parent: String): ListResp {
        if (token.isBlank()) error("还没粘贴令牌：点顶栏「设置」，把那串令牌粘一次就行")
        val rel = parent.trim('/')
        val p = pathOf(rel)
        val req = headers().url("$api/$p?ref=$branch").get().build()
        val j = call(req) ?: return ListResp(parent = rel)
        val arr = j.optJSONArray("__array") ?: return ListResp(parent = rel)
        val dirs = mutableListOf<FileItem>()
        val files = mutableListOf<FileItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            if (name.startsWith(".")) continue          // 隐藏 .trash 等内部目录
            val item = toItem(o, rel)
            if (item.type == "dir") dirs += item else files += item
        }
        DevLog.i("GH", "列出沙盒 /$rel · ${dirs.size} 目录 · ${files.size} 文件")
        return ListResp(rel, dirs.sortedBy { it.name }, files.sortedBy { it.name })
    }

    private fun getRaw(rel: String): JSONObject? {
        val req = headers().url("$api/${pathOf(rel)}?ref=$branch").get().build()
        return call(req)
    }

    /** 读原始字节。>1MB 时 content 为空，改走 blob 接口 */
    fun readBytes(rel: String): ByteArray {
        val j = getRaw(rel) ?: error("仓库里没有这个文件")
        val content = j.optString("content").replace("\n", "").replace("\r", "")
        if (content.isNotEmpty()) {
            return android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        }
        val sha = j.optString("sha")
        val req = headers().url("https://api.github.com/repos/$owner/$repo/git/blobs/$sha").get().build()
        val b = call(req) ?: error("读取 blob 失败")
        val c = b.optString("content").replace("\n", "").replace("\r", "")
        return android.util.Base64.decode(c, android.util.Base64.DEFAULT)
    }

    fun readText(rel: String): String = String(readBytes(rel), Charsets.UTF_8)

    /** 新建或覆盖：PUT /contents/{path}，带 sha 表示更新（乐观锁） */
    fun put(rel: String, bytes: ByteArray, message: String, oldSha: String? = null): FileItem {
        val o = JSONObject()
        o.put("message", message)
        o.put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        o.put("branch", branch)
        if (!oldSha.isNullOrBlank()) o.put("sha", oldSha)
        val req = headers().url("$api/${pathOf(rel)}")
            .put(o.toString().toRequestBody(jsonMedia)).build()
        val j = call(req) ?: error("写入失败")
        val content = j.optJSONObject("content") ?: JSONObject()
        DevLog.i("GH", "写入 /$rel · ${bytes.size} B · ${message}")
        return FileItem(
            id = pathOf(rel), name = rel.substringAfterLast('/'), parent = rel.substringBeforeLast('/', ""),
            rel = rel, type = "file", size = bytes.size.toLong(),
            sha256 = content.optString("sha", j.optString("sha")),
            mime = mimeOf(rel.substringAfterLast('/')),
        )
    }

    fun createText(name: String, parent: String, text: String = ""): FileItem =
        put(joinRel(parent, name), text.toByteArray(), "FileBridge: 新建 ${name}")

    fun saveText(rel: String, text: String): FileItem =
        put(rel, text.toByteArray(), "FileBridge: 编辑 ${rel.substringAfterLast('/')}", currentSha(rel))

    private fun currentSha(rel: String): String? = try {
        getRaw(rel)?.optString("sha")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** GitHub 没有空目录，用 .gitkeep 占位 */
    fun mkdir(name: String, parent: String): FileItem {
        val rel = joinRel(joinRel(parent, name), ".gitkeep")
        return put(rel, ByteArray(0), "FileBridge: 新建目录 $name")
    }

    fun importFrom(uri: Uri, name: String, parent: String): FileItem {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("读不到所选文件")
        val clean = File(name.replace('\\', '/')).name
        if (clean.isEmpty() || clean == "." || clean == "..") error("文件名不合法")
        return put(joinRel(parent, clean), bytes, "FileBridge: 导入 $clean")
    }

    fun rename(rel: String, newName: String): FileItem {
        val clean = File(newName.replace('\\', '/')).name
        val dst = joinRel(rel.substringBeforeLast('/', ""), clean)
        val bytes = try { readBytes(rel) } catch (_: Exception) { ByteArray(0) }
        put(dst, bytes, "FileBridge: 改名 $rel → $dst")
        deleteRaw(rel, "FileBridge: 改名清理 $rel")
        DevLog.i("GH", "改名 /$rel → /$dst")
        return FileItem(id = pathOf(dst), name = clean, parent = rel.substringBeforeLast('/', ""),
            rel = dst, type = "file", size = bytes.size.toLong(), mime = mimeOf(clean))
    }

    private fun deleteRaw(rel: String, message: String) {
        val sha = currentSha(rel) ?: return
        val o = JSONObject().put("message", message).put("sha", sha).put("branch", branch)
        val req = headers().url("$api/${pathOf(rel)}")
            .delete(o.toString().toRequestBody(jsonMedia)).build()
        call(req)
    }

    // ---------------- 回收站（仓库里的 .trash 目录） ----------------
    fun delete(rel: String) {
        getRaw(rel) ?: error("文件不存在")
        val bytes = try { readBytes(rel) } catch (_: Exception) { ByteArray(0) }
        val id = System.currentTimeMillis().toString().takeLast(10)
        val name = rel.substringAfterLast('/')
        val tRel = joinRel(".trash", "${id}_$name")
        put(tRel, bytes, "FileBridge: 移入回收站 $name")
        // 写一份元信息，恢复时知道原路径
        put(joinRel(".trash", "${id}_$name.meta"),
            JSONObject().put("rel", rel).put("name", name).toString().toByteArray(),
            "FileBridge: 回收站元信息 $name")
        deleteRaw(rel, "FileBridge: 移入回收站 $name")
        DevLog.i("GH", "沙盒文件 /$rel 移入回收站 (id=$id)")
    }

    fun trash(): List<TrashItem> {
        if (token.isBlank()) return emptyList()
        val req = headers().url("$api/${trashRelOf("")}?ref=$branch").get().build()
        val j = call(req) ?: return emptyList()
        val arr = j.optJSONArray("__array") ?: return emptyList()
        val out = mutableListOf<TrashItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fileName = o.optString("name")
            if (!fileName.endsWith(".meta")) continue
            val key = fileName.removeSuffix(".meta")          // 形如 1732700000_笔记.md
            val meta = try { JSONObject(readText(joinRel(".trash", fileName))) } catch (_: Exception) { JSONObject() }
            out += TrashItem(
                id = key,
                name = meta.optString("name", key.substringAfter('_')),
                rel = meta.optString("rel", ""),
                parent = meta.optString("rel").substringBeforeLast('/', ""),
                type = "file",
                deleted_at = key.substringBefore('_').toDoubleOrNull() ?: 0.0,
            )
        }
        return out.sortedByDescending { it.deleted_at ?: 0.0 }
    }

    fun restore(key: String) {
        val metaName = "$key.meta"
        val meta = JSONObject(readText(joinRel(".trash", metaName)))
        val rel = meta.optString("rel")
        val name = meta.optString("name")
        val tFile = joinRel(".trash", key)
        val bytes = try { readBytes(tFile) } catch (_: Exception) { ByteArray(0) }
        put(rel, bytes, "FileBridge: 恢复 $name")
        deleteRaw(tFile, "FileBridge: 恢复清理 $tFile")
        deleteRaw(joinRel(".trash", metaName), "FileBridge: 恢复清理元信息")
        DevLog.i("GH", "从回收站恢复 /$rel")
    }

    fun purge(key: String) {
        deleteRaw(joinRel(".trash", key), "FileBridge: 彻底删除 $key")
        deleteRaw(joinRel(".trash", "$key.meta"), "FileBridge: 彻底删除元信息")
        DevLog.i("GH", "彻底删除 $key")
    }

    /** 下载到 APP 私有目录，用于「打开 / 分享」 */
    fun localFile(rel: String): File {
        val bytes = try { readBytes(rel) } catch (_: Exception) { ByteArray(0) }
        val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
        val f = File(dir, rel.substringAfterLast('/'))
        f.writeBytes(bytes)
        return f
    }

    private fun joinRel(parent: String, name: String) =
        listOf(parent.trim('/'), name.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
}
