#!/usr/bin/env python3
"""
把对话工作区里的产物同步到跨对话持久区，不依赖服务进程。

用法：
  python3 scripts/sync_workspace.py            # 扫描 /data/workspace 并登记
  python3 scripts/sync_workspace.py --init    # 只初始化目录 + 跨对话标记文件

它和服务端共用同一把 flock 锁、同一个 index.json 结构，所以两边并发写是安全的。
生产里更推荐把这一步换成「对象存储 + 元数据 DB」，脚本只是最小可跑版本。
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import mimetypes
import os
import shutil
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE = Path(os.getenv("FILEBRIDGE_BASE", "/data/user_persistent_data/filebridge")).resolve()
ROOT = BASE / "files"
INDEX = BASE / "index.json"
LOCK = BASE / ".lock"
WS = Path(os.getenv("FILEBRIDGE_WORKSPACE", "/data/workspace")).resolve()
MARKER = BASE / "CROSS_SESSION_MARKER.txt"

ROOT.mkdir(parents=True, exist_ok=True)


@contextmanager
def locked():
    with LOCK.open("a+") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(f, fcntl.LOCK_UN)


def sha(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def clean(name: str) -> str:
    n = Path(name.replace("\\", "/")).name.strip()
    return n or "unnamed"


def load() -> dict:
    if not INDEX.exists():
        return {}
    try:
        return json.loads(INDEX.read_text("utf-8"))
    except Exception:
        return {}


def save(db: dict) -> None:
    tmp = INDEX.with_suffix(".tmp")
    tmp.write_text(json.dumps(db, ensure_ascii=False, indent=2), "utf-8")
    os.replace(tmp, INDEX)


def ensure_marker() -> None:
    if MARKER.exists():
        return
    now = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S CST")
    MARKER.write_text(
        "FileBridge 跨对话持久性标记\n"
        f"创建时间：{now}\n"
        f"路径：{MARKER}\n\n"
        "验证方法：下次新开一个对话，让 AI 读取上面这个路径。\n"
        "只要文件还在、创建时间不变，就说明 /data/user_persistent_data 确实跨对话保留。\n",
        "utf-8",
    )
    print(f"[marker] created: {MARKER}")


def main() -> None:
    global BASE, ROOT, INDEX, LOCK, WS, MARKER
    ap = argparse.ArgumentParser()
    ap.add_argument("--init", action="store_true", help="只初始化，不扫描")
    ap.add_argument("--base", default=str(BASE))
    ap.add_argument("--workspace", default=str(WS))
    args = ap.parse_args()

    BASE = Path(args.base).resolve()
    ROOT = BASE / "files"
    INDEX = BASE / "index.json"
    LOCK = BASE / ".lock"
    WS = Path(args.workspace).resolve()
    MARKER = BASE / "CROSS_SESSION_MARKER.txt"
    ROOT.mkdir(parents=True, exist_ok=True)

    ensure_marker()
    if args.init:
        return

    added = []
    for src in sorted(WS.iterdir()):
        if not src.is_file() or src.name.startswith(".") or src.name.startswith("filebridge"):
            continue
        name = clean(src.name)
        dst = ROOT / name
        if dst.exists() and sha(dst) == sha(src):
            continue
        # virtiofs 不允许 utime/copystat，只拷内容即可
        shutil.copyfile(src, dst)
        with locked():
            db = load()
            fid = next((k for k, v in db.items()
                        if v.get("rel") == name and v.get("type") != "dir"), None)
            fid = fid or uuid.uuid4().hex[:16]
            db[fid] = {
                "name": name, "parent": "", "rel": name, "type": "file",
                "sha256": sha(dst),
                "mime": mimetypes.guess_type(name)[0] or "application/octet-stream",
                "updated_at": time.time(),
            }
            save(db)
        added.append(name)
    print(f"[sync] {len(added)} file(s) -> {ROOT}")
    for n in added:
        print("  +", n)


if __name__ == "__main__":
    main()
