#!/usr/bin/env python3
"""
FileBridge · 跨对话持久区 ↔ 私有镜像仓库 双向增量同步

本地根：/data/user_persistent_data   （真正跨对话保留的目录，不是 /data/workspace）
镜像仓：zjh1934/filebridge-persistent（私有，仓库根目录 = 持久区根目录）
代码仓：zjh1934/filebridge-android  （只放 APP 源码与 CI，与数据彻底分开）

为什么用 Git Data API 而不是 git push：
  沙盒可用磁盘只剩约 0.4 GB，持久区本身约 1 GB，
  git clone / 对象库放不下。这里全程流式：一次只在内存里拿一个文件，
  用 /git/blobs 建对象 + /git/trees 批量提交，不需要额外磁盘。

三向比较（base / local / remote），用 git blob sha1 当内容指纹，不下载远端内容做比较：
  - 仅本地变 → 上传
  - 仅远端变 → 回灌本地
  - 两边都变 → 默认以远端（手机 APP 的编辑）为准，本地版本备份到 /data/workspace/conflicts/
  - 空目录 → 远端用 .gitkeep 占位，本地不落地该文件

天然支持断点续传：每次重新比较远端 tree，已提交的文件下次会自动跳过。

用法：
  FILEBRIDGE_GH_TOKEN=xxx python3 sync_persistent.py status   # 只比较，不改
  FILEBRIDGE_GH_TOKEN=xxx python3 sync_persistent.py sync     # 双向同步
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import requests

OWNER = os.getenv("FB_MIRROR_OWNER", "zjh1934")
REPO = os.getenv("FB_MIRROR_REPO", "filebridge-persistent")
BRANCH = os.getenv("FB_MIRROR_BRANCH", "main")
LOCAL = Path(os.getenv("FB_PERSISTENT_DIR", "/data/user_persistent_data")).resolve()
STATE = LOCAL / ".filebridge_sync_state.json"
CONFLICTS = Path(os.getenv("FB_CONFLICT_DIR", "/data/workspace/conflicts"))
TOKEN = os.getenv("FILEBRIDGE_GH_TOKEN", "").strip()

EXCLUDE_TOP = {".filebridge_sync_state.json"}     # 同步状态自身不进镜像
GITKEEP = ".gitkeep"
REMOTE_META = ".filebridge-mirror-info.md"        # 镜像仓元数据，不回灌本地
BATCH = 300                                       # 每批 tree 条目数
SLEEP_PER_BLOB = 0.06                             # 轻微限速，避开 secondary rate limit

H = {
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "FileBridge-Persistent-Sync",
}
R = f"https://api.github.com/repos/{OWNER}/{REPO}"


def api(method: str, path: str, *, params=None, body=None, allow_404=False, timeout=180):
    url = path if path.startswith("http") else R + path
    hdr = dict(H)
    if TOKEN:
        hdr["Authorization"] = f"Bearer {TOKEN}"
    for attempt in range(6):
        try:
            resp = requests.request(method, url, headers=hdr, params=params,
                                    json=body, timeout=timeout)
        except Exception as e:
            if attempt == 5:
                raise
            wait = min(30, 2 ** attempt)
            print(f"[retry] 网络异常 {e}，{wait}s 后重试", flush=True)
            time.sleep(wait)
            continue
        if allow_404 and (
            resp.status_code == 404
            or (resp.status_code == 409 and "git repository is empty" in resp.text.lower())
        ):
            return None
        # core 限额用尽时，直接等到 reset，而不是短重试后放弃
        if resp.status_code == 403 and resp.headers.get("X-RateLimit-Remaining") == "0":
            reset = int(resp.headers.get("X-RateLimit-Reset", "0") or "0")
            wait = max(10, reset - int(time.time()) + 10)
            print(f"[rate-limit] core 用尽，等待重置 {wait}s", flush=True)
            time.sleep(min(wait, 3600))
            continue
        low = resp.text.lower()
        if resp.status_code in (403, 429) and (
            "rate limit" in low or "secondary" in low or "abuse" in low
        ):
            wait = float(resp.headers.get("Retry-After", 0)) or min(60, 5 * (attempt + 1))
            print(f"[rate-limit] 触发限流，等待 {wait:.0f}s（remaining="
                  f"{resp.headers.get('X-RateLimit-Remaining','?')}）", flush=True)
            time.sleep(wait)
            continue
        if resp.status_code >= 400:
            raise RuntimeError(f"{method} {url} -> {resp.status_code}: {resp.text[:220]}")
        return resp.json() if resp.content else {}
    raise RuntimeError("重试次数耗尽")


def blob_sha(data: bytes) -> str:
    h = hashlib.sha1()
    h.update(b"blob " + str(len(data)).encode() + b"\0")
    h.update(data)
    return h.hexdigest()


def safe_rel(rel: str) -> bool:
    p = Path(rel)
    return not p.is_absolute() and ".." not in p.parts and rel not in ("", ".")


def remote_snapshot():
    """返回 (远端文件 map, HEAD commit sha, HEAD tree sha, 远端 .gitkeep 集合)"""
    ref = api("GET", f"/git/ref/heads/{BRANCH}", allow_404=True)
    if ref is None:
        return {}, None, None, set()
    parent = ref["object"]["sha"]
    commit = api("GET", f"/git/commits/{parent}")
    tree_sha = commit["tree"]["sha"]
    tree = api("GET", f"/git/trees/{tree_sha}", params={"recursive": "1"})
    if tree.get("truncated"):
        raise RuntimeError("远端 tree 被截断，无法安全同步")
    files, keeps = {}, set()
    for e in tree["tree"]:
        if e["type"] != "blob":
            continue
        p = e["path"]
        if p == REMOTE_META:
            continue
        if p == GITKEEP or p.endswith("/" + GITKEEP):
            keeps.add(p)
            continue
        files[p] = e["sha"]
    return files, parent, tree_sha, keeps


def load_state() -> dict:
    if not STATE.exists():
        return {}
    try:
        return json.loads(STATE.read_text("utf-8")).get("files", {})
    except Exception:
        return {}


def save_state(info: dict):
    STATE.write_text(
        json.dumps({"files": info, "updated": time.time(), "repo": f"{OWNER}/{REPO}"},
                   ensure_ascii=False),
        "utf-8",
    )


def local_scan(state: dict):
    """扫描持久区；size + mtime_ns 未变时复用上次的 sha，避免反复读 1 GB 内容。"""
    info, empties = {}, []
    n = 0
    for p in LOCAL.rglob("*"):
        rel = p.relative_to(LOCAL).as_posix()
        if p.is_dir():
            if not any(p.iterdir()):
                empties.append(rel)
            continue
        if rel in EXCLUDE_TOP or p.name == GITKEEP:
            continue
        st = p.stat()
        prev = state.get(rel)
        if prev and prev.get("size") == st.st_size and prev.get("mtime_ns") == st.st_mtime_ns:
            info[rel] = {"sha": prev["sha"], "size": st.st_size, "mtime_ns": st.st_mtime_ns}
        else:
            info[rel] = {
                "sha": blob_sha(p.read_bytes()),
                "size": st.st_size,
                "mtime_ns": st.st_mtime_ns,
            }
        n += 1
        if n % 800 == 0:
            print(f"[scan] 已读 {n} 个文件", flush=True)
    return info, empties


def decide(base, local, remote):
    push, pull, drop_remote, drop_local, conflicts = [], [], [], [], []
    for rel in sorted(set(base) | set(local) | set(remote)):
        b, l, r = base.get(rel), local.get(rel), remote.get(rel)
        if l == r or (l is None and r is None):
            continue
        if b is None:
            if l is not None and r is None:
                push.append(rel)
            elif r is not None and l is None:
                pull.append(rel)
            else:
                conflicts.append(rel)
        elif l == b and r is None:
            drop_local.append(rel)          # 远端删了，本地没动
        elif r == b and l is None:
            drop_remote.append(rel)         # 本地删了，远端没动
        elif l != b and r == b:
            push.append(rel)                # 仅本地改
        elif r != b and l == b:
            pull.append(rel)                # 仅远端改
        else:
            conflicts.append(rel)           # 两边都改
    return push, pull, drop_remote, drop_local, conflicts


def backup_local(rel: str):
    """冲突时把本地版本备份到 /data/workspace/conflicts（不在持久区，避免循环同步）"""
    src = LOCAL / rel
    if not src.is_file():
        return False
    try:
        dst = CONFLICTS / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(src.read_bytes())
        return True
    except Exception as e:
        print(f"[warn] 冲突备份失败 {rel}: {e}", flush=True)
        return False


def pull_file(rel: str, sha: str, info: dict):
    if not safe_rel(rel):
        raise RuntimeError(f"远端路径不安全: {rel}")
    blob = api("GET", f"/git/blobs/{sha}")
    data = base64.b64decode(blob.get("content", "").replace("\n", ""))
    if blob.get("encoding") != "base64":
        raise RuntimeError(f"远端 blob 编码异常: {rel}")
    dst = LOCAL / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(data)
    st = dst.stat()
    info[rel] = {"sha": blob_sha(data), "size": st.st_size, "mtime_ns": st.st_mtime_ns}


def flush(entries, parent, tree_sha):
    if not entries:
        return parent, tree_sha
    payload = {"tree": entries}
    if tree_sha:
        payload["base_tree"] = tree_sha
    t = api("POST", "/git/trees", body=payload)
    c = api("POST", "/git/commits", body={
        "message": "FileBridge: 同步跨对话持久区 [skip ci]",
        "tree": t["sha"],
        "parents": [parent] if parent else [],
    })
    if parent is None:
        api("POST", "/git/refs", body={"ref": f"refs/heads/{BRANCH}", "sha": c["sha"]})
    else:
        api("PATCH", f"/git/refs/heads/{BRANCH}", body={"sha": c["sha"]})
    return c["sha"], c["tree"]["sha"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("action", nargs="?", default="sync", choices=["sync", "status"])
    args = ap.parse_args()

    if not TOKEN:
        print("需要环境变量 FILEBRIDGE_GH_TOKEN", file=sys.stderr)
        return 2
    LOCAL.mkdir(parents=True, exist_ok=True)

    remote, parent, tree_sha, keeps = remote_snapshot()
    state = load_state()
    info, empties = local_scan(state)
    base = {k: v["sha"] for k, v in state.items()}
    local = {k: v["sha"] for k, v in info.items()}

    push, pull, drop_remote, drop_local, conflicts = decide(base, local, remote)
    total_local = len(local)
    print(f"[state] 本地 {total_local} 文件 / 远端 {len(remote)} 文件 / 空目录 {len(empties)}")
    print(f"[plan] 上传 {len(push)} · 回灌 {len(pull)} · 删远端 {len(drop_remote)}"
          f" · 删本地 {len(drop_local)} · 冲突 {len(conflicts)}")
    if args.action == "status":
        return 0

    # 冲突：以远端（手机编辑）为准，本地版本备份后回灌
    for rel in conflicts:
        if rel in local and rel in remote:
            backup_local(rel)
            pull.append(rel)

    # 远端改动 → 回灌本地
    for i, rel in enumerate(pull, 1):
        if rel in remote:
            pull_file(rel, remote[rel], info)
        if i % 100 == 0:
            print(f"[pull] {i}/{len(pull)}", flush=True)

    # 远端删除 → 本地删除
    for rel in drop_local:
        p = LOCAL / rel
        if safe_rel(rel) and p.is_file():
            p.unlink()
        info.pop(rel, None)

    # 远端 .gitkeep 对应的空目录，本地也建出来（不落地 .gitkeep 文件）
    for k in keeps:
        d = k[:-len(GITKEEP)].rstrip("/")
        if d and safe_rel(d):
            (LOCAL / d).mkdir(parents=True, exist_ok=True)

    # 构造 tree 条目
    known = set(remote.values())
    entries = []

    # 空目录占位：本地空目录 → 远端补 .gitkeep；本地不再空 → 删掉多余 .gitkeep
    empty_blob_sha = None
    want_keeps = {f"{d}/{GITKEEP}" for d in empties if d}
    for rel in sorted(want_keeps - keeps):
        if empty_blob_sha is None:
            empty_blob_sha = api("POST", "/git/blobs",
                                 body={"content": "", "encoding": "utf-8"})["sha"]
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": empty_blob_sha})
    local_dirs_with_files = {k.rsplit("/", 1)[0] for k in local if "/" in k}
    for rel in sorted(keeps - want_keeps):
        d = rel[:-len(GITKEEP)].rstrip("/")
        if d in local_dirs_with_files:
            entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": None})

    for rel in drop_remote:
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": None})

    # 上传：内容去重，相同内容只创建一次 blob
    for i, rel in enumerate(push, 1):
        if not safe_rel(rel):
            continue
        data = (LOCAL / rel).read_bytes()
        sha = blob_sha(data)
        if sha not in known:
            b = api("POST", "/git/blobs",
                    body={"content": base64.b64encode(data).decode(), "encoding": "base64"},
                    timeout=240)
            known.add(b["sha"])
            sha = b["sha"]
            time.sleep(SLEEP_PER_BLOB)
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": sha})
        info[rel] = {
            "sha": blob_sha(data),
            "size": len(data),
            "mtime_ns": (LOCAL / rel).stat().st_mtime_ns,
        }
        if i % 200 == 0:
            print(f"[push] {i}/{len(push)}", flush=True)

    # 分批提交，中途限流也不会丢已提交的进度
    for i in range(0, len(entries), BATCH):
        chunk = entries[i:i + BATCH]
        parent, tree_sha = flush(chunk, parent, tree_sha)
        print(f"[commit] 批次 {i // BATCH + 1}/{(len(entries) + BATCH - 1) // BATCH}"
              f" · {len(chunk)} 条 · {parent[:8]}", flush=True)

    save_state(info)
    print(f"[done] 同步完成 · 本地 {len(info)} 文件 · "
          f"上传 {len(push)} · 回灌 {len(pull)} · 冲突 {len(conflicts)} · "
          f"空目录占位 {len(want_keeps)}")
    print(f"[done] 镜像仓 https://github.com/{OWNER}/{REPO}/tree/{BRANCH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
