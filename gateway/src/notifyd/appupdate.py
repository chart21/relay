"""The Relay app updates itself over its SSH link: `notifyd publish-apk` stores a build here, hello_ok tells the phone
its version, and the `apk` ssh-entry command streams it (gzip; debug dex compresses to about half)."""
from __future__ import annotations

import gzip
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

from .config import STATE_DIR


def app_dir() -> Path:
    return STATE_DIR / "app"


def publish(apk: Path) -> dict:
    """Copy a built APK (with Gradle's output-metadata.json next to it) into the update store."""
    apk = apk.expanduser().resolve()
    meta = json.loads((apk.parent / "output-metadata.json").read_text())
    el = next(e for e in meta["elements"] if e.get("outputFile") == apk.name)
    data = apk.read_bytes()
    d = app_dir()
    d.mkdir(parents=True, exist_ok=True)
    tmp = d / "relay.apk.gz.tmp"
    with gzip.open(tmp, "wb", compresslevel=6) as f:
        f.write(data)
    tmp.replace(d / "relay.apk.gz")
    info = {"version_code": int(el["versionCode"]), "version_name": str(el.get("versionName", "")),
            "size": len(data), "gz_size": (d / "relay.apk.gz").stat().st_size,
            "sha256": hashlib.sha256(data).hexdigest(), "published_at": time.time()}
    (d / "relay.json").write_text(json.dumps(info, indent=1))
    return info


def info() -> dict | None:
    try:
        i = json.loads((app_dir() / "relay.json").read_text())
    except (OSError, ValueError):
        return None
    return i if (app_dir() / "relay.apk.gz").exists() else None


def stream(out=None) -> int:
    """ssh-entry `apk`: the published APK, gzip-compressed, on stdout."""
    f = app_dir() / "relay.apk.gz"
    if not f.exists():
        sys.stderr.write("notifyd: no app build published (notifyd publish-apk)\n")
        return 1
    out = out or sys.stdout.buffer
    with f.open("rb") as src:
        shutil.copyfileobj(src, out, 1 << 16)
    out.flush()
    return 0
