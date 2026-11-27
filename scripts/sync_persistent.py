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
import threading
import hashlib
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests

OWNER = os.getenv("FB_MIRROR_OWNER", "zjh1934")
REPO = os.getenv("FB_MIRROR_REPO", "filebridge-persistent")
BRANCH = os.getenv("FB_MIRROR_BRANCH", "main")
LOCAL = Path(os.getenv("FB_PERSISTENT_DIR", "/data/user_persistent_data")).resolve()
STATE = LOCAL / ".filebridge_sync_state.json"        # 只记「已确认同步」的基准
SCAN_CACHE = LOCAL / ".filebridge_scan_cache.json"   # 只记扫描加速用的 sha，不是同步状态
CONFLICTS = Path(os.getenv("FB_CONFLICT_DIR", "/data/workspace/conflicts"))
TOKEN = os.getenv("FILEBRIDGE_GH_TOKEN", "").strip()

EXCLUDE_TOP = {".filebridge_sync_state.json", ".filebridge_scan_cache.json"}  # 内部状态不进镜像
GITKEEP = ".gitkeep"
REMOTE_META = ".filebridge-mirror-info.md"        # 镜像仓元数据，不回灌本地
BATCH = 300                                       # 每批 tree 条目数
UPLOAD_WORKERS = 8                                # 并发上传（太高易触发 secondary limit）
RATE_GATE = threading.Event()                     # core 用尽时只让一个线程等待
RATE_GATE.set()
BLOB_CACHE = Path(os.getenv("FB_BLOB_CACHE", "/data/workspace/.persistent_blob_cache.json"))

# 每线程一个长连接 Session：避免每个 blob 都重新 TLS 握手（之前慢的主因之一）
_TLS = threading.local()

def http_session() -> requests.Session:
    s = getattr(_TLS, "s", None)
    if s is None:
        s = requests.Session()
        _TLS.s = s
    return s

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
            resp = http_session().request(method, url, headers=hdr, params=params,
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
        # core 限额用尽 / 快用尽：只让一个线程真正等待，其余等广播，避免 8 个线程刷屏
        rem = resp.headers.get("X-RateLimit-Remaining")
        low = rem is not None and rem.isdigit() and int(rem) < 80
        if (resp.status_code == 403 and rem == "0") or low:
            reset = int(resp.headers.get("X-RateLimit-Reset", "0") or "0")
            wait = max(10, reset - int(time.time()) + 15)
            if RATE_GATE.is_set():
                RATE_GATE.clear()
                print(f"[rate-limit] 配额不足(remaining={rem})，等待重置 {wait}s", flush=True)
                time.sleep(min(wait, 3600))
                RATE_GATE.set()
            else:
                RATE_GATE.wait(timeout=min(wait + 30, 3660))
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


def load_meta() -> dict:
    """整份状态（含分组进度）。兼容只有 files 字段的旧格式。"""
    if not STATE.exists():
        return {"files": {}, "groups": {}}
    try:
        d = json.loads(STATE.read_text("utf-8"))
        d.setdefault("files", {})
        d.setdefault("groups", {})
        return d
    except Exception:
        return {"files": {}, "groups": {}}


def load_scan_cache() -> dict:
    try:
        return json.loads(SCAN_CACHE.read_text("utf-8"))
    except Exception:
        return {}


def save_scan_cache(info: dict):
    """本地现状只是扫描加速缓存，绝不能当成「已同步基准」。"""
    try:
        SCAN_CACHE.write_text(json.dumps(info, ensure_ascii=False), "utf-8")
    except Exception:
        pass


def save_state(confirmed: dict, groups: dict | None = None):
    """每批提交后调用一次，中途停掉也不丢进度。
    confirmed 必须是「远端已经确认收到」的文件，不能传本地扫描结果。
    """
    meta = load_meta()
    meta["files"] = confirmed
    if groups is not None:
        meta["groups"] = groups
    meta["updated"] = time.time()
    meta["repo"] = f"{OWNER}/{REPO}"
    STATE.write_text(json.dumps(meta, ensure_ascii=False), "utf-8")


def group_of(rel: str) -> str:
    """顶层目录即一个「项目」；根下散文件归到 _root。"""
    parts = Path(rel).parts
    return parts[0] if len(parts) > 1 else "_root"


def belongs(rel: str, group: str | None) -> bool:
    return group is None or group == "all" or group_of(rel) == group


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


def flush(entries, parent, tree_sha, label: str = "全量增量"):
    if not entries:
        return parent, tree_sha
    payload = {"tree": entries}
    if tree_sha:
        payload["base_tree"] = tree_sha
    t = api("POST", "/git/trees", body=payload)
    c = api("POST", "/git/commits", body={
        "message": f"FileBridge: 同步持久区 [{label}] [skip ci]",
        "tree": t["sha"],
        "parents": [parent] if parent else [],
    })
    if parent is None:
        api("POST", "/git/refs", body={"ref": f"refs/heads/{BRANCH}", "sha": c["sha"]})
    else:
        api("PATCH", f"/git/refs/heads/{BRANCH}", body={"sha": c["sha"]})
    return c["sha"], c["tree"]["sha"]


def main() -> int:
    ap = argparse.ArgumentParser(
        description="持久区 ↔ 私有镜像仓：首次可逐项目同步，后续靠三向比较自动检测变化"
    )
    ap.add_argument("action", nargs="?", default="sync",
                    choices=["sync", "status", "groups"])
    ap.add_argument("target", nargs="?", default=None,
                    help="项目名（顶层目录，或 _root）；不填 = 所有项目做增量检测")
    args = ap.parse_args()

    if not TOKEN:
        print("需要环境变量 FILEBRIDGE_GH_TOKEN", file=sys.stderr)
        return 2
    LOCAL.mkdir(parents=True, exist_ok=True)

    remote, parent, tree_sha, keeps = remote_snapshot()
    meta = load_meta()
    confirmed = dict(meta["files"])          # 已确认同步的基准（不是本地扫描结果）
    groups_done = meta["groups"]
    info, empties = local_scan(load_scan_cache())
    save_scan_cache(info)
    base = {k: v["sha"] for k, v in confirmed.items()}
    local = {k: v["sha"] for k, v in info.items()}

    # 远端与本地本来就一致的文件也算「已同步」，后续才能正确识别单侧改动
    for rel in local:
        if rel in remote and local[rel] == remote[rel]:
            confirmed[rel] = info[rel]

    # 各项目清单（按体积升序，首次同步先小后大）
    inventory: dict[str, dict[str, int]] = {}
    for rel, m in info.items():
        g = inventory.setdefault(group_of(rel), {"files": 0, "bytes": 0})
        g["files"] += 1
        g["bytes"] += m["size"]
    order = sorted(inventory, key=lambda g: inventory[g]["bytes"])

    if args.action == "groups":
        print(f"持久区项目清单（共 {len(info)} 文件 / {sum(v['bytes'] for v in inventory.values())/1048576:.1f} MB）")
        print(f"{'项目':<14}{'文件':>8}{'大小':>12}   首次同步")
        for g in order:
            v = inventory[g]
            done = "已完成" if groups_done.get(g, {}).get("done") else "待同步"
            print(f"{g:<14}{v['files']:>8}{v['bytes']/1048576:>10.1f} MB   {done}")
        print("\n逐个跑： python3 sync_persistent.py sync <项目>")
        print("之后只需： python3 sync_persistent.py sync   （自动检测所有变化）")
        return 0

    group = args.target
    if group and group != "all" and group not in inventory:
        print(f"没有这个项目：{group}；可用：{', '.join(order)}", file=sys.stderr)
        return 3
    label = group if group and group != "all" else "全量增量"

    push, pull, drop_remote, drop_local, conflicts = decide(base, local, remote)
    # 逐项目：只处理属于该组的条目，其余留到下一轮
    push = [r for r in push if belongs(r, group)]
    pull = [r for r in pull if belongs(r, group)]
    drop_remote = [r for r in drop_remote if belongs(r, group)]
    drop_local = [r for r in drop_local if belongs(r, group)]
    conflicts = [r for r in conflicts if belongs(r, group)]

    print(f"[state] 本地 {len(local)} 文件 / 远端 {len(remote)} 文件 / 空目录 {len(empties)}")
    print(f"[plan:{label}] 上传 {len(push)} · 回灌 {len(pull)} · 删远端 {len(drop_remote)}"
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
            confirmed[rel] = info[rel]
        if i % 100 == 0:
            print(f"[pull] {i}/{len(pull)}", flush=True)

    # 远端删除 → 本地删除
    for rel in drop_local:
        fg = LOCAL / rel
        if safe_rel(rel) and fg.is_file():
            fg.unlink()
        info.pop(rel, None)

    # 远端 .gitkeep 对应的空目录，本地也建出来（不落地 .gitkeep 文件）
    for k in keeps:
        d = k[:-len(GITKEEP)].rstrip("/")
        if d and safe_rel(d) and belongs(d, group):
            (LOCAL / d).mkdir(parents=True, exist_ok=True)

    known = set(remote.values())
    try:
        known |= set(json.loads(BLOB_CACHE.read_text("utf-8")))
        print(f"[cache] 复用 {len(known)} 个已创建 blob", flush=True)
    except Exception:
        pass
    entries = []

    # 空目录占位：本地空目录 → 远端补 .gitkeep；本地不再空 → 删掉多余 .gitkeep
    empty_blob_sha = None
    want_keeps = {f"{d}/{GITKEEP}" for d in empties if d and belongs(d, group)}
    for rel in sorted(want_keeps - keeps):
        if empty_blob_sha is None:
            empty_blob_sha = api("POST", "/git/blobs",
                                 body={"content": "", "encoding": "utf-8"})["sha"]
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": empty_blob_sha})
    local_dirs_with_files = {k.rsplit("/", 1)[0] for k in local if "/" in k}
    for rel in sorted(keeps - want_keeps):
        d = rel[:-len(GITKEEP)].rstrip("/")
        if d in local_dirs_with_files and belongs(d, group):
            entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": None})

    for rel in drop_remote:
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": None})

    # 并发上传：worker 内才读文件；每满一批立刻提交并落盘状态，断网也不丢进度
    known_lock = threading.Lock()
    global_new: list[str] = []
    committed = 0

    def upload_one(rel: str):
        if not safe_rel(rel):
            return None
        data = (LOCAL / rel).read_bytes()
        sha = blob_sha(data)
        with known_lock:
            exists = sha in known
        if not exists:
            b = api("POST", "/git/blobs",
                    body={"content": base64.b64encode(data).decode(), "encoding": "base64"},
                    timeout=240)
            with known_lock:
                known.add(b["sha"])
            sha = b["sha"]
            global_new.append(sha)
        st = (LOCAL / rel).stat()
        return ({"path": rel, "mode": "100644", "type": "blob", "sha": sha},
                {"sha": blob_sha(data), "size": len(data), "mtime_ns": st.st_mtime_ns})

    def cache_blobs():
        if not global_new:
            return
        try:
            old = set(json.loads(BLOB_CACHE.read_text("utf-8"))) if BLOB_CACHE.exists() else set()
            BLOB_CACHE.write_text(json.dumps(sorted(old | set(global_new))), "utf-8")
        except Exception:
            pass
        global_new.clear()

    with ThreadPoolExecutor(max_workers=UPLOAD_WORKERS) as ex:
        futures = [ex.submit(upload_one, rel) for rel in push]
        for i, fut in enumerate(as_completed(futures), 1):
            res = fut.result()
            if res is None:
                continue
            entry, item = res
            entries.append(entry)
            info[entry["path"]] = item
            confirmed[entry["path"]] = item              # 只有提交成功才算已同步
            if len(entries) >= BATCH:
                parent, tree_sha = flush(entries, parent, tree_sha, label)
                committed += 1
                save_state(confirmed, groups_done)      # 每批落盘，可随时中断重跑
                print(f"[commit] {label} · {i}/{len(push)} · {parent[:8]}", flush=True)
                entries = []
            if i % 200 == 0:
                cache_blobs()
                print(f"[push] {i}/{len(push)}", flush=True)

    cache_blobs()

    if entries:
        parent, tree_sha = flush(entries, parent, tree_sha, label)
        committed += 1
        print(f"[commit] {label} · 收尾 {len(entries)} 条 · {parent[:8]}", flush=True)
        entries = []

    # 标记该组首次同步完成，后续 sync 不带项目名就自动做全量增量检测
    if group and group != "all":
        groups_done[group] = {
            "done": True,
            "files": inventory[group]["files"],
            "bytes": inventory[group]["bytes"],
            "at": time.time(),
        }
    save_state(confirmed, groups_done)
    save_scan_cache(info)

    print(f"[done:{label}] 提交 {committed} 次 · 上传 {len(push)} · 回灌 {len(pull)}"
          f" · 冲突 {len(conflicts)} · 空目录占位 {len(want_keeps)}")
    if group and group != "all":
        rest = [g for g in order if not groups_done.get(g, {}).get("done")]
        print(f"[next] 剩余项目: {', '.join(rest) if rest else '无，全部完成'}"
              f"  → python3 sync_persistent.py sync {rest[0] if rest else ''}".rstrip())
    else:
        done = sum(1 for g in order if groups_done.get(g, {}).get("done"))
        print(f"[incremental] 已完成首次同步的项目 {done}/{len(order)}；本次只处理检测到的差异")
    print(f"[done] 镜像仓 https://github.com/{OWNER}/{REPO}/tree/{BRANCH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
