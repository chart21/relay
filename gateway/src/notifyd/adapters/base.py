"""Adapter base: session model, transcript paging, incremental JSONL reading, text helpers."""
from __future__ import annotations

import json
import re
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable

from .. import tmux
from ..config import Account

MAX_TEXT = 20_000


@dataclass
class Session:
    tool: str
    alias: str
    id: str
    cwd: str = ""
    title: str = ""
    state: str = "unknown"  # busy | idle | needs_input | exited
    alive: bool = False  # a process currently holds the session
    pane: str | None = None  # tmux pane id when interactive in tmux
    pid: int | None = None
    last_activity: float = 0.0  # unix seconds
    label: str = ""
    tmux_session: str | None = None  # agent tmux session (has @agent_alias), else None
    attachable: bool = False
    last_message: str = ""
    duplicates: int = 1
    remote_url: str = ""  # the session in the Claude app (Claude Code Remote Control), when it is on
    background: bool = False  # a headless run (`claude -p`: scripts, helpers, tests), not a conversation
    extra: dict = field(default_factory=dict)  # path, tmux_alias, ... (internal)

    @property
    def key(self) -> str:
        return f"{self.tool}:{self.alias}:{self.id}"

    def to_dict(self) -> dict:
        return {
            "key": self.key, "tool": self.tool, "alias": self.alias, "id": self.id, "label": self.label,
            "cwd": self.cwd, "title": self.title, "state": self.state, "alive": self.alive,
            "attachable": self.attachable, "tmux_session": self.tmux_session,
            "last_activity": self.last_activity, "last_message": self.last_message,
            "duplicates": self.duplicates, **({"remote_url": self.remote_url} if self.remote_url else {}),
            **({"background": True} if self.background else {}),
        }


class SendError(RuntimeError):
    pass


def link_pane(s: Session, panes: dict[str, dict]) -> None:
    """Attach tmux info (agent session name, attachable) to a session that runs in `s.pane`."""
    p = panes.get(s.pane) if s.pane else None
    if not p:
        s.pane = None
        return
    s.extra["tmux_alias"] = p["alias"]
    if p["alias"]:
        s.tmux_session, s.attachable = p["session"], True


def clean_text(text: str, limit: int = 300) -> str:
    """Markdown/code stripped single-line text (for last_message and notifications)."""
    text = re.sub(r"```.*?```", " ", text, flags=re.S)  # drop code blocks
    text = re.sub(r"[#*`>_]+|\[([^\]]*)\]\([^)]*\)", lambda m: m.group(1) or "", text)  # markdown marks
    return " ".join(text.split())[:limit]


def parse_ts(v) -> float:
    if isinstance(v, (int, float)):
        return v / 1000 if v > 1e11 else float(v)
    try:
        return datetime.fromisoformat(str(v)).timestamp()
    except (ValueError, TypeError):
        return 0.0


def msg(id_: str, role: str, text: str = "", tools: list | None = None, ts: float = 0.0) -> dict:
    return {"id": str(id_), "role": role, "text": text[:MAX_TEXT], "tools": tools or [], "ts": ts}


def one_line(s: str, n: int = 200) -> str:
    return " ".join(str(s).split())[:n]


def lock_holders() -> dict[int, int]:
    """inode -> pid from /proc/locks."""
    holders = {}
    try:
        for line in Path("/proc/locks").read_text().splitlines():
            parts = line.split()
            if len(parts) >= 6:
                holders[int(parts[5].split(":")[2])] = int(parts[4])
    except (OSError, ValueError, IndexError):
        pass
    return holders


class Incremental:
    """A JSONL file parsed once and then only for appended complete lines."""

    def __init__(self, path: Path, factory: Callable[[], object]):
        self.path, self.factory = path, factory
        self.builder, self.offset = factory(), 0

    def refresh(self) -> list[dict]:
        size = self.path.stat().st_size
        if size < self.offset:  # truncated/rewritten
            self.builder, self.offset = self.factory(), 0
        if size > self.offset:
            with self.path.open("rb") as f:
                f.seek(self.offset)
                data = f.read(size - self.offset)
            end = data.rfind(b"\n") + 1  # only complete lines
            for line in data[:end].splitlines():
                try:
                    self.builder.feed(json.loads(line))
                except (ValueError, TypeError, AttributeError):
                    continue
            self.offset += end
        return self.builder.result()


_CACHE: OrderedDict[str, Incremental] = OrderedDict()


def jsonl_messages(path: Path, factory: Callable[[], object]) -> list[dict]:
    key = f"{factory.__qualname__}:{path}"
    inc = _CACHE.get(key)
    if inc is None:
        inc = _CACHE[key] = Incremental(path, factory)
        while len(_CACHE) > 8:
            _CACHE.popitem(last=False)
    _CACHE.move_to_end(key)
    return inc.refresh()


class Adapter:
    tool = ""
    account: Account
    auto_trust = True  # config `auto_trust`: sessions the gateway opens skip the folder-trust prompt

    def __init__(self, account: Account):
        self.account = account
        self.home = Path(account.home)
        self._lm: dict[str, tuple[object, str]] = {}

    def list_sessions(self, panes: dict[str, dict] | None = None, dormant: bool = True) -> list[Session]:  # pragma: no cover
        raise NotImplementedError

    def _base(self, **kw) -> Session:
        return Session(tool=self.tool, alias=self.account.alias, label=self.account.label, **kw)

    # -- transcripts ------------------------------------------------------
    def messages(self, session: Session) -> list[dict] | None:
        """Normalised messages, oldest first; None when this session's store cannot be read."""
        return None

    def stamp(self, session: Session):
        """Cheap change marker for last_message caching."""
        return session.last_activity

    def transcript(self, session: Session, before: str | None = None, limit: int = 50, start_at: int | None = None) -> dict:
        """A page of messages ending before the cursor `before` (an index; None = the newest). start_at: instead of
        `limit`, everything from that index on (max 2000), so the phone can jump back to a search hit at once."""
        try:
            msgs = self.messages(session)
        except Exception:
            msgs = None
        if not msgs:
            return {"messages": self.fallback(session), "before": None}
        limit = max(1, min(int(limit or 50), 200))
        try:
            end = len(msgs) if before in (None, "") else max(0, min(len(msgs), int(before)))
        except ValueError:
            end = len(msgs)
        start = max(0, end - limit) if start_at is None else max(0, end - 2000, min(end, int(start_at)))
        return {"messages": msgs[start:end], "before": str(start) if start > 0 else None}

    def find_in(self, session: Session, query: str, limit: int = 100, roles: set[str] | None = None) -> dict:
        """Case-insensitive search in one session's whole transcript, newest first: [{id, index, role, ts, snippet}]
        (index = the message's position, usable as transcript(start_at=index))."""
        q = query.strip().casefold()
        msgs = self.messages(session) or []
        hits = []
        for i in range(len(msgs) - 1, -1, -1):
            m = msgs[i]
            if roles and m.get("role") not in roles:
                continue
            text = m.get("text") or ""
            pos = text.casefold().find(q) if q else -1
            if pos < 0:
                continue
            a = max(0, pos - 50)
            snippet = " ".join(text[a:pos + len(q) + 80].split())
            hits.append({"id": m["id"], "index": i, "role": m["role"], "ts": m.get("ts", 0),
                         "snippet": ("…" if a else "") + snippet + ("…" if pos + len(q) + 80 < len(text) else "")})
            if len(hits) >= limit:
                break
        return {"matches": hits, "total_messages": len(msgs)}

    def fallback(self, session: Session) -> list[dict]:
        """Unparseable store: the tmux screen as one system message (nothing when not in tmux)."""
        target = session.pane or (tmux.session_target(session.tmux_session) if session.tmux_session else None)
        if not target:
            return []
        try:
            text = tmux.capture(target, 200, escapes=False)
        except RuntimeError:
            return []
        return [msg("capture", "system", text, ts=time.time())] if text else []

    def tail_text(self, session: Session) -> str:
        for m in reversed(self.messages(session) or []):
            if m["role"] == "assistant" and m["text"].strip():
                return clean_text(m["text"])
        return ""

    def last_message(self, session: Session) -> str:
        stamp = self.stamp(session)
        hit = self._lm.get(session.key)
        if hit and hit[0] == stamp:
            return hit[1]
        try:
            text = self.tail_text(session)
        except Exception:
            text = ""
        self._lm[session.key] = (stamp, text)
        return text

    def iter_files(self, since: float) -> list[Path]:  # transcript files modified after `since` (for search)
        return []

    def file_id(self, path: Path) -> str:
        return path.stem

    def file_messages(self, path: Path) -> list[dict]:
        return []

    def search(self, query: str, since: float, limit: int = 20) -> list[dict]:
        """Case-insensitive text search over transcripts modified after `since`; one hit per session."""
        q, hits = query.lower(), []
        files = sorted(self.iter_files(since), key=lambda f: -f.stat().st_mtime)[:300]
        for f in files:
            try:
                if f.stat().st_size > 100_000_000 or q.encode() not in f.read_bytes().lower():
                    continue
                msgs = self.file_messages(f)
            except (OSError, ValueError):
                continue
            for m in reversed(msgs):
                low = m["text"].lower()
                if m["role"] in ("user", "assistant") and q in low:
                    i = max(0, low.index(q) - 80)
                    hits.append({"session_key": f"{self.tool}:{self.account.alias}:{self.file_id(f)}",
                                 "tool": self.tool, "alias": self.account.alias, "role": m["role"],
                                 "snippet": one_line(m["text"][i:i + 240]), "ts": m["ts"] or f.stat().st_mtime})
                    break
            if len(hits) >= limit:
                break
        return hits

    # -- control ----------------------------------------------------------
    def ensure_pane(self, session: Session) -> str | None:
        """A tmux pane to type into / attach to, creating one when the tool can (see ClaudeAdapter)."""
        return session.pane

    def send(self, session: Session, text: str, enter: bool = True) -> str:  # pragma: no cover
        """Deliver text to the session. Returns a short description of the method used."""
        raise NotImplementedError


def spawn_detached(cmd: list[str], cwd: str | None, env: dict | None = None) -> None:
    import subprocess
    subprocess.Popen(cmd, cwd=cwd or None, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True)
