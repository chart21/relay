"""Files the phone shares into a session (Android share sheet): stored on the Desktop under
~/.local/share/notifyd/uploads/<date>/, outside every repo, so an agent can read them by path. Kept 30 days."""
from __future__ import annotations

import base64
import os
import re
import shutil
import time
import uuid
from pathlib import Path

ROOT = Path(os.environ.get("NOTIFYD_UPLOADS", "~/.local/share/notifyd/uploads")).expanduser()
MAX_BYTES = 50 * 1024 * 1024
KEEP_DAYS = 30
_open: dict[str, dict] = {}  # upload_id -> {path, part, seq, size, expected, started}


def safe_name(name: str) -> str:
    base = os.path.basename(name.replace("\\", "/")).strip() or "file"
    base = re.sub(r"[^\w.\- ]+", "_", base)[:120].strip(" .") or "file"
    return base


def _cleanup(now: float) -> None:
    if ROOT.exists():
        for d in ROOT.iterdir():
            if d.is_dir() and now - d.stat().st_mtime > KEEP_DAYS * 86400:
                shutil.rmtree(d, ignore_errors=True)
    for uid in [u for u, o in _open.items() if now - o["started"] > 3600]:  # abandoned uploads
        _open.pop(uid)["part"].unlink(missing_ok=True)


def begin(name: str, size: int = 0) -> dict:
    if size > MAX_BYTES:
        raise ValueError(f"file too large ({size // 1048576} MB, max {MAX_BYTES // 1048576} MB)")
    now = time.time()
    _cleanup(now)
    day = ROOT / time.strftime("%Y-%m-%d")
    day.mkdir(parents=True, exist_ok=True)
    fname = safe_name(name)
    path, stem, ext, n = day / fname, Path(fname).stem, Path(fname).suffix, 1
    while path.exists() or any(o["path"] == path for o in _open.values()):
        n += 1
        path = day / f"{stem}-{n}{ext}"
    uid = uuid.uuid4().hex[:12]
    part = path.with_name(path.name + ".part")
    part.write_bytes(b"")
    _open[uid] = {"path": path, "part": part, "seq": 0, "size": 0, "expected": size, "started": now}
    return {"upload_id": uid}


def chunk(uid: str, seq: int, data_b64: str) -> dict:
    o = _open.get(uid)
    if not o:
        raise ValueError("unknown or expired upload")
    if seq != o["seq"]:
        raise ValueError(f"chunk {seq} out of order (expected {o['seq']})")
    data = base64.b64decode(data_b64)
    if o["size"] + len(data) > MAX_BYTES:
        _open.pop(uid)["part"].unlink(missing_ok=True)
        raise ValueError("file too large")
    with o["part"].open("ab") as f:
        f.write(data)
    o["seq"] += 1
    o["size"] += len(data)
    return {"received": o["size"]}


def end(uid: str) -> dict:
    o = _open.pop(uid, None)
    if not o:
        raise ValueError("unknown or expired upload")
    if o["expected"] and o["size"] != o["expected"]:
        o["part"].unlink(missing_ok=True)
        raise ValueError(f"incomplete upload ({o['size']} of {o['expected']} bytes)")
    o["part"].rename(o["path"])
    return {"path": str(o["path"]), "size": o["size"]}
