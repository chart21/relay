"""SQLite event log + key/value store. Events carry a monotonically increasing seq for client resume."""
from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path

from .config import STATE_DIR

KEEP_EVENTS = 5000


class State:
    def __init__(self, path: Path | None = None):
        path = path or STATE_DIR / "state.db"
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.db = sqlite3.connect(path)
        self.db.execute("create table if not exists events (seq integer primary key autoincrement,"
                        " ts real, type text, payload text)")
        self.db.execute("create table if not exists kv (k text primary key, v text)")
        self.db.commit()

    def add_event(self, type_: str, payload: dict) -> dict:
        ts = time.time()
        cur = self.db.execute("insert into events (ts,type,payload) values (?,?,?)",
                              (ts, type_, json.dumps(payload)))
        self.db.execute("delete from events where seq <= ?", (cur.lastrowid - KEEP_EVENTS,))
        self.db.commit()
        return {"seq": cur.lastrowid, "ts": ts, "type": type_, **payload}

    def last_seq(self) -> int:
        r = self.db.execute("select max(seq) from events").fetchone()
        return r[0] or 0

    def events_since(self, seq: int) -> list[dict]:
        rows = self.db.execute("select seq,ts,type,payload from events where seq>? order by seq", (seq,))
        return [{"seq": s, "ts": t, "type": ty, **json.loads(p)} for s, t, ty, p in rows]

    def get(self, k: str, default: str | None = None) -> str | None:
        r = self.db.execute("select v from kv where k=?", (k,)).fetchone()
        return r[0] if r else default

    def set(self, k: str, v: str) -> None:
        self.db.execute("insert into kv values (?,?) on conflict(k) do update set v=excluded.v", (k, v))
        self.db.commit()
