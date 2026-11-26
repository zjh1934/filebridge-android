#!/usr/bin/env python3
"""
推送前的静态自检（不依赖 Android SDK / Kotlin 编译器）：

  1. 必需文件齐全
  2. 仓库里绝不能出现 GitHub Token、PAT 或签名私钥
  3. 默认零配置本机模式 / 固定签名 / 版本规则 / 日志功能都真正接上
  4. CI workflow 能被 YAML 解析，产物名与 Gradle 版本对得上
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
    "android/app/src/main/java/com/example/filebridge/LocalStore.kt",
    "android/app/src/main/java/com/example/filebridge/FileRepository.kt",
    "android/app/src/main/java/com/example/filebridge/MainActivity.kt",
]

SKIP_DIRS = {".git", ".gh", "__pycache__", "build", ".gradle", ".idea"}
SKIP_NAMES = {"server.log", "VERIFY_STATIC.md", "filebridge.jks"}

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
        if p.suffix in (".jks", ".keystore", ".pk8", ".p12"):
            hits.append(f"{p.relative_to(ROOT)} 含签名私钥")
    assert not hits, "发现凭据: " + "; ".join(hits)
    return f"扫描 {len(all_files())} 个文件，无 Token / PAT / 私钥泄露"


def t_local_default():
    p = ROOT / "android/app/src/main/java/com/example/filebridge/MainActivity.kt"
    s = p.read_text("utf-8")
    for kw in ['prefs.getString("mode", "local")', 'prefs.getString("base", "")',
               "if (mode == \"local\")", "if (base.isBlank())", "LocalStore(ctx)",
               "还没填网关地址", "不需要网络、路由器或电脑"]:
        assert kw in s, f"MainActivity 缺少 {kw}"
    assert "http://10.0.2.2" not in s, "还残留模拟器默认地址"
    ls = (ROOT / "android/app/src/main/java/com/example/filebridge/LocalStore.kt").read_text("utf-8")
    for kw in ["class LocalStore", "fun list(", "fun importFrom(", "fun trash(",
               "fun restore(", "fun shareIntent(", "fun viewIntent("]:
        assert kw in ls, f"LocalStore.kt 缺少 {kw}"
    return "默认本机模式、无默认 IP、本地 CRUD/导入/回收站/分享齐备"


def t_devlog_kotlin():
    s = (ROOT / "android/app/src/main/java/com/example/filebridge/DevLog.kt").read_text("utf-8")
    for kw in ["object DevLog", "fun sanitize", "fun exportMarkdown", "fun shareIntent",
               "fun http(", "fun clear(", "stackTraceToString", "devlog.ndjson",
               "## 5. 网络请求明细", "## 6. 异常堆栈"]:
        assert kw in s, f"DevLog.kt 缺少 {kw}"
    return "日志采集/脱敏/MD 导出/分享/异常堆栈齐备"


def t_devlog_wired():
    checks = {
        "ApiClient.kt": ["DevLogInterceptor", "DevLog.http("],
        "FileRepository.kt": ["track(", "DevLog.e("],
        "MainActivity.kt": ["DevLog.init(", "一键导出 MD", "另存为…", "DevLog.clear()", "reportError"],
        "AndroidManifest.xml": ["<queries>", "fileprovider"],
        "file_paths.xml": ['name="myfiles"', 'name="exports"', 'name="devlog"'],
    }
    base = ROOT / "android/app/src/main"
    java = base / "java/com/example/filebridge"
    for fn, kws in checks.items():
        p = java / fn if fn.endswith(".kt") else (
            base / "AndroidManifest.xml" if fn == "AndroidManifest.xml"
            else base / "res/xml/file_paths.xml"
        )
        s = p.read_text("utf-8")
        for kw in kws:
            assert kw in s, f"{fn} 缺少 {kw}"
    web = (ROOT / "web/index.html").read_text("utf-8")
    for kw in ["function exportDevLog", "function scrub", "导出日志"]:
        assert kw in web, f"web/index.html 缺少 {kw}"
    return "日志已接入网络层 / 仓库层 / UI / Manifest / FileProvider / Web"


def t_fixed_signing():
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    gradle = (ROOT / "android/app/build.gradle.kts").read_text("utf-8")
    for kw in ["secrets.FILEBRIDGE_KEYSTORE_B64", "base64 -d > app/filebridge.jks",
               "assembleRelease", "verify --verbose", "--print-certs"]:
        assert kw in wf, f"workflow 缺少 {kw}"
    for kw in ['create("fixed")', "file(\"filebridge.jks\")", "enableV2Signing = true",
               'versionName = "1.1"', "versionCode = 2"]:
        assert kw in gradle, f"build.gradle.kts 缺少 {kw}"
    assert (ROOT / "android/app/filebridge.jks").exists() is False, "私钥不应在仓库里"
    gi = (ROOT / ".gitignore").read_text("utf-8")
    assert "*.jks" in gi, ".gitignore 未排除 jks"
    return "固定证书来自 Secret、release 签名、CI 校验签名、私钥不入库"


def t_version_rule():
    wf = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
    body = (ROOT / ".github/RELEASE_BODY.md").read_text("utf-8")
    for kw in ['APP_VERSION: "1.1"', 'APK_NAME: "FileBridge-v1.1.apk"',
               'RELEASE_TAG: "v1.1"', "assembleRelease", "app-release.apk"]:
        assert kw in wf, f"workflow 缺少 {kw}"
    for kw in ["FileBridge-v1.1.apk", "签名已固定", "v1.2、v1.3", "覆盖安装"]:
        assert kw in body, f"RELEASE_BODY 缺少 {kw}"
    return "V1.1 起包名/tag/版本规则固定，后续可覆盖安装"


def t_workflow():
    s = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
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
    for kw in ["contents: write", "RELEASE_BODY.md", "actions/checkout@v4"]:
        assert kw in s, f"workflow 缺少 {kw}"
    return parsed + "；产物路径与 Release 配置正确"


def t_readme():
    s = (ROOT / "README.md").read_text("utf-8")
    for kw in ["打开就能用", "不需要电脑", "不需要路由器", "一键导出", "开发者日志",
               "固定签名", "v1.1", "脱敏"]:
        assert kw in s, f"README 缺少 {kw}"
    return "README 已改为零配置优先，含签名与版本规则"


def main():
    for name, fn in [
        ("必需文件齐全", t_required),
        ("无凭据/私钥泄露", t_no_secrets),
        ("零配置本机模式", t_local_default),
        ("DevLog 核心能力", t_devlog_kotlin),
        ("日志接入链路", t_devlog_wired),
        ("固定签名", t_fixed_signing),
        ("V1.1 版本规则", t_version_rule),
        ("CI workflow 校验", t_workflow),
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
