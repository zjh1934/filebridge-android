#!/usr/bin/env python3
"""
沙盒 → GitHub：把跨对话持久区里的文件推到仓库 sandbox-files/ 目录。

用法（在对话里由 AI 执行，或你自己在有 Python 的地方跑）：
  FILEBRIDGE_GH_TOKEN=xxx python3 scripts/sync_to_github.py

只同步这一个专用目录，不碰 /data/user_persistent_data 下的其它私人资料。
commit message 带 [skip ci]，且 CI 配了 paths-ignore，避免同步文件触发重新打包。
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

H = {
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "FileBridge-Sandbox-Sync",
}
if TOKEN:
    H["Authorization"] = f"Bearer {TOKEN}"
API = f"https://api.github.com/repos/{OWNER}/{REPO}/contents"


def main() -> int:
    if not TOKEN:
        print("需要 FILEBRIDGE_GH_TOKEN 环境变量", file=sys.stderr)
        return 2
    LOCAL.mkdir(parents=True, exist_ok=True)
    H["Authorization"] = f"Bearer {TOKEN}"

    added, skipped = [], []
    files = sorted(p for p in LOCAL.rglob("*") if p.is_file())
    if not files:
        print("本地目录是空的，没什么可推")
        return 0

    for p in files:
        rel = p.relative_to(LOCAL).as_posix()
        remote = f"{ROOT}/{rel}"
        data = p.read_bytes()
        # 已经一致就跳过，避免无意义 commit
        cur = requests.get(f"{API}/{remote}", headers=H, params={"ref": BRANCH}, timeout=30)
        if cur.status_code == 200:
            try:
                old = base64.b64decode(cur.json()["content"].replace("\n", ""))
                if old == data:
                    skipped.append(rel)
                    continue
            except Exception:
                pass
            payload = {
                "message": f"FileBridge: 同步沙盒文件 {rel} [skip ci]",
                "content": base64.b64encode(data).decode(),
                "branch": BRANCH,
                "sha": cur.json()["sha"],
            }
        elif cur.status_code == 404:
            payload = {
                "message": f"FileBridge: 同步沙盒文件 {rel} [skip ci]",
                "content": base64.b64encode(data).decode(),
                "branch": BRANCH,
            }
        else:
            print(f"[FAIL] {rel}: 查询失败 {cur.status_code} {cur.text[:120]}")
            continue

        r = requests.put(f"{API}/{remote}", headers=H, json=payload, timeout=60)
        if r.status_code in (200, 201):
            added.append(rel)
            print(f"  + {rel}  ({len(data)} B)")
        else:
            print(f"[FAIL] {rel}: {r.status_code} {r.text[:160]}")
            return 3

    print(f"\n[sync] 推送 {len(added)} 个，跳过 {len(skipped)} 个未变化文件")
    print(f"[sync] 仓库目录：https://github.com/{OWNER}/{REPO}/tree/{BRANCH}/{ROOT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
