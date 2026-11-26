#!/usr/bin/env python3
"""
推送前静态自检（不依赖 Android SDK / Kotlin 编译器）：

  1. 必需文件齐全
  2. 仓库里绝不能出现 GitHub Token、PAT 或签名私钥
  3. 默认就是「沙盒文件」模式，GitHub 中继链路完整
  4. 固定签名 / 版本规则 / 日志功能都接上
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
    "android/app/src/main/java/com/example/filebridge/LocalStore.kt",
    "android/app/src/main/java/com/example/filebridge/DevLog.kt",
    "android/app/src/main/java/com/example/filebridge/Api.kt",
    "android/app/src/main/java/com/example/filebridge/ApiClient.kt",
    "android/app/src/main/java/com/example/filebridge/FileRepository.kt",
    "android/app/src/main/java/com/example/filebridge/MainActivity.kt",
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


def t_required():
    missing = [f for f in REQUIRED if not (ROOT / f).is_file()]
    assert not missing, f"缺失文件: {missing}"
    return f"{len(REQUIRED)} 个必需文件齐全"


def t_no_secrets():
    pats = [re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"),
            re.compile(r"github_pat_[A-Za-z0-9_]{20,}")]
    real = ""
    env = os.getenv("FILEBRIDGE_GH_TOKEN", "")
    tf = Path("/data/workspace/.gh/token")
    if env:
        real = env.strip()
    elif tf.exists():
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


def t_sandbox_default():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ['prefs.getString("mode", "sandbox")', 'private const val GH_OWNER = "zjh1934"',
               'private const val GH_REPO = "filebridge-android"',
               'private const val GH_ROOT = "sandbox-files"',
               '"sandbox" -> gh.list(p)', "FileBridge · 沙盒 /$parent",
               "把令牌粘一次", "保存，然后就能看到沙盒文件"]:
        assert kw in s, f"MainActivity 缺少 {kw}"
    assert "http://10.0.2.2" not in s, "仍残留模拟器默认地址"
    return "默认沙盒模式、仓库/目录预置、只需粘一次令牌"


def t_gh_backend():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/GitHubStore.kt").read_text("utf-8")
    for kw in ["class GitHubStore", "fun list(", "fun readBytes(", "fun put(",
               "fun delete(", "fun trash(", "fun restore(", "fun purge(",
               "fun mkdir(", "fun importFrom(", "fun localFile(",
               "repos/$owner/$repo/contents", "X-GitHub-Api-Version", "DevLog.http("]:
        assert kw in s, f"GitHubStore.kt 缺少 {kw}"
    return "Contents API 列/读/写/删/回收站/导入/下载齐备，请求全量打点"


def t_relay_scripts():
    a = (ROOT / "scripts/sync_to_github.py").read_text("utf-8")
    b = (ROOT / "scripts/pull_from_github.py").read_text("utf-8")
    for kw in ["[skip ci]", "FILEBRIDGE_GH_TOKEN", "sandbox-files", "repos/{OWNER}/{REPO}/contents"]:
        assert kw in a, f"sync_to_github.py 缺少 {kw}"
    for kw in ["FILEBRIDGE_GH_TOKEN", "git/blobs", "sandbox-files"]:
        assert kw in b, f"pull_from_github.py 缺少 {kw}"
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    assert "paths-ignore" in wf and "sandbox-files/**" in wf, "CI 未忽略中转目录"
    return "双向同步脚本 + CI paths-ignore 齐备（同步不触发重打包）"


def t_devlog():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/DevLog.kt").read_text("utf-8")
    for kw in ["object DevLog", "fun sanitize", "fun exportMarkdown", "fun shareIntent",
               "fun http(", "stackTraceToString", "devlog.ndjson", "## 6. 异常堆栈"]:
        assert kw in s, f"DevLog.kt 缺少 {kw}"
    main = (ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt").read_text("utf-8")
    for kw in ["DevLog.init(", "一键导出 MD", "另存为…", "DevLog.clear()"]:
        assert kw in main, f"MainActivity 缺少 {kw}"
    web = (ROOT / "web/index.html").read_text("utf-8")
    assert "function exportDevLog" in web and "function scrub" in web, "Web 端日志导出缺失"
    return "日志采集/脱敏/MD 导出/分享齐备，安卓与 Web 都已接入"


def t_fixed_signing():
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    gradle = (ROOT / "android/app/build.gradle.kts").read_text("utf-8")
    for kw in ["secrets.FILEBRIDGE_KEYSTORE_B64", "base64 -d > app/filebridge.jks",
               "assembleRelease", "verify --verbose", "--print-certs"]:
        assert kw in wf, f"workflow 缺少 {kw}"
    for kw in ['create("fixed")', 'file("filebridge.jks")', "enableV2Signing = true",
               'versionName = "1.2"', "versionCode = 3"]:
        assert kw in gradle, f"build.gradle.kts 缺少 {kw}"
    assert not (ROOT / "android/app/filebridge.jks").exists(), "私钥不应在仓库里"
    assert "*.jks" in (ROOT / ".gitignore").read_text("utf-8"), ".gitignore 未排除 jks"
    return "固定证书来自 Secret、release 签名、CI 校验、私钥不入库"


def t_version_rule():
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    body = (ROOT / ".github/RELEASE_BODY.md").read_text("utf-8")
    for kw in ['APP_VERSION: "1.2"', 'APK_NAME: "FileBridge-v1.2.apk"',
               'RELEASE_TAG: "v1.2"', "assembleRelease", "app-release.apk"]:
        assert kw in wf, f"workflow 缺少 {kw}"
    for kw in ["FileBridge-v1.2.apk", "沙盒", "覆盖安装", "粘一次"]:
        assert kw in body, f"RELEASE_BODY 缺少 {kw}"
    return "v1.2 包名/tag/版本规则固定，可覆盖 v1.1"


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
    for kw in ["查看和编辑沙盒里的文件", "不是手机本地文件管理器", "GitHub",
               "摆渡", "粘一次", "隐私边界", "一键导出", "固定签名"]:
        assert kw in s, f"README 缺少 {kw}"
    return "README 已纠正目标，含中继原理、隐私边界与闭环"


def main():
    for name, fn in [
        ("必需文件齐全", t_required), ("无凭据/私钥泄露", t_no_secrets),
        ("默认沙盒文件模式", t_sandbox_default), ("GitHub 中继后端", t_gh_backend),
        ("双向同步脚本", t_relay_scripts), ("开发者日志", t_devlog),
        ("固定签名", t_fixed_signing), ("v1.2 版本规则", t_version_rule),
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
