#!/usr/bin/env python3
"""
推送前静态自检（不依赖 Android SDK / Kotlin 编译器）：

  1. 必需文件齐全，旧的中转脚本/目录确实已删
  2. 仓库里绝不能出现 Token / PAT / 签名私钥
  3. 两个仓库分离：代码仓 public，持久区镜像仓 private，APP 指向后者根路径
  4. 全量双向同步脚本的三向比较 / 断点续传 / 空目录占位 / 限速退避
  5. v1.4 的线程安全修复没有回归
  6. 生成 VERIFY_STATIC.md

用法：python3 scripts/check_sources.py
"""
from __future__ import annotations

import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "VERIFY_STATIC.md"

REQUIRED = [
    "README.md", "VERIFY.md", "VERIFY_STATIC.md", ".gitignore", "LICENSE",
    ".github/workflows/build-apk.yml", ".github/RELEASE_BODY.md",
    "scripts/sync_persistent.py", "scripts/verify.py", "scripts/check_sources.py",
    "scripts/start_server.sh", "scripts/sync_workspace.py",
    "server/app.py", "server/__init__.py", "web/index.html",
    "android/settings.gradle.kts", "android/build.gradle.kts",
    "android/gradle.properties", "android/app/build.gradle.kts",
    "android/app/proguard-rules.pro",
    "android/app/src/main/AndroidManifest.xml",
    "android/app/src/main/res/xml/file_paths.xml",
    "android/app/src/main/res/values/styles.xml",
    "android/app/src/main/java/com/example/filebridge/GitHubStore.kt",
    "android/app/src/main/java/com/example/filebridge/Models.kt",
    "android/app/src/main/java/com/example/filebridge/Theme.kt",
    "android/app/src/main/java/com/example/filebridge/DevLog.kt",
    "android/app/src/main/java/com/example/filebridge/MainActivity.kt",
]

REMOVED = [
    "android/app/src/main/java/com/example/filebridge/LocalStore.kt",
    "android/app/src/main/java/com/example/filebridge/Api.kt",
    "android/app/src/main/java/com/example/filebridge/ApiClient.kt",
    "android/app/src/main/java/com/example/filebridge/FileRepository.kt",
    "scripts/sync_to_github.py", "scripts/pull_from_github.py",
]

SKIP_DIRS = {".git", ".gh", "__pycache__", "build", ".gradle", ".idea"}
SKIP_NAMES = {"server.log", "VERIFY_STATIC.md", "filebridge.jks"}

results: list[tuple[str, str, str]] = []


def check(name: str, fn):
    try:
        d = fn()
        results.append((name, "PASS", str(d)))
        print(f"[PASS] {name}: {d}")
    except Exception as e:
        results.append((name, "FAIL", f"{type(e).__name__}: {e}"))
        print(f"[FAIL] {name}: {type(e).__name__}: {e}")


def all_files() -> list[Path]:
    out = []
    for p in ROOT.rglob("*"):
        if not p.is_file():
            continue
        if any(part in SKIP_DIRS for part in p.parts) or p.name in SKIP_NAMES:
            continue
        out.append(p)
    return out


def code_only(s: str) -> str:
    """去掉注释，避免把「说明里提到旧依赖 / 被规避的审美」误判成代码残留。"""
    out, in_block = [], False
    for line in s.splitlines():
        t = line.strip()
        if in_block:
            if "*/" in t:
                in_block = False
            continue
        if t.startswith("/*"):
            if "*/" not in t:
                in_block = True
            continue
        if t.startswith("*") or t.startswith("//"):
            continue
        i = line.find("//")
        out.append(line[:i] if i >= 0 else line)
    return "\n".join(out)


def t_required():
    missing = [f for f in REQUIRED if not (ROOT / f).is_file()]
    assert not missing, f"缺失文件: {missing}"
    left = [f for f in REMOVED if (ROOT / f).exists()]
    assert not left, f"旧残留未清: {left}"
    assert not (ROOT / "sandbox-files").exists(), "代码仓里不应再有 sandbox-files 中转目录"
    return f"{len(REQUIRED)} 个必需文件齐全，6 个旧残留已删"


def t_no_secrets():
    pats = [re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"),
            re.compile(r"github_pat_[A-Za-z0-9_]{20,}")]
    real = os.getenv("FILEBRIDGE_GH_TOKEN", "").strip()
    tf = Path("/data/workspace/.gh/token")
    if not real and tf.exists():
        real = tf.read_text().strip()
    hits = []
    for p in all_files():
        try:
            text = p.read_text("utf-8", errors="ignore")
        except Exception:
            continue
        for pat in pats:
            if pat.search(text):
                hits.append(f"{p.relative_to(ROOT)} 命中 {pat.pattern}")
        if real and real in text:
            hits.append(f"{p.relative_to(ROOT)} 含真实 Token")
        if p.suffix in (".jks", ".keystore", ".pk8", ".p12"):
            hits.append(f"{p.relative_to(ROOT)} 含签名私钥")
    assert not hits, "发现凭据: " + "; ".join(hits)
    return f"扫描 {len(all_files())} 个文件，无 Token / PAT / 私钥泄露"


def t_two_repos():
    """核心：代码仓与数据镜像仓必须分开，APP 指向私有镜像仓根路径。"""
    m = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ['GH_REPO = "filebridge-persistent"', 'GH_ROOT = ""',
               'GH_OWNER = "zjh1934"', 'GitHubStore(ctx, GH_OWNER, GH_REPO, token, GH_ROOT)']:
        assert kw in m, f"MainActivity 未指向私有镜像仓: {kw}"
    assert "filebridge-android" not in code_only(m), "界面仍引用代码仓当数据源"
    g = (ROOT / "android/app/src/main/java/com/example/filebridge/GitHubStore.kt").read_text("utf-8")
    assert "private fun contentsUrl(" in g, "缺少仓库根路径 URL 处理"
    assert "if (p.isBlank()) api else" in g, "contents/ 斜杠问题未处理"
    d = (ROOT / "android/app/src/main/java/com/example/filebridge/DevLog.kt").read_text("utf-8")
    assert 'GH_MIRROR_REPO = "filebridge-persistent"' in d, "日志未标注镜像仓"
    s = (ROOT / "scripts/sync_persistent.py").read_text("utf-8")
    assert 'REPO = os.getenv("FB_MIRROR_REPO", "filebridge-persistent")' in s, "同步脚本目标仓不对"
    assert "/data/user_persistent_data" in s, "同步脚本根目录不是持久区"
    return "代码仓/私有镜像仓分离，APP 与同步脚本都指向持久区全量镜像根"


def t_persistent_sync():
    """全量双向同步的关键机制不能少。"""
    s = code_only((ROOT / "scripts/sync_persistent.py").read_text("utf-8"))
    for kw in ["def remote_snapshot(", "def local_scan(", "def decide(",
               "def blob_sha(", "def flush(", "def pull_file(", "def backup_local(",
               "base_tree", "BATCH = 300", "SLEEP_PER_BLOB", "rate-limit",
               "GITKEEP", ".filebridge_sync_state.json", "EXCLUDE_TOP",
               "truncated", "safe_rel(", "retry"]:
        assert kw in s, f"sync_persistent.py 缺少 {kw}"
    assert "ghp_" not in s and "FILEBRIDGE_GH_TOKEN" in s, "令牌不应硬编码"
    assert "git/trees" in s and "/git/blobs" in s, "未走 Git Data API 流式同步"
    # CI runner 上没有 /data/user_persistent_data，只有本地沙盒才有，故做条件检查
    persist = Path("/data/user_persistent_data/filebridge/sync_persistent.py")
    if persist.exists():
        assert persist.read_bytes() == (ROOT / "scripts/sync_persistent.py").read_bytes(), \
            "两份同步脚本不一致"
        dual = "，持久区副本与代码仓一致"
    else:
        dual = "（CI 环境无持久区，副本检查跳过）"
    return "三向比较/断点续传/空目录占位/限速退避齐备" + dual


def t_sandbox_default():
    m = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ["先连上沙盒", "保存并查看文件", "FileBridgeTheme",
               "跨对话持久区", "私有仓库"]:
        assert kw in m, f"MainActivity 缺少 {kw}"
    assert "http://10.0.2.2" not in m, "仍残留模拟器默认地址"
    return "默认私有镜像仓、首页连接卡引导、文案说明全量持久区"


def t_ui_design():
    t = code_only((ROOT / "android/app/src/main/java/com/example/filebridge/Theme.kt").read_text("utf-8"))
    for kw in ["object FB", "val Ink", "val Slate", "val Paper", "val Accent",
               "val ShapeSmall", "val ShapeCard", "val ShapeSheet", "val ShapePill",
               "lightColorScheme", "Typography(", "Shapes(", "fun FileBridgeTheme"]:
        assert kw in t, f"Theme.kt 缺少 {kw}"
    assert t.count("Color(0xFF") >= 6, "命名色 token 不足 6 个"
    m = code_only((ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8"))
    for kw in ["ModalBottomSheet", "ActionRow", "FileCard", "FolderRow", "AssistChip",
               "ExtendedFloatingActionButton", "StatBlock", "fun badge(", "fun friendlyError(",
               "这个目录还是空的", "没能读到沙盒文件", "重新试一次", "保存到沙盒",
               "移到回收站", "全部文件"]:
        assert kw in m, f"MainActivity 缺少 {kw}"
    for bad in ["ALL-CAPS", "EYEBROW", "terracotta", "gradient"]:
        assert bad not in m and bad not in t, f"出现默认审美 {bad}"
    return "设计 token 集中、底部动作面板/路径面包屑/空态错误态齐备"


def t_gh_backend():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/GitHubStore.kt").read_text("utf-8")
    for kw in ["class GitHubStore", "fun list(", "fun readBytes(", "fun put(",
               "fun delete(", "fun trash(", "fun restore(", "fun purge(",
               "fun mkdir(", "fun importFrom(", "fun localFile(",
               "repos/$owner/$repo/contents", "DevLog.http("]:
        assert kw in s, f"GitHubStore.kt 缺少 {kw}"
    return "Contents API 列/读/写/删/回收站/导入/下载齐备"


def t_features_intact():
    m = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ["gh.list(", "gh.readText(", "gh.saveText(", "gh.put(", "gh.importFrom(",
               "gh.createText(", "gh.mkdir(", "gh.rename(", "gh.delete(",
               "gh.trash(", "gh.restore(", "gh.purge(", "gh.localFile(",
               "viewIntent(", "shareIntent(", "DevLog.exportMarkdown("]:
        assert kw in m, f"功能入口丢失: {kw}"
    return "列表/读/编辑/覆盖/导入/新建/改名/删除/回收站/打开/分享/日志全部保留"


def t_thread_safety():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/GitHubStore.kt").read_text("utf-8")
    for kw in ["import kotlinx.coroutines.Dispatchers", "import kotlinx.coroutines.withContext",
               "import kotlinx.coroutines.CoroutineScope", "import android.os.Looper",
               "private suspend fun <T> io(", "withContext(Dispatchers.IO, block)",
               "Looper.getMainLooper()?.thread == Thread.currentThread()",
               "client.newCall(req).execute()"]:
        assert kw in s, f"GitHubStore.kt 缺少线程安全要件: {kw}"
    bad = re.findall(r"^    fun (\w+)\(", s, re.M)
    assert not bad, f"仍有非 suspend 的非私有方法: {bad}"
    for name in ["list", "put", "readBytes", "readText", "saveText", "createText",
                 "mkdir", "importFrom", "rename", "delete", "trash", "restore",
                 "purge", "localFile"]:
        assert re.search(rf"suspend fun {name}\(", s), f"方法 {name} 未标记 suspend"
    assert s.count("= io {") >= 10, "io{} 包裹数量不足"
    assert s.count(".execute()") == 1, "存在未被 io{} 收拢的同步网络调用"
    m = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    assert "runBlocking" not in m, "不允许把网络强行拉回主线程"
    assert "fun msgOf(" in m and "NetworkOnMainThread" in m, "异常文案兜底缺失"
    return "全部网络入口 suspend + Dispatchers.IO，call() 有主线程断言"


def t_log_accuracy():
    d = (ROOT / "android/app/src/main/java/com/example/filebridge/DevLog.kt").read_text("utf-8")
    assert "0.1 (versionCode 1)" not in d, "版本号仍被硬编码"
    for kw in ["fun appVersion(", "PackageInfoFlags", "longVersionCode",
               "镜像仓库", "object DevLog", "fun sanitize", "fun exportMarkdown",
               "fun http(", "stackTraceToString", "## 6. 异常堆栈"]:
        assert kw in d, f"DevLog.kt 缺少 {kw}"
    assert "服务地址" not in d, "旧网关地址字段未清"
    m = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ["DevLog.init(", "导出 Markdown", "另存为…", "DevLog.clear()"]:
        assert kw in m, f"MainActivity 缺少 {kw}"
    return "版本号动态读取、镜像仓标注、脱敏/MD 导出齐备"


def t_fixed_signing():
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    gradle = (ROOT / "android/app/build.gradle.kts").read_text("utf-8")
    for kw in ["secrets.FILEBRIDGE_KEYSTORE_B64", "base64 -d > app/filebridge.jks",
               "assembleRelease", "verify --verbose", "--print-certs"]:
        assert kw in wf, f"workflow 缺少 {kw}"
    for kw in ['create("fixed")', 'file("filebridge.jks")', "enableV2Signing = true",
               'versionName = "1.5"', "versionCode = 6",
               "isMinifyEnabled = true", "isShrinkResources = true"]:
        assert kw in gradle, f"build.gradle.kts 缺少 {kw}"
    assert not (ROOT / "android/app/filebridge.jks").exists(), "私钥不应在仓库里"
    assert "*.jks" in (ROOT / ".gitignore").read_text("utf-8")
    return "固定证书来自 Secret、release 签名 + R8、CI 校验、私钥不入库"


def t_version_rule():
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    body = (ROOT / ".github/RELEASE_BODY.md").read_text("utf-8")
    for kw in ['APP_VERSION: "1.5"', 'APK_NAME: "FileBridge-v1.5.apk"',
               'RELEASE_TAG: "v1.5"', "Source self-check"]:
        assert kw in wf, f"workflow 缺少 {kw}"
    for kw in ["FileBridge-v1.5.apk", "filebridge-persistent", "两个仓库",
               "跨对话持久区", "直接覆盖"]:
        assert kw in body, f"RELEASE_BODY 缺少 {kw}"
    return "v1.5 包名/tag/版本规则固定，可覆盖 v1.1～v1.4"


def t_workflow():
    s = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    try:
        import yaml  # type: ignore
        doc = yaml.safe_load(s)
        assert isinstance(doc, dict) and "build" in doc.get("jobs", {}), "workflow 结构不对"
        assert doc["jobs"]["build"]["runs-on"] == "ubuntu-latest", "runner 不对"
        parsed = "PyYAML 解析通过"
    except ImportError:
        parsed = "PyYAML 未安装，改为关键字校验"
    for kw in ["contents: write", "RELEASE_BODY.md", "actions/checkout@v4"]:
        assert kw in s, f"workflow 缺少 {kw}"
    assert "sandbox-files/**" not in s, "仍残留旧中转目录的 paths-ignore"
    return parsed + "；产物与 Release 配置正确"


def t_readme():
    s = (ROOT / "README.md").read_text("utf-8")
    for kw in ["跨对话持久区", "zjh1934/filebridge-persistent",
               "私有", "两个仓库", "sync_persistent.py", "v1.5", "三向比较",
               "NetworkOnMainThreadException", "隐私边界", "固定签名"]:
        assert kw in s, f"README 缺少 {kw}"
    return "README 已同步 v1.5：两仓分离、全量镜像、同步机制与边界"


def main():
    for name, fn in [
        ("必需文件与旧残留", t_required), ("无凭据/私钥泄露", t_no_secrets),
        ("两仓分离与镜像根", t_two_repos), ("全量双向同步脚本", t_persistent_sync),
        ("默认持久区镜像", t_sandbox_default), ("v1.3 UI 设计系统", t_ui_design),
        ("GitHub 中继后端", t_gh_backend), ("核心功能未改坏", t_features_intact),
        ("网络线程安全", t_thread_safety), ("日志准确性", t_log_accuracy),
        ("固定签名与 R8", t_fixed_signing), ("v1.5 版本规则", t_version_rule),
        ("CI workflow 校验", t_workflow), ("README 文档校验", t_readme),
    ]:
        check(name, fn)

    passed = sum(1 for _, s, _ in results if s == "PASS")
    total = len(results)
    now = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S CST")
    lines = [
        "# FileBridge 源码静态自检", "",
        f"- 执行时间：{now}", f"- 结果：**{passed}/{total} 通过**",
        "- 说明：沙盒内无 Android SDK，此处为**静态自检**；Kotlin 实际编译与签名校验由 GitHub Actions 完成，结果以 Release 产物为准。", "",
        "| 检查项 | 结果 | 说明 |", "| --- | --- | --- |",
    ]
    for n, s, d in results:
        lines.append(f"| {n} | {s} | {d} |")
    lines += ["", "> 本文件由 `scripts/check_sources.py` 生成。", ""]
    OUT.write_text("\n".join(lines), "utf-8")
    print(f"\n{passed}/{total} PASS -> {OUT}")
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
