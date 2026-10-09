"""Phone bridge (PROTOCOL.md "Phone bridge"): what the phone forwards lands as JSONL in an inbox folder;
send and location requests from agents wait for the phone, which approves (and, for messages, sends) them.

Consumers of the inbox are your own scripts and skills (NOTIFYD_INBOX). Approval is only for sending.
"""
from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from datetime import datetime
from pathlib import Path

INBOX = Path(os.environ.get("NOTIFYD_INBOX", "~/.local/share/notifyd/inbox")).expanduser()
KINDS = ("notification", "sms", "health", "location")
MAX_ITEMS = 500
SEEN_DAYS = 30
APPS = {"whatsapp": "com.whatsapp", "threema": "ch.threema.app", "signal": "org.thoughtcrime.securesms",
        "telegram": "org.telegram.messenger"}
SEND_KINDS = ("message", "mail", "invite", "post", "order", "payment")
SEND_TTL_S = 24 * 3600
LOCATION_TTL_S = 600


class BridgeError(ValueError):
    pass


class Bridge:
    def __init__(self, state, inbox: Path | None = None):
        self.state, self.inbox = state, inbox or INBOX
        db = state.db
        db.execute("create table if not exists inbox_seen (id text primary key, ts real)")
        db.execute("create table if not exists requests (id text primary key, kind text, payload text, status text,"
                   " result text, created real, expires real)")
        db.commit()
        self.waiters: dict[str, list[asyncio.Future]] = {}

    # -- phone -> desktop -------------------------------------------------
    def store(self, kind: str, items: list) -> dict:
        if kind not in KINDS:
            raise BridgeError(f"unknown kind {kind!r} (one of {', '.join(KINDS)})")
        if not isinstance(items, list) or len(items) > MAX_ITEMS:
            raise BridgeError(f"items must be a list of at most {MAX_ITEMS}")
        stored = dups = 0
        now = time.time()
        files: dict[Path, list[str]] = {}
        for it in items:
            if not isinstance(it, dict) or not isinstance(it.get("id"), str) or not it["id"]:
                raise BridgeError("every item needs a string id")
            ts = it.get("ts", it.get("start"))
            if not isinstance(ts, (int, float)):
                raise BridgeError(f"item {it['id']}: ts (or start) must be unix seconds")
            if self.state.db.execute("select 1 from inbox_seen where id=?", (f"{kind}:{it['id']}",)).fetchone():
                dups += 1
                continue
            self.state.db.execute("insert into inbox_seen values (?,?)", (f"{kind}:{it['id']}", now))
            day = datetime.fromtimestamp(float(ts)).strftime("%Y-%m-%d")
            files.setdefault(self.inbox / kind / f"{day}.jsonl", []).append(
                json.dumps({**it, "received_at": now}, ensure_ascii=False))
            stored += 1
        for path, lines in files.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a") as f:
                f.write("\n".join(lines) + "\n")
        self.state.db.execute("delete from inbox_seen where ts < ?", (now - SEEN_DAYS * 86400,))
        self.state.db.commit()
        return {"stored": stored, "duplicates": dups}

    def recent(self, kind: str = "notification", hours: float = 24, app: str = "", query: str = "", limit: int = 50) -> list[dict]:
        """Newest inbox items (for agents and the voice assistant)."""
        if kind not in KINDS:
            raise BridgeError(f"unknown kind {kind!r}")
        since, out = time.time() - hours * 3600, []
        app = APPS.get(app.lower(), app)
        for f in sorted((self.inbox / kind).glob("*.jsonl"), reverse=True)[:max(2, int(hours // 24) + 2)]:
            for line in reversed(f.read_text().splitlines()):
                try:
                    it = json.loads(line)
                except ValueError:
                    continue
                if float(it.get("ts", it.get("start", 0))) < since:
                    continue
                if app and it.get("app") != app:
                    continue
                if query and query.lower() not in json.dumps(it, ensure_ascii=False).lower():
                    continue
                out.append(it)
        out.sort(key=lambda it: -float(it.get("ts", it.get("start", 0))))
        return out[:limit]

    # -- requests (desktop -> phone) ----------------------------------------
    def create(self, p: dict) -> tuple[str, dict]:
        """-> (event type, event payload) to publish; the request is stored as pending."""
        kind = str(p.get("kind") or "")
        rid = uuid.uuid4().hex[:10]
        now = time.time()
        if kind == "location":
            ev = {"request_id": rid, "reason": str(p.get("reason") or "")[:200], "expires_at": now + LOCATION_TTL_S}
            etype = "location_request"
        elif kind in SEND_KINDS:
            text = str(p.get("text") or "").strip()
            if kind == "message":
                app, to = APPS.get(str(p.get("app") or "").lower(), str(p.get("app") or "")), str(p.get("to") or "").strip()
                if not (app and to and text):
                    raise BridgeError("send-message needs app, to (the chat as shown in its notifications) and text")
                summary = f"{to}: {text}"
                ev = {"request_id": rid, "kind": kind, "executor": "phone", "app": app, "conversation": to, "text": text}
            else:
                summary = str(p.get("summary") or "").strip()
                if not summary:
                    raise BridgeError(f"{kind} approval needs a summary (what will be sent, to whom)")
                ev = {"request_id": rid, "kind": kind, "executor": "desktop", "text": text}
            ev.update({"summary": summary[:500], "requested_by": str(p.get("requested_by") or "an agent")[:80],
                       "expires_at": now + SEND_TTL_S})
            etype = "send_request"
        else:
            raise BridgeError(f"unknown request kind {kind!r} (location, {', '.join(SEND_KINDS)})")
        self.state.db.execute("insert into requests values (?,?,?,?,?,?,?)",
                              (rid, kind, json.dumps(ev), "pending", None, now, ev["expires_at"]))
        self.state.db.commit()
        return etype, ev

    def status(self, rid: str) -> dict:
        r = self.state.db.execute("select kind, payload, status, result, expires from requests where id=?", (rid,)).fetchone()
        if not r:
            raise BridgeError(f"no such request {rid}")
        kind, payload, status, result, expires = r
        if status == "pending" and time.time() > expires:
            status = "expired"
        out = {"request_id": rid, "kind": kind, "status": status, **({"summary": json.loads(payload).get("summary")}
                                                                      if kind != "location" else {})}
        if result:
            out.update(json.loads(result))
        return out

    def pending_payload(self, rid: str) -> dict | None:
        r = self.state.db.execute("select payload, status, expires from requests where id=?", (rid,)).fetchone()
        return json.loads(r[0]) if r and r[1] == "pending" and time.time() <= r[2] else None

    def resolve(self, rid: str, ok: bool, error: str | None = None, data: dict | None = None) -> dict:
        r = self.state.db.execute("select kind, status from requests where id=?", (rid,)).fetchone()
        if not r:
            raise BridgeError(f"no such request {rid}")
        kind, status = r
        if status != "pending":
            return self.status(rid)  # already resolved (e.g. a second phone): keep the first answer
        if kind == "location":
            new = "done" if ok else "failed"
        elif ok:
            new = "sent" if kind == "message" else "approved"
        else:
            new = "denied" if error == "denied" else "failed"
        result = {k: v for k, v in {"ok": bool(ok), "error": error, **(data or {})}.items() if v is not None}
        self.state.db.execute("update requests set status=?, result=? where id=?", (new, json.dumps(result), rid))
        self.state.db.commit()
        st = self.status(rid)
        for fut in self.waiters.pop(rid, []):
            if not fut.done():
                fut.set_result(st)
        return st

    async def wait(self, rid: str, wait_s: float) -> dict:
        st = self.status(rid)
        if st["status"] != "pending" or wait_s <= 0:
            return st
        fut = asyncio.get_running_loop().create_future()
        self.waiters.setdefault(rid, []).append(fut)
        try:
            return await asyncio.wait_for(fut, wait_s)
        except asyncio.TimeoutError:
            return self.status(rid)  # pending: the phone is offline or the user has not decided yet
