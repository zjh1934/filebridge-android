#!/usr/bin/env python3
"""
对运行中的 FileBridge 服务做端到端验证，并生成 VERIFY.md（真实结果，不是手写结论）。

先启动服务：  bash scripts/start_server.sh
再执行：      python3 scripts/verify.py
"""
from __future__ import annotations

import os
import re
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

URL = os.getenv("FILEBRIDGE_URL", "http://127.0.0.1:8765").rstrip("/")
TOKEN = os.getenv("FILEBRIDGE_TOKEN", "demo-token-change-me")
H = {"x-api-key": TOKEN}
BASE = Path(os.getenv("FILEBRIDGE_BASE", "/data/user_persistent_data/filebridge")).resolve()
WS = Path(os.getenv("FILEBRIDGE_WORKSPACE", "/data/workspace")).resolve()
OUT = Path(__file__).resolve().parent.parent / "VERIFY.md"

S = requests.Session()
st: dict = {}
results: list[tuple[str, str, str]] = []
R = uuid.uuid4().hex[:6]


def expect(cond, msg):
    if not cond:
        raise AssertionError(msg)


def check(name, fn):
    try:
        d = fn()
        results.append((name, "PASS", str(d)))
        print(f"[PASS] {name}: {d}")
    except Exception as e:
        results.append((name, "FAIL", f"{type(e).__name__}: {e}"))
        print(f"[FAIL] {name}: {type(e).__name__}: {e}")


def lst(parent=""):
    r = S.get(f"{URL}/files", headers=H, params={"parent": parent})
    expect(r.status_code == 200, r.text)
    return r.json()


def t_health():
    j = S.get(f"{URL}/health").json()
    expect(j.get("ok") is True, "health")
    return f"root={j['root']}, indexed={j['files']}"


def t_auth():
    r = S.get(f"{URL}/files", headers={"x-api-key": "wrong-token"})
    expect(r.status_code == 401, f"expected 401, got {r.status_code}")
    return "错误密钥 -> 401"


def t_mkdir():
    dname = f"verify_docs_{R}"
    r = S.post(f"{URL}/dirs", headers=H, json={"name": dname})
    expect(r.status_code == 200, r.text)
    st["dname"], st["did"] = dname, r.json()["id"]
    return f"建目录 {dname}"


def t_upload_root():
    fname, data = f"hello_{R}.txt", b"hello filebridge\n"
    r = S.post(f"{URL}/files", headers=H, files={"file": (fname, data)}, data={"parent": ""})
    expect(r.status_code == 200, r.text)
    st["f"], st["data"] = r.json(), data
    return f"上传 {fname}, sha={r.json()['sha256'][:10]}"


def t_list_root():
    j = lst()
    expect(any(d["name"] == st["dname"] for d in j["dirs"]), "dir not listed")
    expect(any(f["id"] == st["f"]["id"] for f in j["files"]), "file not listed")
    return f"根目录 {len(j['dirs'])} 个子目录 / {len(j['files'])} 个文件"


def t_upload_nested():
    r = S.post(f"{URL}/files", headers=H,
               files={"file": (f"nested_{R}.txt", b"nested\n")},
               data={"parent": st["dname"]})
    expect(r.status_code == 200, r.text)
    st["nested"] = r.json()
    return f"子目录上传 rel={r.json()['rel']}"


def t_list_nested():
    j = lst(st["dname"])
    expect(any(f["id"] == st["nested"]["id"] for f in j["files"]), "nested not listed")
    return "子目录隔离正确"


def t_download():
    r = S.get(f"{URL}/files/{st['f']['id']}/content", headers=H)
    expect(r.status_code == 200 and r.content == st["data"], "content mismatch")
    return f"下载 {len(r.content)} 字节，内容一致"


def t_etag_conflict():
    r = S.put(f"{URL}/files/{st['f']['id']}", headers={**H, "If-Match": "deadbeef"},
              files={"file": ("x.txt", b"bad")})
    expect(r.status_code == 409, f"expected 409, got {r.status_code}: {r.text}")
    return "旧 ETag 覆盖 -> 409（乐观锁生效）"


def t_update_ok():
    new = b"hello filebridge v2\n"
    r = S.put(f"{URL}/files/{st['f']['id']}", headers={**H, "If-Match": st["f"]["sha256"]},
              files={"file": (st["f"]["name"], new)})
    expect(r.status_code == 200, r.text)
    expect(r.json()["sha256"] != st["f"]["sha256"], "sha did not change")
    st["f"], st["data"] = r.json(), new
    got = S.get(f"{URL}/files/{st['f']['id']}/content", headers=H).content
    expect(got == new, "updated content mismatch")
    return f"覆盖成功，新 sha={r.json()['sha256'][:10]}"


def t_presign():
    u = S.get(f"{URL}/files/{st['f']['id']}/open", headers=H).json()["url"]
    r = requests.get(u)
    expect(r.status_code == 200 and r.content == st["data"], "presigned get failed")
    bad = u[:-1] + ("0" if u[-1] != "0" else "1")
    expect(requests.get(bad).status_code == 403, "bad sig not rejected")
    old = re.sub(r"exp=\d+", "exp=1", u)
    expect(requests.get(old).status_code == 403, "expired sig not rejected")
    return "预签名 URL 免密钥可取；错签/过期 -> 403"


def t_traversal():
    r = S.post(f"{URL}/files", headers=H,
               files={"file": ("x.txt", b"x")}, data={"parent": "../../etc"})
    expect(r.status_code == 400, f"parent traversal not blocked: {r.status_code} {r.text}")
    evil = f"../evil_{R}.txt"
    r = S.post(f"{URL}/files", headers=H, files={"file": (evil, b"x")})
    expect(r.status_code == 200, r.text)
    rel = r.json()["rel"]
    expect(".." not in rel and "/" not in rel, f"filename not cleaned: {rel}")
    expect(not (BASE / rel).exists(), "file escaped ROOT")
    S.delete(f"{URL}/files/{r.json()['id']}", headers=H, params={"purge": 1})
    return f"parent=../../etc -> 400；文件名 ../ 被清洗为 {rel}"


def t_rename():
    newname = f"renamed_{R}.txt"
    r = S.patch(f"{URL}/files/{st['f']['id']}", headers=H, json={"name": newname})
    expect(r.status_code == 200 and r.json()["name"] == newname, r.text)
    st["f"] = r.json()
    return f"重命名为 {newname}"


def t_soft_delete():
    fid = st["f"]["id"]
    r = S.delete(f"{URL}/files/{fid}", headers=H, params={"purge": 0})
    expect(r.status_code == 200 and r.json()["purged"] is False, r.text)
    expect(not any(f["id"] == fid for f in lst()["files"]), "still in main list")
    expect(any(x["id"] == fid for x in S.get(f"{URL}/trash", headers=H).json()), "not in trash")
    return "软删后主列表消失、回收站可见"


def t_restore():
    fid = st["f"]["id"]
    r = S.post(f"{URL}/trash/{fid}/restore", headers=H)
    expect(r.status_code == 200, r.text)
    expect(any(f["id"] == fid for f in lst()["files"]), "not restored")
    return "从回收站恢复成功"


def t_purge():
    fid = st["f"]["id"]
    r = S.delete(f"{URL}/files/{fid}", headers=H, params={"purge": 1})
    expect(r.status_code == 200 and r.json()["purged"] is True, r.text)
    expect(S.get(f"{URL}/files/{fid}", headers=H).status_code == 404, "still stat-able")
    return "彻底删除 -> 404"


def t_trash_purge():
    nid = st["nested"]["id"]
    S.delete(f"{URL}/files/{nid}", headers=H, params={"purge": 0})
    r = S.delete(f"{URL}/trash/{nid}", headers=H)
    expect(r.status_code == 200, r.text)
    expect(not any(x["id"] == nid for x in S.get(f"{URL}/trash", headers=H).json()), "still in trash")
    return "回收站彻底删除接口可用"


def t_sync():
    tmp = WS / f"_fb_verify_{R}.txt"
    tmp.write_text("sync probe\n", "utf-8")
    try:
        r = S.post(f"{URL}/sync", headers=H)
        expect(r.status_code == 200, r.text)
        expect(tmp.name in r.json()["added"], f"not added: {r.text}")
        hit = next((f for f in lst()["files"] if f["name"] == tmp.name), None)
        expect(hit is not None, "synced file not listed")
        S.delete(f"{URL}/files/{hit['id']}", headers=H, params={"purge": 1})
        return f"/sync 登记 {tmp.name} 到持久区"
    finally:
        tmp.unlink(missing_ok=True)


def t_marker():
    m = BASE / "CROSS_SESSION_MARKER.txt"
    expect(m.exists(), "marker missing")
    expect("跨对话" in m.read_text("utf-8"), "marker content unexpected")
    return f"标记文件存在：{m}"


def cleanup():
    # 清掉本次验证建的目录记录（目录里的文件已被移走）
    S.delete(f"{URL}/files/{st['did']}", headers=H, params={"purge": 1})


def main():
    tests = [
        ("服务健康检查", t_health), ("错误密钥拦截", t_auth), ("新建目录", t_mkdir),
        ("上传文件", t_upload_root), ("根目录列表", t_list_root),
        ("子目录上传", t_upload_nested), ("子目录隔离", t_list_nested),
        ("下载内容一致", t_download), ("ETag 冲突 409", t_etag_conflict),
        ("带 ETag 覆盖写", t_update_ok), ("预签名打开与过期校验", t_presign),
        ("路径穿越与文件名清洗", t_traversal), ("重命名", t_rename),
        ("软删除", t_soft_delete), ("回收站恢复", t_restore), ("彻底删除", t_purge),
        ("回收站彻底删除", t_trash_purge), ("工作区同步 /sync", t_sync),
        ("跨对话标记文件", t_marker),
    ]
    for name, fn in tests:
        check(name, fn)
    try:
        cleanup()
    except Exception:
        pass

    passed = sum(1 for _, s, _ in results if s == "PASS")
    total = len(results)
    now = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S CST")
    lines = [
        "# FileBridge 实跑验证记录", "",
        f"- 执行时间：{now}",
        f"- 服务地址：`{URL}`",
        f"- 持久目录：`{BASE}`",
        f"- 结果：**{passed}/{total} 通过**", "",
        "| 检查项 | 结果 | 说明 |", "| --- | --- | --- |",
    ]
    for n, s, d in results:
        lines.append(f"| {n} | {s} | {d} |")
    lines += ["", "> 本文件由 `scripts/verify.py` 真实调用运行中的服务生成，非手写结论。", ""]
    OUT.write_text("\n".join(lines), "utf-8")
    print(f"\n{passed}/{total} PASS -> {OUT}")
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
