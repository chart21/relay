"""agy (Antigravity CLI): conversation_summaries.db + presence/*.lock + conversations/<id>.db (protobuf steps).

Every file is optional: agy 1.0.3 has no conversation store at all, so everything degrades to "no sessions"
or the tmux-capture transcript fallback.
"""
from __future__ import annotations

import json
import sqlite3
import subprocess
from datetime import datetime
from pathlib import Path
from urllib.parse import unquote, urlparse

from .. import tmux
from ..config import resolve_binary
from ..launcher import SKIP_FLAGS, account_env
from .base import Adapter, SendError, Session, clean_text, link_pane, lock_holders, msg, one_line

STEP_USER, STEP_PLANNER = 14, 15


def _varint(b: bytes, i: int) -> tuple[int, int]:
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        s += 7
        if not c & 0x80:
            return r, i


def pb_fields(b: bytes) -> list[tuple[int, int, int | bytes]]:
    """Minimal protobuf wire decoder: [(field, wiretype, value)]; [] when `b` is not a valid message."""
    out, i = [], 0
    try:
        while i < len(b):
            k, i = _varint(b, i)
            f, w = k >> 3, k & 7
            if w == 0:
                v, i = _varint(b, i)
            elif w == 2:
                n, i = _varint(b, i)
                v, i = b[i:i + n], i + n
                if len(v) != n:
                    return []
            elif w == 1:
                v, i = b[i:i + 8], i + 8
            elif w == 5:
                v, i = b[i:i + 4], i + 4
            else:
                return []
            out.append((f, w, v))
    except IndexError:
        return []
    return out


def _sub(fields, n: int) -> list:
    v = next((v for f, w, v in fields if f == n and w == 2), b"")
    return pb_fields(v) if isinstance(v, bytes) else []


def _text(fields, n: int) -> str:
    v = next((v for f, w, v in fields if f == n and w == 2), b"")
    return v.decode("utf-8", "replace") if isinstance(v, bytes) else ""


def step_message(idx: int, step_type: int, payload: bytes) -> dict | None:
    """One conversation step -> {role, text, tools, ts} or None for steps that are neither user nor assistant text."""
    f = pb_fields(payload)
    ts = next((float(v) for _, w, v in _sub(_sub(f, 5), 1) if w == 0), 0.0)
    if step_type == STEP_USER:
        text = _text(_sub(f, 19), 2).strip()
        return msg(f"s{idx}", "user", text, ts=ts) if text else None
    if step_type == STEP_PLANNER:
        body = _sub(f, 20)
        tools = []
        for fn, w, v in body:
            if fn == 7 and w == 2:
                call = pb_fields(v)
                args = _text(call, 3)
                try:
                    a = json.loads(args)
                    args = next((x for x in a.values() if isinstance(x, str)), "") if isinstance(a, dict) else args
                except ValueError:
                    pass
                tools.append({"name": _text(call, 2) or "tool", "summary": one_line(args)})
        text = (_text(body, 1) or _text(body, 8)).strip()
        return msg(f"s{idx}", "assistant", text, tools, ts) if text or tools else None
    return None


def _connect(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=2)


def _cwd(workspace_uris: str) -> str:
    try:
        uris = json.loads(workspace_uris)
        return unquote(urlparse(uris[0]).path) if uris else ""
    except (ValueError, TypeError, IndexError):
        return workspace_uris.strip('[]"').replace("file://", "").split('","')[0] if workspace_uris else ""


class AgyAdapter(Adapter):
    tool = "agy"

    def __init__(self, account):
        super().__init__(account)
        self._msgs: dict[str, tuple[tuple, list[dict]]] = {}

    def list_sessions(self, panes: dict[str, dict] | None = None, dormant: bool = True) -> list[Session]:
        db = self.home / "conversation_summaries.db"
        if not db.exists():
            return []
        try:
            con = _connect(db)
            try:
                rows = con.execute(
                    "select conversation_id,title,not_fully_idle,last_modified_time,workspace_uris,killed "
                    "from conversation_summaries where parent_conversation_id=''"
                ).fetchall()
            finally:
                con.close()
        except sqlite3.Error:
            return []
        holders = lock_holders()
        panes = panes if panes is not None else tmux.list_panes()
        out = []
        for cid, title, busy, mtime, ws, killed in rows:
            lock = self.home / "presence" / f"{cid}.lock"
            pid = None
            try:
                pid = holders.get(lock.stat().st_ino)
            except OSError:
                pass
            alive = pid is not None
            if not alive and not dormant:
                continue
            s = self._base(id=cid, cwd=_cwd(ws), title=title, alive=alive, pid=pid, last_activity=_ts(mtime),
                           state=("busy" if busy else "idle") if alive else "exited",
                           extra={"killed": bool(killed)})
            if pid:
                s.pane = tmux.pane_for_pid(pid, panes)
                link_pane(s, panes)
            out.append(s)
        return out

    # -- transcripts ------------------------------------------------------
    def _db(self, session: Session) -> Path | None:
        p = self.home / "conversations" / f"{session.id}.db"
        return p if p.exists() else None

    def stamp(self, session: Session):
        p = self._db(session)
        if p is None:
            return session.last_activity
        st = p.stat()
        wal = p.with_name(p.name + "-wal")
        return (st.st_mtime_ns, st.st_size, wal.stat().st_size if wal.exists() else 0)

    def messages(self, session: Session) -> list[dict] | None:
        p = self._db(session)
        if p is None:
            return None
        stamp = self.stamp(session)
        hit = self._msgs.get(session.id)
        if hit and hit[0] == stamp:
            return hit[1]
        con = _connect(p)
        try:
            rows = con.execute("select idx,step_type,step_payload from steps where step_type in (?,?) order by idx",
                               (STEP_USER, STEP_PLANNER)).fetchall()
        finally:
            con.close()
        out: list[dict] = []
        for idx, st, payload in rows:
            m = step_message(idx, st, payload or b"")
            if not m:
                continue
            if m["role"] == "assistant" and out and out[-1]["role"] == "assistant":  # tool-call steps of one turn
                last = out[-1]
                last["text"] = (last["text"] + "\n\n" + m["text"]).strip()
                last["tools"] += m["tools"]
            else:
                out.append(m)
        self._msgs[session.id] = (stamp, out)
        return out

    def tail_text(self, session: Session) -> str:
        p = self._db(session)
        if p is None:
            return ""
        con = _connect(p)
        try:
            rows = con.execute("select idx,step_payload from steps where step_type=? order by idx desc limit 30",
                               (STEP_PLANNER,)).fetchall()
        finally:
            con.close()
        for idx, payload in rows:
            m = step_message(idx, STEP_PLANNER, payload or b"")
            if m and m["text"]:
                return clean_text(m["text"])
        return ""

    # -- control ----------------------------------------------------------
    def send(self, session: Session, text: str, enter: bool = True) -> str:
        if session.pane:
            tmux.send_text(session.pane, text, enter)
            return f"tmux {session.pane}"
        if session.alive:
            raise SendError("this conversation is open in a Desktop terminal outside tmux: reply there, or close it there and send again from the phone (it then resumes here)")
        binary = resolve_binary(self.account.binary)
        if not binary:
            raise SendError(f"{self.account.binary} not found")
        flags = [SKIP_FLAGS["agy"]] if self.account.skip_permissions else []
        subprocess.Popen([binary, *flags, "-p", "--conversation", session.id, text], cwd=session.cwd or None,
                         env=account_env(self.account), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)
        return "headless resume"


def _ts(s: str) -> float:
    try:
        s = s.replace("Z", "+00:00")
        head, _, tail = s.partition(".")
        if tail:  # trim nanoseconds to microseconds
            frac = "".join(c for c in tail if c.isdigit())[:6]
            zone = tail[len("".join(c for c in tail if c.isdigit())):]
            s = f"{head}.{frac}{zone}"
        return datetime.fromisoformat(s).timestamp()
    except ValueError:
        return 0.0
