package com.example.filebridge

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 开发者日志：把 APP 运行时的详细状态记录下来，一键导出成 Markdown 供排查问题。
 *
 * 设计要点：
 *  - 内存环形缓冲（本次会话）+ 磁盘 ndjson 追加（跨启动保留），导出时两者合并。
 *  - 所有写盘 / 导出内容都过 sanitize()：Token、预签名 sig、Bearer 一律打码，日志本身不泄密。
 *  - 每条 HTTP 请求单独记 HttpRec，导出时生成「网络请求明细」表，方便定位到底是接口还是界面出错。
 *  - 异常带完整 stackTraceToString，导出时单独成块，不用在聊天里贴截图。
 */
enum class LogLevel { D, I, W, E }

data class HttpRec(
    val method: String,
    val path: String,
    val code: Int,
    val ms: Long,
    val error: String? = null,
)

data class LogEntry(
    val ts: Long,
    val level: LogLevel,
    val tag: String,
    val msg: String,
    val stack: String? = null,
    val http: HttpRec? = null,
)

object DevLog {
    private const val MAX_MEM = 3000
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val MAX_HISTORY_LINES = 600

    @Volatile
    private var appCtx: Context? = null
    private val lock = Any()
    private val mem = ArrayDeque<LogEntry>()
    val sessionStart: Long = System.currentTimeMillis()

    private val tz: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private val SECRET_PATTERNS = listOf(
        Regex("""(sig=)[0-9a-fA-F]{16,}""", RegexOption.IGNORE_CASE),
        Regex("""(token=)[^&\s"']+""", RegexOption.IGNORE_CASE),
        Regex("""(x-api-key["':= ]+)[^\s,&"']+""", RegexOption.IGNORE_CASE),
        Regex("""(Bearer )[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
        Regex("""(gh[pousr]_[A-Za-z0-9]{16,})"""),
        Regex("""(api[_-]?key["':= ]+)[^\s,&"']+""", RegexOption.IGNORE_CASE),
    )

    /** 脱敏：密钥 / 签名 / 凭据一律替换，导出文件可以放心发出去 */
    fun sanitize(input: String): String {
        var s = input
        for (p in SECRET_PATTERNS) {
            s = p.replace(s) { m ->
                val v = m.groupValues
                if (v.size >= 3) v[1] + "***" else "***"
            }
        }
        return s
    }

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        i("APP", "DevLog 初始化 · 新会话开始")
        i("DEVICE", deviceInfo())
    }

    fun deviceInfo(): String = buildString {
        append("制造商=").append(Build.MANUFACTURER)
        append(" 型号=").append(Build.MODEL)
        append(" Android=").append(Build.VERSION.RELEASE)
        append(" SDK=").append(Build.VERSION.SDK_INT)
        append(" 语言=").append(Locale.getDefault().toLanguageTag())
        append(" 时区=").append(tz.id)
        append(" ABI=").append(Build.SUPPORTED_ABIS.joinToString(","))
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = write(LogLevel.D, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = write(LogLevel.I, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = write(LogLevel.W, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write(LogLevel.E, tag, msg, t)

    fun http(method: String, path: String, code: Int, ms: Long, error: String?) {
        val err = error?.let { sanitize(it) }
        val rec = HttpRec(method, sanitize(path), code, ms, err)
        val level = if (code in 200..299) LogLevel.I else LogLevel.W
        val msg = "$method ${rec.path} → $code (${ms}ms)" + (err?.let { " · $it" } ?: "")
        write(level, "HTTP", msg, null, rec)
    }

    private fun write(
        level: LogLevel,
        tag: String,
        msg: String,
        t: Throwable?,
        httpRec: HttpRec? = null,
    ) {
        val safeMsg = sanitize(msg)
        val stack = t?.stackTraceToString()?.let { sanitize(it) }
        val entry = LogEntry(System.currentTimeMillis(), level, tag, safeMsg, stack, httpRec)
        if (stack != null) {
            // 异常正文也单独记一条，方便按级别筛
        }
        synchronized(lock) {
            mem.addLast(entry)
            while (mem.size > MAX_MEM) mem.removeFirst()
        }
        appendToDisk(entry)
    }

    private fun logFile(): File? {
        val ctx = appCtx ?: return null
        val dir = File(ctx.filesDir, "devlog").apply { mkdirs() }
        val f = File(dir, "devlog.ndjson")
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            val bak = File(dir, "devlog.1.ndjson")
            if (bak.exists()) bak.delete()
            f.renameTo(bak)
        }
        return f
    }

    private fun appendToDisk(e: LogEntry) {
        try {
            val f = logFile() ?: return
            val o = JSONObject()
            o.put("ts", e.ts)
            o.put("lvl", e.level.name)
            o.put("tag", e.tag)
            o.put("msg", e.msg)
            o.put("stack", e.stack ?: "")
            e.http?.let { h ->
                val j = JSONObject()
                j.put("method", h.method)
                j.put("path", h.path)
                j.put("code", h.code)
                j.put("ms", h.ms)
                j.put("error", h.error ?: "")
                o.put("http", j)
            }
            f.appendText(o.toString() + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // 日志系统自身不能把业务搞崩
        }
    }

    private fun readHistory(): List<LogEntry> {
        val f = logFile() ?: return emptyList()
        if (!f.exists()) return emptyList()
        val out = mutableListOf<LogEntry>()
        try {
            val lines = f.readLines(Charsets.UTF_8)
            for (line in lines.takeLast(MAX_HISTORY_LINES)) {
                if (line.isBlank()) continue
                try {
                    val o = JSONObject(line)
                    val h = o.optJSONObject("http")?.let {
                        HttpRec(
                            it.optString("method"), it.optString("path"),
                            it.optInt("code"), it.optLong("ms"),
                            it.optString("error").takeIf { x -> x.isNotEmpty() },
                        )
                    }
                    out += LogEntry(
                        o.optLong("ts"),
                        runCatching { LogLevel.valueOf(o.optString("lvl")) }.getOrDefault(LogLevel.D),
                        o.optString("tag"), o.optString("msg"),
                        o.optString("stack").takeIf { it.isNotEmpty() }, h,
                    )
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    data class Summary(
        val total: Int,
        val errors: Int,
        val warns: Int,
        val httpCount: Int,
        val httpFailed: Int,
        val avgMs: Long,
        val last: List<String>,
        val sizeBytes: Long,
    )

    fun summary(): Summary {
        val snapshot = synchronized(lock) { mem.toList() }
        val http = snapshot.mapNotNull { it.http }
        val hist = readHistory()
        val size = logFile()?.length() ?: 0L
        return Summary(
            total = snapshot.size,
            errors = snapshot.count { it.level == LogLevel.E },
            warns = snapshot.count { it.level == LogLevel.W },
            httpCount = http.size,
            httpFailed = http.count { it.code !in 200..299 || it.error != null },
            avgMs = if (http.isEmpty()) 0 else http.map { it.ms }.average().toLong(),
            last = snapshot.takeLast(8).reversed().map { "${it.level} ${it.tag} ${it.msg}" },
            sizeBytes = size + (if (hist.isNotEmpty()) 1 else 0),
        )
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = tz }.format(Date(ts))

    private fun fmtFull(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { timeZone = tz }.format(Date(ts))

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = tz }.format(Date())

    private fun mdEscape(s: String): String =
        s.replace("|", "\\|").replace("\r", " ").replace("\n", " ⏎ ")

    /** 版本信息从安装包本身读取，不再硬编码（之前一直写死 0.1，害排查时误判版本） */
    private fun appVersion(ctx: Context): String = try {
        val pm = ctx.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0)
        }
        val name = pi.versionName ?: "未知"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "$name (versionCode $code)"
    } catch (e: Exception) {
        "读取失败：${e.message ?: e.toString()}"
    }

    /** 生成 Markdown 开发者日志 */
    suspend fun exportMarkdown(ctx: Context): File = withContext(Dispatchers.IO) {
        init(ctx)
        val snapshot = synchronized(lock) { mem.toList() }
        val history = readHistory()
        // 合并：历史 + 本次会话，按时间排序去重（同一条可能既在内存又在磁盘）
        val merged = (history + snapshot)
            .distinctBy { "${it.ts}|${it.tag}|${it.msg}" }
            .sortedBy { it.ts }
        val s = summary()
        val cfg = ctx.getSharedPreferences("filebridge", Context.MODE_PRIVATE)
        val base = cfg.getString("base", "(未设置)") ?: "(未设置)"
        val appVersion = appVersion(ctx)

        val b = StringBuilder()
        b.appendLine("# FileBridge 开发者日志")
        b.appendLine()
        b.appendLine("> 由 APP 内「开发者日志 · 一键导出」生成，用于排查界面、网络与文件操作问题。")
        b.appendLine("> 所有密钥与预签名参数已自动打码，可安全转发。")
        b.appendLine()
        b.appendLine("## 1. 导出信息")
        b.appendLine()
        b.appendLine("| 项 | 值 |")
        b.appendLine("| --- | --- |")
        b.appendLine("| 导出时间 | ${fmtFull(System.currentTimeMillis())} |")
        b.appendLine("| 会话开始 | ${fmtFull(sessionStart)} |")
        b.appendLine("| APP 版本 | ${mdEscape(appVersion)} |")
        b.appendLine("| 设备 | ${mdEscape(deviceInfo())} |")
        b.appendLine("| 服务地址 | `${mdEscape(sanitize(base))}` |")
        b.appendLine("| API Token | `[REDACTED]` |")
        b.appendLine("| 日志文件 | `filesDir/devlog/devlog.ndjson` (${s.sizeBytes} B) |")
        b.appendLine()
        b.appendLine("## 2. 运行摘要")
        b.appendLine()
        b.appendLine("| 指标 | 数值 |")
        b.appendLine("| --- | --- |")
        b.appendLine("| 日志总条数（含历史） | ${merged.size} |")
        b.appendLine("| 本次会话条数 | ${s.total} |")
        b.appendLine("| ERROR | ${s.errors} |")
        b.appendLine("| WARN | ${s.warns} |")
        b.appendLine("| HTTP 请求 | ${s.httpCount} |")
        b.appendLine("| HTTP 失败 | ${s.httpFailed} |")
        b.appendLine("| 平均耗时 | ${s.avgMs} ms |")
        b.appendLine()
        b.appendLine("## 3. 最近状态")
        b.appendLine()
        if (s.last.isEmpty()) b.appendLine("_(暂无)_")
        else s.last.forEach { b.appendLine("- `${mdEscape(it)}`") }
        b.appendLine()
        b.appendLine("## 4. 完整时间线")
        b.appendLine()
        b.appendLine("| 时间 | 级别 | 模块 | 内容 |")
        b.appendLine("| --- | --- | --- | --- |")
        for (e in merged) {
            b.appendLine("| ${fmt(e.ts)} | ${e.level} | ${mdEscape(e.tag)} | ${mdEscape(e.msg)} |")
        }
        b.appendLine()
        b.appendLine("## 5. 网络请求明细")
        b.appendLine()
        val httpRows = merged.mapNotNull { it.http }
        if (httpRows.isEmpty()) {
            b.appendLine("_(本次未产生 HTTP 请求)_")
        } else {
            b.appendLine("| 方法 | 路径 | 状态 | 耗时 | 错误 |")
            b.appendLine("| --- | --- | --- | --- | --- |")
            for (h in httpRows) {
                b.appendLine("| ${h.method} | `${mdEscape(h.path)}` | ${h.code} | ${h.ms} ms | ${mdEscape(h.error ?: "-")} |")
            }
        }
        b.appendLine()
        b.appendLine("## 6. 异常堆栈")
        b.appendLine()
        val stacks = merged.filter { !it.stack.isNullOrEmpty() }
        if (stacks.isEmpty()) {
            b.appendLine("_(无异常堆栈)_")
        } else {
            stacks.forEachIndexed { i, e ->
                b.appendLine("### ${i + 1}. ${fmt(e.ts)} · ${mdEscape(e.tag)} · ${mdEscape(e.msg)}")
                b.appendLine()
                b.appendLine("```text")
                b.appendLine(e.stack)
                b.appendLine("```")
                b.appendLine()
            }
        }
        b.appendLine("---")
        b.appendLine()
        b.appendLine("_FileBridge DevLog · 时区 Asia/Shanghai · 敏感信息已脱敏_")

        val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
        val out = File(dir, "FileBridge_DevLog_${stamp()}.md")
        out.writeText(b.toString(), Charsets.UTF_8)
        i("LOG", "已导出开发者日志 ${out.name} (${out.length()} B, ${merged.size} 条)")
        out
    }

    /** 生成分享 / 保存用的 Intent（content:// + 临时读授权） */
    fun shareIntent(ctx: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, "FileBridge 开发者日志：${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(ctx.contentResolver, "devlog", uri)
        }
    }

    fun clear() {
        synchronized(lock) { mem.clear() }
        try {
            logFile()?.delete()
            val dir = appCtx?.let { File(it.filesDir, "devlog") }
            dir?.let { File(it, "devlog.1.ndjson") }?.delete()
        } catch (_: Exception) {
        }
        i("LOG", "日志已清空")
    }
}
