#!/usr/bin/env python3
"""
GitHub → 沙盒：把仓库 sandbox-files/ 里的改动拉回跨对话持久区。

闭环：
  沙盒文件 --sync_to_github.py--> 仓库 --> 手机 APP 查看/编辑 --> 仓库
      --pull_from_github.py--> 沙盒持久区

下次开对话让我跑一次这个脚本（或说一句「同步沙盒文件」），
你在手机上改的内容就回到沙盒里了。

  FILEBRIDGE_GH_TOKEN=xxx python3 scripts/pull_from_github.py
"""
from __future__ import annotations

import base64
import os
import sys
from pathlib import Path

import requests

OWNER = os.getenv("FILEBRIDGE_GH_OWNER", "zjh1934")
REPO = os.getenv("FILEBRIDGE_GH_REPO", "filebridge-android")
BRANCH = os.getenv("FILEBRIDGE_GH_BRANCH", "main")
ROOT = os.getenv("FILEBRIDGE_GH_ROOT", "sandbox-files")
LOCAL = Path(os.getenv(
    "FILEBRIDGE_SANDBOX_DIR",
    "/data/user_persistent_data/filebridge/sandbox-files",
)).resolve()
TOKEN = os.getenv("FILEBRIDGE_GH_TOKEN", "").strip()

H = {"Accept": "application/vnd.github+json",
     "X-GitHub-Api-Version": "2022-11-28",
     "User-Agent": "FileBridge-Sandbox-Pull"}
API = f"https://api.github.com/repos/{OWNER}/{REPO}/contents"


def walk(remote_path: str, out: list[tuple[str, str]]) -> None:
    r = requests.get(f"{API}/{remote_path}", headers=H, params={"ref": BRANCH}, timeout=30)
    if r.status_code == 404:
        return
    if r.status_code != 200:
        print(f"[WARN] 列目录失败 {remote_path}: {r.status_code} {r.text[:120]}")
        return
    for item in r.json():
        if item["type"] == "dir":
            walk(item["path"], out)
        elif item["type"] == "file":
            out.append((item["path"], item["sha"]))


def main() -> int:
    if not TOKEN:
        print("需要 FILEBRIDGE_GH_TOKEN 环境变量", file=sys.stderr)
        return 2
    H["Authorization"] = f"Bearer {TOKEN}"
    LOCAL.mkdir(parents=True, exist_ok=True)

    entries: list[tuple[str, str]] = []
    walk(ROOT, entries)
    pulled = 0
    for path, sha in entries:
        if not path.startswith(ROOT + "/"):
            continue
        rel = path[len(ROOT) + 1:]
        # 回收站也拉回来，但不当普通文件展示
        dst = LOCAL / rel
        r = requests.get(
            f"https://api.github.com/repos/{OWNER}/{REPO}/git/blobs/{sha}",
            headers=H, timeout=30,
        )
        if r.status_code != 200:
            print(f"[WARN] 读取失败 {rel}: {r.status_code}")
            continue
        data = base64.b64decode(r.json()["content"].replace("\n", ""))
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists() and dst.read_bytes() == data:
            continue
        dst.write_bytes(data)
        pulled += 1
        print(f"  ← {rel}  ({len(data)} B)")

    print(f"\n[pull] 回灌 {pulled} 个改动 → {LOCAL}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
