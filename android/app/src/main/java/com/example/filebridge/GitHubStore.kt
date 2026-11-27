package com.example.filebridge

import android.content.Context
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * 线程模型（v1.4 修复的重点）：
 *   OkHttp 用的是同步 execute()，绝不能在主线程调用。
 *   所有 public 方法都是 suspend，并且统一通过 io{} 切到 Dispatchers.IO。
 *   私有方法（call / getRaw / deleteRaw / currentSha）只在 io 块内部被调用。
 *   call() 开头还有一道主线程断言，万一将来哪里漏了会给出明确报错而不是 null。
 *
 * 之前 v1.2 / v1.3 的 bug：方法写成 suspend 但没切 dispatcher，
 * rememberCoroutineScope() 默认绑主线程，StrictMode 抛 NetworkOnMainThreadException，
 * 导致所有联网操作一次都没成功过。
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
    private val REMOTE_META_NAME = ".filebridge-mirror-info.md"

    /** 所有网络与文件 IO 都必须经过这里。block 是 suspend lambda，允许嵌套调用其它 public 方法。 */
    private suspend fun <T> io(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO, block)

    private fun headers(): Request.Builder = Request.Builder()
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "FileBridge-Android")

    private fun pathOf(rel: String): String =
        listOf(root.trim('/'), rel.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    private fun trashRelOf(rel: String): String =
        listOf(root.trim('/'), ".trash", rel.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    /** 仓库根（持久区根）时不能拼成 contents/ ，要去掉那条斜杠 */
    private fun contentsUrl(rel: String, withRef: Boolean = true): String {
        val p = pathOf(rel)
        val base = if (p.isBlank()) api else "$api/$p"
        return if (withRef) "$base?ref=$branch" else base
    }

    private fun call(req: Request): JSONObject? {
        // 防御：主线程直接发同步请求会抛 NetworkOnMainThreadException（message 为 null，很难查）
        if (Looper.getMainLooper()?.thread == Thread.currentThread()) {
            val e = IllegalStateException("网络请求误跑到主线程，应由 Dispatchers.IO 执行")
            DevLog.e("GH", e.message!!, e)
            throw e
        }
        val t0 = System.nanoTime()
        try {
            client.newCall(req).execute().use { resp ->
                val ms = (System.nanoTime() - t0) / 1_000_000L
                val body = resp.body?.string().orEmpty()
                DevLog.http(req.method, safeUrl(req.url.encodedPath), resp.code, ms,
                    if (resp.isSuccessful) null else body.take(160))
                if (!resp.isSuccessful) {
                    if (resp.code == 401) error("令牌无效或已过期，请重新粘贴")
                    if (resp.code == 404) return null
                    error("GitHub ${resp.code}：${body.take(160)}")
                }
                if (body.isBlank()) return JSONObject()
                return try {
                    if (body.trimStart().startsWith("[")) {
                        JSONObject().put("__array", JSONArray(body))
                    } else JSONObject(body)
                } catch (_: Exception) {
                    JSONObject()
                }
            }
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            DevLog.http(req.method, safeUrl(req.url.encodedPath), -1, ms, e.toString())
            DevLog.e("GH", "请求失败 · ${e.message ?: e.toString()}", e)
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

    private fun toItem(o: JSONObject): FileItem {
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
            sha256 = o.optString("sha"),
            mime = if (type == "dir") "inode/directory" else mimeOf(o.optString("name")),
            updated_at = 0.0,
        )
    }

    /** 列目录：GET /contents/{path} */
    suspend fun list(parent: String): ListResp = io {
        if (token.isBlank()) error("还没粘贴令牌：请先连上沙盒")
        val rel = parent.trim('/')
        val req = headers().url(contentsUrl(rel)).get().build()
        val j = call(req) ?: return@io ListResp(parent = rel)
        val arr = j.optJSONArray("__array") ?: return@io ListResp(parent = rel)
        val dirs = mutableListOf<FileItem>()
        val files = mutableListOf<FileItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            if (name.startsWith(".")) continue          // 隐藏 .trash 等内部目录
            val item = toItem(o)
            if (item.type == "dir") dirs += item else files += item
        }
        DevLog.i("GH", "列出沙盒 /$rel · ${dirs.size} 目录 · ${files.size} 文件")
        ListResp(rel, dirs.sortedBy { it.name }, files.sortedBy { it.name })
    }

    private fun getRaw(rel: String): JSONObject? {
        val req = headers().url(contentsUrl(rel)).get().build()
        return call(req)
    }

    /** 非 suspend 的列目录，仅供 io{} 内部的递归遍历使用 */
    private fun listRaw(rel: String): JSONArray? {
        val req = headers().url(contentsUrl(rel)).get().build()
        return call(req)?.optJSONArray("__array")
    }

    /** 全仓路径索引：一次拿整棵树，供「全仓搜索」用，不用逐级翻目录 */
    suspend fun allPaths(): List<String> = io {
        val req = headers()
            .url("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1")
            .get().build()
        val j = call(req) ?: return@io emptyList()
        val arr = j.optJSONArray("tree") ?: return@io emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") != "blob") continue
            val p = o.optString("path")
            if (p.isBlank() || p == REMOTE_META_NAME) continue
            if (p.contains("/.trash/") || p.endsWith("/.gitkeep")) continue
            if (root.isNotBlank() && !p.startsWith(root.trim('/') + "/")) continue
            out += if (root.isBlank()) p else p.removePrefix(root.trim('/') + "/")
        }
        DevLog.i("GH", "全仓索引 · ${out.size} 个文件")
        out.sorted()
    }

    private fun deleteBySha(rel: String, sha: String, message: String) {
        val o = JSONObject().put("message", message).put("sha", sha).put("branch", branch)
        val req = headers().url(contentsUrl(rel, withRef = false))
            .delete(o.toString().toRequestBody(jsonMedia)).build()
        call(req)
    }

    /** 读原始字节。>1MB 时 content 为空，改走 blob 接口 */
    suspend fun readBytes(rel: String): ByteArray = io {
        val j = getRaw(rel) ?: error("仓库里没有这个文件")
        val content = j.optString("content").replace("\n", "").replace("\r", "")
        if (content.isNotEmpty()) {
            android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        } else {
            val sha = j.optString("sha")
            val req = headers()
                .url("https://api.github.com/repos/$owner/$repo/git/blobs/$sha").get().build()
            val b = call(req) ?: error("读取 blob 失败")
            val c = b.optString("content").replace("\n", "").replace("\r", "")
            android.util.Base64.decode(c, android.util.Base64.DEFAULT)
        }
    }

    suspend fun readText(rel: String): String = String(readBytes(rel), Charsets.UTF_8)

    /** 新建或覆盖：PUT /contents/{path}，带 sha 表示更新（乐观锁） */
    suspend fun put(rel: String, bytes: ByteArray, message: String, oldSha: String? = null): FileItem = io {
        val o = JSONObject()
        o.put("message", message)
        o.put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        o.put("branch", branch)
        if (!oldSha.isNullOrBlank()) o.put("sha", oldSha)
        val req = headers().url(contentsUrl(rel, withRef = false))
            .put(o.toString().toRequestBody(jsonMedia)).build()
        val j = call(req) ?: error("写入失败")
        val content = j.optJSONObject("content") ?: JSONObject()
        DevLog.i("GH", "写入 /$rel · ${bytes.size} B · $message")
        FileItem(
            id = pathOf(rel), name = rel.substringAfterLast('/'),
            parent = rel.substringBeforeLast('/', ""), rel = rel, type = "file",
            size = bytes.size.toLong(),
            sha256 = content.optString("sha", j.optString("sha")),
            mime = mimeOf(rel.substringAfterLast('/')),
        )
    }

    suspend fun createText(name: String, parent: String, text: String = ""): FileItem = io {
        put(joinRel(parent, name), text.toByteArray(), "FileBridge: 新建 $name")
    }

    suspend fun saveText(rel: String, text: String): FileItem = io {
        // currentSha 内部会发一次 GET，必须留在 io 块里
        put(rel, text.toByteArray(), "FileBridge: 编辑 ${rel.substringAfterLast('/')}", currentSha(rel))
    }

    private fun currentSha(rel: String): String? = try {
        getRaw(rel)?.optString("sha")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** GitHub 没有空目录，用 .gitkeep 占位 */
    suspend fun mkdir(name: String, parent: String): FileItem = io {
        put(joinRel(joinRel(parent, name), ".gitkeep"), ByteArray(0), "FileBridge: 新建目录 $name")
    }

    suspend fun importFrom(uri: Uri, name: String, parent: String): FileItem = io {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("读不到所选文件")
        val clean = File(name.replace('\\', '/')).name
        if (clean.isEmpty() || clean == "." || clean == "..") error("文件名不合法")
        put(joinRel(parent, clean), bytes, "FileBridge: 导入 $clean")
    }

    suspend fun rename(rel: String, newName: String): FileItem = io {
        val clean = File(newName.replace('\\', '/')).name
        val dst = joinRel(rel.substringBeforeLast('/', ""), clean)
        val bytes = try { readBytes(rel) } catch (_: Exception) { ByteArray(0) }
        put(dst, bytes, "FileBridge: 改名 $rel → $dst")
        deleteRaw(rel, "FileBridge: 改名清理 $rel")
        DevLog.i("GH", "改名 /$rel → /$dst")
        FileItem(id = pathOf(dst), name = clean, parent = rel.substringBeforeLast('/', ""),
            rel = dst, type = "file", size = bytes.size.toLong(), mime = mimeOf(clean))
    }

    /**
     * 文件夹重命名：GitHub Contents API 没有「目录改名」，逐文件 PUT/DELETE 会产生几十次 commit，
     * 而且中途断网会留下半个目录。这里走 Git Data API：
     *   ① 读整棵 tree（recursive）拿到旧前缀下所有 blob 的 sha
     *   ② 一次 POST /git/trees：新路径复用 sha、旧路径置 null 删除
     *   ③ 一次 commit + PATCH ref
     * 内容一个字节都不重传，整次改名是一个原子 commit，同步脚本会识别成「删旧 + 加新」并回灌沙盒。
     */
    suspend fun renameFolder(rel: String, newName: String): Int = io {
        val clean = File(newName.replace('\\', '/')).name
        require(clean.isNotBlank() && clean != "." && clean != ".." && !clean.contains('/')) {
            "目录名不合法"
        }
        val oldPrefix = pathOf(rel).trim('/')
        val parentPrefix = oldPrefix.substringBeforeLast('/', "")
        val newPrefix = if (parentPrefix.isEmpty()) clean else "$parentPrefix/$clean"
        if (newPrefix == oldPrefix) return@io 0

        val refUrl = "https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch"
        val head = call(headers().url(refUrl).get().build())
            ?.optJSONObject("object")?.optString("sha") ?: error("读不到分支 HEAD")
        val commitObj = call(headers()
            .url("https://api.github.com/repos/$owner/$repo/git/commits/$head").get().build())
            ?: error("读不到 HEAD commit")
        val baseTree = commitObj.optJSONObject("tree")?.optString("sha")
            ?: error("读不到 tree sha")
        val treeResp = call(headers().url(
            "https://api.github.com/repos/$owner/$repo/git/trees/$baseTree?recursive=1")
            .get().build()) ?: error("读不到目录树")
        val arr = treeResp.optJSONArray("tree") ?: error("目录树为空")

        val entries = org.json.JSONArray()
        var moved = 0
        val existing = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "blob") existing += o.optString("path")
        }
        for (p in existing) {
            if (p.startsWith("$newPrefix/") || p == newPrefix) {
                error("目标目录「$clean」已经存在，换一个名字")
            }
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") != "blob") continue
            val p = o.optString("path")
            if (p != oldPrefix && !p.startsWith("$oldPrefix/")) continue
            val suffix = p.removePrefix(oldPrefix)
            val np = newPrefix + suffix
            entries.put(org.json.JSONObject()
                .put("path", np).put("mode", "100644").put("type", "blob")
                .put("sha", o.optString("sha")))
            // 必须是 JSON null（JSONObject.NULL），org.json 的 put(key, null) 会把键删掉
            entries.put(org.json.JSONObject()
                .put("path", p).put("mode", "100644").put("type", "blob")
                .put("sha", org.json.JSONObject.NULL))
            moved++
        }
        if (moved == 0) error("目录里没有文件，可能路径不对：/$rel")

        val newTree = call(headers().url(
            "https://api.github.com/repos/$owner/$repo/git/trees").post(
            org.json.JSONObject().put("base_tree", baseTree).put("tree", entries)
                .toString().toRequestBody(jsonMedia)).build())
            ?.optString("sha") ?: error("创建 tree 失败")
        val newCommit = call(headers().url(
            "https://api.github.com/repos/$owner/$repo/git/commits").post(
            org.json.JSONObject()
                .put("message", "FileBridge: 目录改名 /$rel → /$newPrefix")
                .put("tree", newTree)
                .put("parents", org.json.JSONArray().put(head))
                .toString().toRequestBody(jsonMedia)).build())
            ?.optString("sha") ?: error("创建 commit 失败")
        call(headers().url(refUrl).patch(
            org.json.JSONObject().put("sha", newCommit).put("force", false)
                .toString().toRequestBody(jsonMedia)).build())
        DevLog.i("GH", "目录改名 /$rel → /$newPrefix · $moved 文件 · 单次 commit")
        moved
    }

    /** 统一入口：文件走 Contents API，文件夹走 Git Tree API */
    suspend fun renameAny(rel: String, newName: String, isDir: Boolean) = io {
        if (isDir) renameFolder(rel, newName) else { rename(rel, newName); 1 }
    }

    private fun deleteRaw(rel: String, message: String) {
        val sha = currentSha(rel) ?: return
        val o = JSONObject().put("message", message).put("sha", sha).put("branch", branch)
        val req = headers().url(contentsUrl(rel, withRef = false))
            .delete(o.toString().toRequestBody(jsonMedia)).build()
        call(req)
    }

    // ---------------- 回收站（仓库里的 .trash 目录） ----------------
    suspend fun delete(rel: String) = io {
        getRaw(rel) ?: error("文件不存在")
        val bytes = try { readBytes(rel) } catch (_: Exception) { ByteArray(0) }
        val id = System.currentTimeMillis().toString().takeLast(10)
        val name = rel.substringAfterLast('/')
        put(joinRel(".trash", "${id}_$name"), bytes, "FileBridge: 移入回收站 $name")
        put(joinRel(".trash", "${id}_$name.meta"),
            JSONObject().put("rel", rel).put("name", name).toString().toByteArray(),
            "FileBridge: 回收站元信息 $name")
        deleteRaw(rel, "FileBridge: 移入回收站 $name")
        DevLog.i("GH", "沙盒文件 /$rel 移入回收站 (id=$id)")
    }

    /**
     * 删除整个文件夹：递归收集所有文件，把「路径 + 内容」打包成一条回收站元信息，
     * 再逐个删除（含 .gitkeep 占位）。恢复时从这条记录整体重建，不用翻一堆碎片。
     */
    suspend fun deleteFolder(rel: String): Int = io {
        val found = mutableListOf<Pair<String, ByteArray>>()
        val toDelete = mutableListOf<Pair<String, String>>()   // path -> git blob sha

        fun walk(dir: String) {
            val arr = listRaw(dir) ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.optString("path")       // 仓库内全路径（root 为空时等同相对路径）
                val sha = o.optString("sha")
                if (o.optString("type") == "dir") {
                    walk(if (root.isBlank()) p else p.removePrefix(root.trim('/') + "/"))
                } else {
                    val localRel = if (root.isBlank()) p else p.removePrefix(root.trim('/') + "/")
                    val bytes = try { readBytesSync(localRel) } catch (_: Exception) { ByteArray(0) }
                    found += localRel to bytes
                    toDelete += p to sha
                }
            }
        }

        // listRaw 用 contentsUrl(rel) 已带 root 前缀，返回项的 path 是仓库全路径
        walk(rel)

        val id = System.currentTimeMillis().toString().takeLast(10)
        val name = rel.substringAfterLast('/')
        val meta = JSONObject().put("rel", rel).put("name", name).put("isDir", true)
        val fa = JSONArray()
        for ((r, b) in found) {
            fa.put(JSONObject().put("rel", r)
                .put("content", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)))
        }
        meta.put("files", fa)
        val metaRel = joinRel(".trash", "$id.meta")
        put(metaRel, meta.toString().toByteArray(), "FileBridge: 删除目录 $name（${found.size} 文件）")

        for ((p, sha) in toDelete) {
            // p 是仓库内路径；contentsUrl 会再加一次 root，这里直接拼 URL
            val req = headers().url("$api/$p")
                .delete(JSONObject().put("message", "FileBridge: 删除目录 $name")
                    .put("sha", sha).put("branch", branch)
                    .toString().toRequestBody(jsonMedia)).build()
            call(req)
        }
        DevLog.i("GH", "删除目录 /$rel · ${found.size} 个文件")
        found.size
    }

    /** 同步读，仅供 deleteFolder 在 io{} 内使用 */
    private fun readBytesSync(rel: String): ByteArray {
        val j = getRaw(rel) ?: return ByteArray(0)
        val content = j.optString("content").replace("\n", "").replace("\r", "")
        if (content.isNotEmpty()) return android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        val sha = j.optString("sha")
        val req = headers()
            .url("https://api.github.com/repos/$owner/$repo/git/blobs/$sha").get().build()
        val b = call(req) ?: return ByteArray(0)
        val c = b.optString("content").replace("\n", "").replace("\r", "")
        return android.util.Base64.decode(c, android.util.Base64.DEFAULT)
    }

    suspend fun trash(): List<TrashItem> = io {
        if (token.isBlank()) return@io emptyList()
        val req = headers().url(contentsUrl(joinRel(".trash", ""))).get().build()
        val j = call(req) ?: return@io emptyList()
        val arr = j.optJSONArray("__array") ?: return@io emptyList()
        val out = mutableListOf<TrashItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fileName = o.optString("name")
            if (!fileName.endsWith(".meta")) continue
            val key = fileName.removeSuffix(".meta")
            val meta = try {
                JSONObject(readText(joinRel(".trash", fileName)))
            } catch (_: Exception) {
                JSONObject()
            }
            out += TrashItem(
                id = key,
                name = meta.optString("name", key.substringAfter('_')),
                rel = meta.optString("rel", ""),
                parent = meta.optString("rel").substringBeforeLast('/', ""),
                type = "file",
                deleted_at = key.substringBefore('_').toDoubleOrNull() ?: 0.0,
            )
        }
        out.sortedByDescending { it.deleted_at ?: 0.0 }
    }

    suspend fun restore(key: String) = io {
        val metaName = "$key.meta"
        val meta = JSONObject(readText(joinRel(".trash", metaName)))
        val rel = meta.optString("rel")
        val name = meta.optString("name")
        val fa = meta.optJSONArray("files")
        if (fa != null) {
            // 整目录恢复：逐文件写回
            for (i in 0 until fa.length()) {
                val o = fa.getJSONObject(i)
                val r = o.optString("rel")
                val c = o.optString("content")
                val bytes = android.util.Base64.decode(c, android.util.Base64.DEFAULT)
                put(r, bytes, "FileBridge: 恢复目录 $name")
            }
        } else {
            val tFile = joinRel(".trash", key)
            val bytes = try { readBytes(tFile) } catch (_: Exception) { ByteArray(0) }
            put(rel, bytes, "FileBridge: 恢复 $name")
            deleteRaw(tFile, "FileBridge: 恢复清理 $tFile")
        }
        deleteRaw(joinRel(".trash", metaName), "FileBridge: 恢复清理元信息")
        DevLog.i("GH", "从回收站恢复 /$rel")
    }

    suspend fun purge(key: String) = io {
        deleteRaw(joinRel(".trash", key), "FileBridge: 彻底删除 $key")
        deleteRaw(joinRel(".trash", "$key.meta"), "FileBridge: 彻底删除元信息")
        DevLog.i("GH", "彻底删除 $key")
    }

    /** 下载到 APP 私有目录，用于「打开 / 分享」 */
    suspend fun localFile(rel: String): File = io {
        val bytes = try { readBytes(rel) } catch (_: Exception) { ByteArray(0) }
        val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
        val f = File(dir, rel.substringAfterLast('/'))
        f.writeBytes(bytes)
        f
    }

    private fun joinRel(parent: String, name: String) =
        listOf(parent.trim('/'), name.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
}
