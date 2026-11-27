#!/usr/bin/env python3
"""
推送前静态自检（不依赖 Android SDK / Kotlin 编译器）：

  1. 必需文件齐全，且 1.1 残留文件确实已删
  2. 仓库里绝不能出现 Token / PAT / 签名私钥
  3. 单一「沙盒文件」模式 + GitHub 中继链路完整
  4. v1.3 设计系统（颜色/字阶/形状 token）与新界面组件到位
  5. 生成 VERIFY_STATIC.md

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
    "README.md", "VERIFY.md", ".gitignore",
    ".github/workflows/build-apk.yml", ".github/RELEASE_BODY.md",
    "scripts/sync_to_github.py", "scripts/pull_from_github.py",
    "scripts/verify.py", "scripts/check_sources.py",
    "scripts/start_server.sh", "scripts/sync_workspace.py",
    "server/app.py", "server/__init__.py", "web/index.html",
    "android/settings.gradle.kts", "android/build.gradle.kts",
    "android/gradle.properties", "android/app/build.gradle.kts",
    "android/app/src/main/AndroidManifest.xml",
    "android/app/src/main/res/xml/file_paths.xml",
    "android/app/src/main/res/values/styles.xml",
    "android/app/src/main/java/com/example/filebridge/GitHubStore.kt",
    "android/app/src/main/java/com/example/filebridge/Models.kt",
    "android/app/src/main/java/com/example/filebridge/Theme.kt",
    "LICENSE",
    "android/app/src/main/java/com/example/filebridge/DevLog.kt",
    "android/app/src/main/java/com/example/filebridge/MainActivity.kt",
]

# v1.3 明确删除的 1.1 / 1.2 残留
REMOVED = [
    "android/app/src/main/java/com/example/filebridge/LocalStore.kt",
    "android/app/src/main/java/com/example/filebridge/Api.kt",
    "android/app/src/main/java/com/example/filebridge/ApiClient.kt",
    "android/app/src/main/java/com/example/filebridge/FileRepository.kt",
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
    return f"{len(REQUIRED)} 个必需文件齐全"


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


def t_slim():
    left = [f for f in REMOVED if (ROOT / f).exists()]
    assert not left, f"1.1 残留未清: {left}"
    gradle = code_only((ROOT / "android/app/build.gradle.kts").read_text("utf-8"))
    deps = "\n".join(l for l in gradle.splitlines() if "implementation(" in l)
    for kw in ["retrofit", "converter-gson", "lifecycle-viewmodel", "ui-tooling-preview"]:
        assert kw not in deps, f"build.gradle.kts 仍含依赖 {kw}"
    for kw in ["com.squareup.okhttp3:okhttp", "androidx.compose.material3:material3",
               "kotlinx-coroutines-android"]:
        assert kw in deps, f"build.gradle.kts 缺少必要依赖 {kw}"
    main = code_only((ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8"))
    for kw in ["LocalStore", "FileRepository", "ApiClient", "mode ==", "云网关"]:
        assert kw not in main, f"MainActivity 仍引用旧模式 {kw}"
    models = code_only((ROOT / "android/app/src/main/java/com/example/filebridge/Models.kt").read_text("utf-8"))
    for kw in ["data class FileItem", "data class ListResp", "data class TrashItem"]:
        assert kw in models, f"Models.kt 缺少 {kw}"
    assert "retrofit" not in models.lower(), "Models.kt 不应引入 Retrofit"
    return "4 个残留文件已删、冗余依赖已清、数据模型独立保留、界面只剩单一沙盒模式"


def t_sandbox_default():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ['private const val GH_OWNER = "zjh1934"',
               'private const val GH_REPO = "filebridge-android"',
               'private const val GH_ROOT = "sandbox-files"',
               "GitHubStore(ctx, GH_OWNER, GH_REPO, token, GH_ROOT)",
               "先连上沙盒", "保存并查看文件", "FileBridgeTheme"]:
        assert kw in s, f"MainActivity 缺少 {kw}"
    assert "http://10.0.2.2" not in s, "仍残留模拟器默认地址"
    return "默认沙盒文件、仓库/目录预置、首页连接卡引导"


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
    # frontend-design 规约明令避免的默认审美
    for bad in ["ALL-CAPS", "EYEBROW", "奶油", "terracotta", "gradient"]:
        assert bad not in m and bad not in t, f"出现默认审美 {bad}"
    return "设计 token 集中、底部动作面板/路径面包屑/空态与错误态齐备，避开模板化审美"


def t_gh_backend():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/GitHubStore.kt").read_text("utf-8")
    for kw in ["class GitHubStore", "fun list(", "fun readBytes(", "fun put(",
               "fun delete(", "fun trash(", "fun restore(", "fun purge(",
               "fun mkdir(", "fun importFrom(", "fun localFile(",
               "repos/$owner/$repo/contents", "DevLog.http("]:
        assert kw in s, f"GitHubStore.kt 缺少 {kw}"
    return "Contents API 列/读/写/删/回收站/导入/下载齐备（v1.3 未改动）"


def t_features_intact():
    """功能不能改坏：所有核心操作入口都还在"""
    m = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ["gh.list(", "gh.readText(", "gh.saveText(", "gh.put(", "gh.importFrom(",
               "gh.createText(", "gh.mkdir(", "gh.rename(", "gh.delete(",
               "gh.trash(", "gh.restore(", "gh.purge(", "gh.localFile(",
               "viewIntent(", "shareIntent(", "DevLog.exportMarkdown("]:
        assert kw in m, f"功能入口丢失: {kw}"
    return "列表/读/编辑/覆盖/导入/新建/改名/删除/回收站/打开/分享/日志全部保留"


def t_relay_scripts():
    a = (ROOT / "scripts/sync_to_github.py").read_text("utf-8")
    b = (ROOT / "scripts/pull_from_github.py").read_text("utf-8")
    for kw in ["[skip ci]", "FILEBRIDGE_GH_TOKEN", "sandbox-files", "repos/{OWNER}/{REPO}/contents"]:
        assert kw in a, f"sync_to_github.py 缺少 {kw}"
    for kw in ["FILEBRIDGE_GH_TOKEN", "git/blobs", "sandbox-files"]:
        assert kw in b, f"pull_from_github.py 缺少 {kw}"
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    assert "paths-ignore" in wf and "sandbox-files/**" in wf, "CI 未忽略中转目录"
    return "双向同步脚本 + CI paths-ignore 齐备"


def t_devlog():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/DevLog.kt").read_text("utf-8")
    for kw in ["object DevLog", "fun sanitize", "fun exportMarkdown", "fun shareIntent",
               "fun http(", "stackTraceToString", "devlog.ndjson", "## 6. 异常堆栈"]:
        assert kw in s, f"DevLog.kt 缺少 {kw}"
    m = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ["DevLog.init(", "导出 Markdown", "另存为…", "DevLog.clear()"]:
        assert kw in m, f"MainActivity 缺少 {kw}"
    assert "function exportDevLog" in (ROOT / "web/index.html").read_text("utf-8")
    return "日志采集/脱敏/MD 导出/分享齐备，安卓与 Web 都已接入"


def t_fixed_signing():
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    gradle = (ROOT / "android/app/build.gradle.kts").read_text("utf-8")
    for kw in ["secrets.FILEBRIDGE_KEYSTORE_B64", "base64 -d > app/filebridge.jks",
               "assembleRelease", "verify --verbose", "--print-certs"]:
        assert kw in wf, f"workflow 缺少 {kw}"
    for kw in ['create("fixed")', 'file("filebridge.jks")', "enableV2Signing = true",
               'versionName = "1.3"', "versionCode = 4"]:
        assert kw in gradle, f"build.gradle.kts 缺少 {kw}"
    assert not (ROOT / "android/app/filebridge.jks").exists(), "私钥不应在仓库里"
    assert "*.jks" in (ROOT / ".gitignore").read_text("utf-8")
    return "固定证书来自 Secret、release 签名、CI 校验、私钥不入库"


def t_version_rule():
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    body = (ROOT / ".github/RELEASE_BODY.md").read_text("utf-8")
    for kw in ['APP_VERSION: "1.3"', 'APK_NAME: "FileBridge-v1.3.apk"',
               'RELEASE_TAG: "v1.3"', "assembleRelease", "app-release.apk"]:
        assert kw in wf, f"workflow 缺少 {kw}"
    for kw in ["FileBridge-v1.3.apk", "清干净了", "frontend-design", "功能一个没改坏", "覆盖安装"]:
        assert kw in body, f"RELEASE_BODY 缺少 {kw}"
    return "v1.3 包名/tag/版本规则固定，可覆盖 v1.1/v1.2"


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
    return parsed + "；产物路径与 Release 配置正确"


def t_readme():
    s = (ROOT / "README.md").read_text("utf-8")
    for kw in ["查看和编辑沙盒里的文件", "不是手机本地文件管理器", "frontend-design",
               "v1.3", "清掉 1.1 残留", "Theme.kt", "隐私边界", "固定签名"]:
        assert kw in s, f"README 缺少 {kw}"
    return "README 已同步 v1.3：瘦身、UI 依据、用法与边界"


def main():
    for name, fn in [
        ("必需文件齐全", t_required), ("无凭据/私钥泄露", t_no_secrets),
        ("1.1 残留已清空", t_slim), ("默认沙盒文件模式", t_sandbox_default),
        ("v1.3 UI 设计系统", t_ui_design), ("GitHub 中继后端", t_gh_backend),
        ("核心功能未改坏", t_features_intact), ("双向同步脚本", t_relay_scripts),
        ("开发者日志", t_devlog), ("固定签名", t_fixed_signing),
        ("v1.3 版本规则", t_version_rule), ("CI workflow 校验", t_workflow),
        ("README 文档校验", t_readme),
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
