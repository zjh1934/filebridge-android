#!/usr/bin/env python3
"""
推送前的静态自检（不依赖 Android SDK / Kotlin 编译器）：

  1. 必需文件齐全
  2. 仓库里绝不能出现 GitHub Token 或任何真实密钥
  3. 开发者日志功能在安卓端 / Web 端 / 文档里都真正接上了
  4. CI workflow 能被 YAML 解析，且产物路径与 Gradle 配置对得上
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
    "README.md",
    "VERIFY.md",
    ".gitignore",
    ".github/workflows/build-apk.yml",
    ".github/RELEASE_BODY.md",
    "server/app.py",
    "server/__init__.py",
    "scripts/start_server.sh",
    "scripts/sync_workspace.py",
    "scripts/verify.py",
    "scripts/check_sources.py",
    "web/index.html",
    "android/settings.gradle.kts",
    "android/build.gradle.kts",
    "android/gradle.properties",
    "android/app/build.gradle.kts",
    "android/app/src/main/AndroidManifest.xml",
    "android/app/src/main/res/xml/file_paths.xml",
    "android/app/src/main/res/values/styles.xml",
    "android/app/src/main/java/com/example/filebridge/Api.kt",
    "android/app/src/main/java/com/example/filebridge/ApiClient.kt",
    "android/app/src/main/java/com/example/filebridge/DevLog.kt",
    "android/app/src/main/java/com/example/filebridge/FileRepository.kt",
    "android/app/src/main/java/com/example/filebridge/MainActivity.kt",
]

SKIP_DIRS = {".git", ".gh", "__pycache__", "build", ".gradle", ".idea"}
SKIP_NAMES = {"server.log", "VERIFY_STATIC.md"}

results: list[tuple[str, str, str]] = []


def check(name: str, fn):
    try:
        detail = fn()
        results.append((name, "PASS", str(detail)))
        print(f"[PASS] {name}: {detail}")
    except Exception as e:
        results.append((name, "FAIL", f"{type(e).__name__}: {e}"))
        print(f"[FAIL] {name}: {type(e).__name__}: {e}")


def all_files() -> list[Path]:
    out = []
    for p in ROOT.rglob("*"):
        if not p.is_file():
            continue
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        if p.name in SKIP_NAMES:
            continue
        out.append(p)
    return out


def t_required():
    missing = [f for f in REQUIRED if not (ROOT / f).is_file()]
    assert not missing, f"缺失文件: {missing}"
    return f"{len(REQUIRED)} 个必需文件齐全"


def t_no_secrets():
    pats = [
        re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"),
        re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    ]
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
    assert not hits, "发现凭据: " + "; ".join(hits)
    return f"扫描 {len(all_files())} 个文件，无 Token / PAT 泄露"


def t_devlog_kotlin():
    p = ROOT / "android/app/src/main/java/com/example/filebridge/DevLog.kt"
    s = p.read_text("utf-8")
    for kw in ["object DevLog", "fun sanitize", "fun exportMarkdown", "fun shareIntent",
               "fun http(", "fun clear(", "stackTraceToString", "devlog.ndjson",
               "## 5. 网络请求明细", "## 6. 异常堆栈"]:
        assert kw in s, f"DevLog.kt 缺少 {kw}"
    return "日志采集/脱敏/MD 导出/分享/异常堆栈齐备"


def t_devlog_wired():
    checks = {
        "ApiClient.kt": ["DevLogInterceptor", "DevLog.http("],
        "FileRepository.kt": ["track(", "exportDevLog", "DevLog.e(", "devLogShareIntent"],
        "MainActivity.kt": ["DevLog.init(", "一键导出 MD", "另存为…", "clearDevLog", "reportError"],
        "AndroidManifest.xml": ["<queries>", "fileprovider"],
        "file_paths.xml": ['name="exports"', 'name="devlog"'],
    }
    base = ROOT / "android/app/src/main"
    java = base / "java/com/example/filebridge"
    for fn, kws in checks.items():
        p = java / fn if fn.endswith(".kt") else (
            base / "AndroidManifest.xml" if fn.endswith(".xml") and fn == "AndroidManifest.xml"
            else base / "res/xml/file_paths.xml"
        )
        s = p.read_text("utf-8")
        for kw in kws:
            assert kw in s, f"{fn} 缺少 {kw}"
    return "日志已接入网络层 / 仓库层 / UI / Manifest / FileProvider"


def t_web_devlog():
    s = (ROOT / "web/index.html").read_text("utf-8")
    for kw in ["function exportDevLog", "function scrub", "function dl(",
               "DEVLOG", "导出日志", "## 4. 网络请求明细"]:
        assert kw in s, f"web/index.html 缺少 {kw}"
    return "Web 控制台同样支持一键导出 MD 日志"


def t_workflow():
    p = ROOT / ".github/workflows/build-apk.yml"
    s = p.read_text("utf-8")
    try:
        import yaml  # type: ignore
        doc = yaml.safe_load(s)
        assert isinstance(doc, dict), "YAML 顶层不是映射"
        assert "jobs" in doc and "build" in doc["jobs"], "缺少 build job"
        assert doc["jobs"]["build"]["runs-on"] == "ubuntu-latest", "runner 不对"
        assert doc.get(True) or doc.get("on"), "缺少触发器"
        parsed = "PyYAML 解析通过"
    except ImportError:
        parsed = "PyYAML 未安装，改为关键字校验"
        for kw in ["actions/checkout@v4", "setup-java@v4", "setup-android@v3",
                   "gradle/actions/setup-gradle@v4", "assembleDebug",
                   "softprops/action-gh-release@v2", "tag_name: v0.1.0-debug"]:
            assert kw in s, f"workflow 缺少 {kw}"
    for kw in ["android/FileBridge-debug.apk", "RELEASE_BODY.md", "contents: write"]:
        assert kw in s, f"workflow 缺少 {kw}"
    return parsed + "；产物路径与 Release 配置正确"


def t_gradle():
    s = (ROOT / "android/app/build.gradle.kts").read_text("utf-8")
    for kw in ["applicationId = \"com.example.filebridge\"", "compileSdk = 34",
               "minSdk = 26", "compose = true", "kotlinCompilerExtensionVersion"]:
        assert kw in s, f"build.gradle.kts 缺少 {kw}"
    assert "assembleDebug" in (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    return "Gradle 配置与 CI 任务一致（app-debug.apk）"


def t_release_body():
    s = (ROOT / ".github/RELEASE_BODY.md").read_text("utf-8")
    for kw in ["FileBridge-debug.apk", "一键导出", "Markdown", "开发者日志", "HTTPS"]:
        assert kw in s, f"RELEASE_BODY 缺少 {kw}"
    return "Release 说明含安装步骤与日志功能"


def t_readme():
    s = (ROOT / "README.md").read_text("utf-8")
    for kw in ["一键导出", "开发者日志", "Release", "脱敏", "CI"]:
        assert kw in s, f"README 缺少 {kw}"
    return "README 已补充日志与 CI/Release 章节"


def main():
    for name, fn in [
        ("必需文件齐全", t_required),
        ("无凭据泄露", t_no_secrets),
        ("DevLog 核心能力", t_devlog_kotlin),
        ("日志接入链路", t_devlog_wired),
        ("Web 端日志导出", t_web_devlog),
        ("CI workflow 校验", t_workflow),
        ("Gradle 配置校验", t_gradle),
        ("Release 说明校验", t_release_body),
        ("README 文档校验", t_readme),
    ]:
        check(name, fn)

    passed = sum(1 for _, s, _ in results if s == "PASS")
    total = len(results)
    now = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S CST")
    lines = [
        "# FileBridge 源码静态自检", "",
        f"- 执行时间：{now}",
        f"- 结果：**{passed}/{total} 通过**",
        "- 说明：沙盒内无 Android SDK，此处为**静态自检**；Kotlin 实际编译由 GitHub Actions 完成，结果以 Release 产物为准。", "",
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
