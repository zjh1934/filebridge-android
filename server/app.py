"""
FileBridge —— 沙盒持久目录的文件网关

设计要点：
  1. 客户端永远只传 file_id，不传绝对路径；服务端用 index.json 做 id -> 相对路径映射。
  2. 所有落盘路径都经过 _safe()：resolve 后必须仍在 ROOT 内，拒绝 .. 与软链逃逸。
  3. index.json 用「线程锁 + 跨进程 flock + 原子替换」保护，服务与同步脚本可并发写。
  4. 覆盖写用 sha256 当 ETag（If-Match），防多端互相覆盖。
  5. 打开走 HMAC 短时效预签名 URL，APP 不必把长期密钥交给系统应用。
  6. 删除默认进回收站，purge=1 才真删。

零业务框架依赖之外的东西很少：fastapi + uvicorn + python-multipart。
"""
from __future__ import annotations

import fcntl
import hashlib
import hmac
import json
import mimetypes
import os
import shutil
import threading
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, Header, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel

# ---------------- 配置 ----------------
BASE = Path(os.getenv("FILEBRIDGE_BASE", "/data/user_persistent_data/filebridge")).resolve()
ROOT = BASE / "files"                 # 真正存放文件的持久目录
TRASH = BASE / ".trash"               # 回收站（不在 ROOT 内，不会被列出）
INDEX = BASE / "index.json"           # id -> 元信息
TRASH_INDEX = TRASH / "index.json"
LOCKFILE = BASE / ".lock"
TOKEN = os.getenv("FILEBRIDGE_TOKEN", "demo-token-change-me")
SECRET = os.getenv("FILEBRIDGE_SECRET", TOKEN + "-sign").encode()
WORKSPACE = Path(os.getenv("FILEBRIDGE_WORKSPACE", "/data/workspace")).resolve()
WEB_HTML = Path(__file__).resolve().parent.parent / "web" / "index.html"
SIGN_TTL = int(os.getenv("FILEBRIDGE_SIGN_TTL", "300"))

ROOT.mkdir(parents=True, exist_ok=True)
TRASH.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="FileBridge", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_methods=["*"], allow_headers=["*"], expose_headers=["*"],
)

_THREAD_LOCK = threading.RLock()


# ---------------- 索引存储 ----------------
@contextmanager
def _locked():
    """同进程多线程 + 跨进程（服务 / 同步脚本）双重互斥。"""
    with _THREAD_LOCK:
        with LOCKFILE.open("a+") as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(f, fcntl.LOCK_UN)


def _load(path: Path | None = None) -> dict[str, Any]:
    p = path or INDEX
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text("utf-8"))
    except Exception:
        return {}


def _save(db: dict[str, Any], path: Path | None = None) -> None:
    p = path or INDEX
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(db, ensure_ascii=False, indent=2), "utf-8")
    os.replace(tmp, p)                       # 原子替换


def _sha(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _mime(name: str) -> str:
    return mimetypes.guess_type(name)[0] or "application/octet-stream"


def _meta(fid: str, rec: dict, p: Path | None = None) -> dict:
    is_dir = rec.get("type") == "dir"
    real = p or _safe(rec["rel"])
    return {
        "id": fid,
        "name": rec["name"],
        "parent": rec.get("parent", ""),
        "rel": rec["rel"],
        "type": "dir" if is_dir else "file",
        "size": 0 if is_dir else (real.stat().st_size if real.exists() else 0),
        "sha256": "" if is_dir else rec.get("sha256", ""),
        "mime": "inode/directory" if is_dir else rec.get("mime") or _mime(rec["name"]),
        "updated_at": rec.get("updated_at", 0),
    }


# ---------------- 安全与鉴权 ----------------
def _auth(x_api_key: str | None, token: str | None = None) -> None:
    key = x_api_key or token
    if not key or not hmac.compare_digest(key, TOKEN):
        raise HTTPException(401, "invalid api key")


def _clean_name(name: str) -> str:
    n = Path((name or "").replace("\\", "/")).name.strip()
    if not n or n in (".", ".."):
        raise HTTPException(400, "invalid name")
    return n


def _safe(rel: str) -> Path:
    """把相对 ROOT 的路径解析成绝对路径，并确保没跑出去。"""
    p = (ROOT / (rel or "")).resolve()
    if p != ROOT and ROOT not in p.parents:
        raise HTTPException(400, "path escape rejected")
    return p


def _parent_dir(parent: str) -> Path:
    parent = (parent or "").strip("/")
    if parent and any(seg in (".", "..") for seg in parent.split("/") if seg):
        raise HTTPException(400, "invalid parent")
    return _safe(parent)


def _sign(fid: str, exp: int) -> str:
    return hmac.new(SECRET, f"{fid}:{exp}".encode(), hashlib.sha256).hexdigest()


def _check_sig(fid: str, exp: str | None, sig: str | None) -> None:
    if not exp or not sig or not exp.isdigit() or int(exp) < time.time():
        raise HTTPException(403, "signature expired")
    if not hmac.compare_digest(_sign(fid, int(exp)), sig):
        raise HTTPException(403, "bad signature")


def _get(db: dict, fid: str) -> dict:
    rec = db.get(fid)
    if not rec:
        raise HTTPException(404, "file not found")
    return rec


# ---------------- 接口 ----------------
@app.get("/health")
def health():
    return {"ok": True, "root": str(ROOT), "files": len(_load())}


@app.get("/files")
def list_files(
    parent: str = Query(""),
    x_api_key: str | None = Header(None),
    token: str | None = None,
):
    """列目录：parent 是相对路径，返回 dirs + files。"""
    _auth(x_api_key, token)
    prefix = (parent or "").strip("/")
    _parent_dir(prefix)                       # 先校验，挡住 ../..
    db = _load()
    files, dirs = [], {}
    for fid, rec in db.items():
        if rec.get("type") == "dir":
            if rec.get("parent", "") == prefix:
                dirs[rec["name"]] = _meta(fid, rec)
            continue
        rel = rec["rel"]
        if prefix:
            if not rel.startswith(prefix + "/"):
                continue
            rest = rel[len(prefix) + 1:]
        else:
            rest = rel
        if not rest:
            continue
        if "/" in rest:                       # 由深层文件聚合出虚拟子目录
            sub = rest.split("/", 1)[0]
            dirs.setdefault(sub, {
                "id": "", "name": sub, "parent": prefix, "rel": (prefix + "/" + sub).strip("/"),
                "type": "dir", "size": 0, "sha256": "", "mime": "inode/directory", "updated_at": 0,
            })
            continue
        p = _safe(rel)
        if p.exists():
            files.append(_meta(fid, rec, p))
    return {"parent": prefix, "dirs": sorted(dirs.values(), key=lambda x: x["name"]),
            "files": sorted(files, key=lambda x: x["name"])}


@app.get("/files/{fid}")
def stat(fid: str, x_api_key: str | None = Header(None)):
    _auth(x_api_key)
    db = _load()
    return _meta(fid, _get(db, fid))


@app.get("/files/{fid}/open")
def open_url(fid: str, request: Request, x_api_key: str | None = Header(None)):
    """返回短时效预签名 URL，交给系统应用 / 浏览器直接打开，不必带长期密钥。"""
    _auth(x_api_key)
    _get(_load(), fid)
    exp = int(time.time()) + SIGN_TTL
    url = f"{request.base_url}files/{fid}/content?exp={exp}&sig={_sign(fid, exp)}"
    return {"url": url, "expires_in": SIGN_TTL}


@app.get("/files/{fid}/content")
def content(
    fid: str,
    exp: str | None = None,
    sig: str | None = None,
    download: int = 0,
    x_api_key: str | None = Header(None),
    token: str | None = None,
):
    if exp or sig:
        _check_sig(fid, exp, sig)
    else:
        _auth(x_api_key, token)
    rec = _get(_load(), fid)
    if rec.get("type") == "dir":
        raise HTTPException(400, "cannot download a directory")
    p = _safe(rec["rel"])
    if not p.exists():
        raise HTTPException(410, "file missing on disk")
    kw: dict[str, Any] = {"media_type": rec.get("mime") or _mime(rec["name"])}
    if download:
        kw["filename"] = rec["name"]
    return FileResponse(p, **kw)


@app.post("/files")
async def upload(
    file: UploadFile = File(...),
    parent: str = Form(""),
    x_api_key: str | None = Header(None),
):
    _auth(x_api_key)
    name = _clean_name(file.filename or "unnamed")
    d = _parent_dir(parent)
    d.mkdir(parents=True, exist_ok=True)
    fid = uuid.uuid4().hex[:16]
    dst = d / name
    if dst.exists():                          # 同名不覆盖，追加短 id
        name = f"{Path(name).stem}_{fid[:6]}{Path(name).suffix}"
        dst = d / name
    with dst.open("wb") as f:
        shutil.copyfileobj(file.file, f)
    rec = {
        "name": name, "parent": (parent or "").strip("/"),
        "rel": str(dst.relative_to(ROOT)), "type": "file",
        "sha256": _sha(dst), "mime": _mime(name), "updated_at": time.time(),
    }
    with _locked():
        db = _load()
        db[fid] = rec
        _save(db)
    return _meta(fid, rec, dst)


class DirBody(BaseModel):
    name: str
    parent: str = ""


@app.post("/dirs")
def mkdir(body: DirBody, x_api_key: str | None = Header(None)):
    _auth(x_api_key)
    name = _clean_name(body.name)
    d = _parent_dir(body.parent) / name
    if d.exists():
        raise HTTPException(409, "already exists")
    d.mkdir(parents=True, exist_ok=True)
    fid = "d_" + uuid.uuid4().hex[:12]
    rec = {"name": name, "parent": (body.parent or "").strip("/"),
           "rel": str(d.relative_to(ROOT)), "type": "dir",
           "sha256": "", "mime": "inode/directory", "updated_at": time.time()}
    with _locked():
        db = _load()
        db[fid] = rec
        _save(db)
    return _meta(fid, rec, d)


@app.put("/files/{fid}")
async def update(
    fid: str,
    file: UploadFile = File(...),
    if_match: str | None = Header(None),
    x_api_key: str | None = Header(None),
):
    """覆盖写。If-Match 传上次拿到的 sha256，不一致返回 409（乐观锁）。"""
    _auth(x_api_key)
    with _locked():
        db = _load()
        rec = _get(db, fid)
        if rec.get("type") == "dir":
            raise HTTPException(400, "cannot overwrite a directory")
        p = _safe(rec["rel"])
        if if_match and if_match != rec.get("sha256", ""):
            raise HTTPException(409, "version conflict: reload and retry")
        tmp = p.with_name(p.name + ".uploading")
        with tmp.open("wb") as f:
            shutil.copyfileobj(file.file, f)
        os.replace(tmp, p)
        rec["sha256"] = _sha(p)
        rec["updated_at"] = time.time()
        rec["mime"] = _mime(rec["name"])
        _save(db)
    return _meta(fid, rec, p)


class PatchBody(BaseModel):
    name: str | None = None
    parent: str | None = None


@app.patch("/files/{fid}")
def patch(fid: str, body: PatchBody, x_api_key: str | None = Header(None)):
    """重命名 / 移动。目录暂不支持移动（子项 rel 需级联更新）。"""
    _auth(x_api_key)
    with _locked():
        db = _load()
        rec = _get(db, fid)
        if rec.get("type") == "dir":
            raise HTTPException(400, "directory move not supported in v0.1")
        new_name = _clean_name(body.name) if body.name else rec["name"]
        new_parent = (body.parent if body.parent is not None else rec.get("parent", "")).strip("/")
        dst = _parent_dir(new_parent) / new_name
        dst.parent.mkdir(parents=True, exist_ok=True)
        src = _safe(rec["rel"])
        if dst.exists() and dst != src:
            raise HTTPException(409, "target exists")
        src.rename(dst)
        rec.update({"name": new_name, "parent": new_parent,
                    "rel": str(dst.relative_to(ROOT)), "updated_at": time.time()})
        _save(db)
    return _meta(fid, rec, dst)


@app.delete("/files/{fid}")
def delete(fid: str, purge: int = 0, x_api_key: str | None = Header(None)):
    _auth(x_api_key)
    with _locked():
        db = _load()
        rec = _get(db, fid)
        p = _safe(rec["rel"])
        if purge:
            if rec.get("type") == "dir":
                if p.exists() and any(p.iterdir()):
                    raise HTTPException(409, "directory not empty")
                if p.exists():
                    p.rmdir()
            else:
                p.unlink(missing_ok=True)
            del db[fid]
            _save(db)
            return {"ok": True, "purged": True}
        # 软删：移进 .trash，并写回收站索引
        tdir = TRASH / fid
        tdir.mkdir(parents=True, exist_ok=True)
        target = tdir / rec["name"]
        if p.exists():
            shutil.move(str(p), str(target))
        rec["deleted_at"] = time.time()
        rec["trash_rel"] = str(target.relative_to(BASE))
        tdb = _load(TRASH_INDEX)
        tdb[fid] = rec
        _save(tdb, TRASH_INDEX)
        del db[fid]
        _save(db)
    return {"ok": True, "purged": False}


@app.get("/trash")
def trash(x_api_key: str | None = Header(None)):
    _auth(x_api_key)
    return sorted(
        [{"id": k, **{kk: v.get(kk) for kk in ("name", "parent", "rel", "type", "deleted_at")}}
         for k, v in _load(TRASH_INDEX).items()],
        key=lambda x: x.get("deleted_at") or 0, reverse=True,
    )


@app.post("/trash/{fid}/restore")
def restore(fid: str, x_api_key: str | None = Header(None)):
    _auth(x_api_key)
    with _locked():
        tdb = _load(TRASH_INDEX)
        rec = tdb.get(fid)
        if not rec:
            raise HTTPException(404, "not in trash")
        src = (BASE / rec["trash_rel"]).resolve()
        dst = _parent_dir(rec.get("parent", "")) / rec["name"]
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists():
            raise HTTPException(409, "target exists")
        if src.exists():
            shutil.move(str(src), str(dst))
        rec.pop("deleted_at", None)
        rec.pop("trash_rel", None)
        rec["rel"] = str(dst.relative_to(ROOT))
        rec["updated_at"] = time.time()
        db = _load()
        db[fid] = rec
        _save(db)
        del tdb[fid]
        _save(tdb, TRASH_INDEX)
    return _meta(fid, rec, dst)


@app.delete("/trash/{fid}")
def purge_trash(fid: str, x_api_key: str | None = Header(None)):
    """彻底删除回收站里的文件，不可恢复。"""
    _auth(x_api_key)
    with _locked():
        tdb = _load(TRASH_INDEX)
        rec = tdb.get(fid)
        if not rec:
            raise HTTPException(404, "not in trash")
        p = (BASE / rec["trash_rel"]).resolve()
        if BASE not in p.parents:
            raise HTTPException(400, "bad trash path")
        p.unlink(missing_ok=True)
        try:
            p.parent.rmdir()                  # 清掉空的 fid 目录
        except OSError:
            pass
        del tdb[fid]
        _save(tdb, TRASH_INDEX)
    return {"ok": True, "purged": True}


@app.post("/sync")
def sync(x_api_key: str | None = Header(None)):
    """把对话工作区 /data/workspace 顶层的产物登记进持久区。"""
    _auth(x_api_key)
    added = []
    if WORKSPACE.exists():
        for src in sorted(WORKSPACE.iterdir()):
            if not src.is_file() or src.name.startswith(".") or src.name.startswith("filebridge"):
                continue
            name = _clean_name(src.name)
            dst = ROOT / name
            if dst.exists() and _sha(dst) == _sha(src):
                continue
            # virtiofs 不允许 utime/copystat，只拷内容即可
            shutil.copyfile(src, dst)
            with _locked():
                db = _load()
                fid = next((k for k, v in db.items()
                            if v.get("rel") == name and v.get("type") != "dir"), None)
                fid = fid or uuid.uuid4().hex[:16]
                db[fid] = {"name": name, "parent": "", "rel": name, "type": "file",
                           "sha256": _sha(dst), "mime": _mime(name), "updated_at": time.time()}
                _save(db)
            added.append(name)
    return {"added": added}


@app.get("/", response_class=HTMLResponse)
def index_page():
    if WEB_HTML.exists():
        return HTMLResponse(WEB_HTML.read_text("utf-8"))
    return HTMLResponse("<h3>FileBridge API is running</h3><p>web/index.html not found</p>")
