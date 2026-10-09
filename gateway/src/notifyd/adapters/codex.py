"""Codex CLI: thread index $CODEX_HOME/state_*.sqlite, rollouts $CODEX_HOME/sessions/**/rollout-*-<id>.jsonl,
liveness from thread-writer-locks/<id>.lock (flock holder)."""
from __future__ import annotations

import json
import re
import sqlite3
import subprocess
import time
from pathlib import Path

from .. import tmux
from ..config import resolve_binary
from ..launcher import SKIP_FLAGS, account_env
from .base import Adapter, SendError, Session, jsonl_messages, link_pane, lock_holders, msg, one_line, parse_ts

_ID = re.compile(r"rollout-.*?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.jsonl$")
ACTIVE_WINDOW_S = 30  # rollout touched this recently (and no screen to ask) => mid-turn
_INJECTED = ("<environment_context>", "<user_instructions>", "# AGENTS.md", "<user_shell_command>",
             "<turn_aborted>", "<permissions", "<collaboration_mode>", "<skills_instructions>")


def _call_summary(p: dict) -> str:
    args = p.get("arguments", p.get("input", p.get("action", "")))
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except ValueError:
            return one_line(args)
    if isinstance(args, dict):
        cmd = args.get("command", args.get("cmd"))
        if isinstance(cmd, list):
            cmd = cmd[-1] if len(cmd) >= 3 and cmd[1] in ("-c", "-lc") else " ".join(map(str, cmd))
        if isinstance(cmd, str) and cmd:
            return one_line(cmd)
        return next((one_line(v) for v in args.values() if isinstance(v, str) and v), "")
    return one_line(args)


def _output_text(o) -> str:
    if isinstance(o, str):
        try:
            d = json.loads(o)
            if isinstance(d, dict) and "output" in d:
                return str(d["output"])
        except ValueError:
            pass
        return o
    if isinstance(o, dict):
        return str(o.get("content", o.get("output", "")))
    return ""


class CodexBuilder:
    """Folds rollout lines ({timestamp, type, payload}) into normalised messages."""

    def __init__(self):
        self.messages: list[dict] = []
        self.alt: list[dict] = []  # event_msg copies, used when a rollout has no response_items
        self.calls: dict[str, dict] = {}
        self.n = 0

    def result(self) -> list[dict]:
        return self.messages or self.alt

    def _tool(self, p: dict, ts: float) -> None:
        tool = {"name": p.get("name") or p.get("type", "tool"), "summary": _call_summary(p)}
        self.calls[p.get("call_id", "")] = tool
        last = self.messages[-1] if self.messages else None
        if last and last["role"] == "assistant":
            last["tools"].append(tool)
        else:
            self.messages.append(msg(p.get("call_id") or f"t{self.n}", "assistant", "", [tool], ts))

    def feed(self, d: dict) -> None:
        self.n += 1
        t, p, ts = d.get("type"), d.get("payload") or {}, parse_ts(d.get("timestamp"))
        if t == "event_msg":
            kind, text = p.get("type"), p.get("message")
            if kind in ("user_message", "agent_message") and isinstance(text, str) and text.strip():
                self.alt.append(msg(f"e{self.n}", "user" if kind == "user_message" else "assistant", text, ts=ts))
            return
        if t != "response_item":
            return
        pt = p.get("type")
        if pt == "message":
            role = p.get("role")
            text = "\n".join(c.get("text", "") for c in p.get("content") or []
                             if isinstance(c, dict) and c.get("type") in ("input_text", "output_text", "text")).strip()
            if role not in ("user", "assistant") or not text or text.startswith(_INJECTED):
                return
            self.messages.append(msg(p.get("id") or f"m{self.n}", role, text, ts=ts))
        elif pt in ("function_call", "custom_tool_call", "local_shell_call"):
            self._tool(p, ts)
        elif pt in ("function_call_output", "custom_tool_call_output"):
            call = self.calls.get(p.get("call_id", ""), {"name": "tool", "summary": ""})
            self.messages.append(msg(f"o{self.n}", "tool", _output_text(p.get("output"))[:4000], [call], ts))


def _meta(f: Path) -> tuple[str, str]:
    cwd = title = ""
    try:
        with f.open() as fh:
            for i, line in enumerate(fh):
                d = json.loads(line)
                p = d.get("payload", d)
                cwd = cwd or p.get("cwd", "") or ""
                if p.get("type") == "message" and p.get("role") == "user" and not title:
                    c = p.get("content")
                    if isinstance(c, list) and c and not str(c[0].get("text", "")).startswith(_INJECTED):
                        title = one_line(c[0].get("text", ""), 60)
                if (cwd and title) or i > 50:
                    break
    except (OSError, ValueError, AttributeError):
        pass
    return cwd, title


class CodexAdapter(Adapter):
    tool = "codex"

    # -- discovery --------------------------------------------------------
    def _live_ids(self) -> dict[str, int]:
        holders, live = lock_holders(), {}
        for lock in (self.home / "thread-writer-locks").glob("*.lock"):
            try:
                pid = holders.get(lock.stat().st_ino)
            except OSError:
                continue
            if pid:
                live[lock.stem] = pid
        return live

    def _threads(self, only: set[str] | None) -> list[dict]:
        """Thread rows from the sqlite index (when present), completed from rollout files."""
        rows: dict[str, dict] = {}
        dbs = sorted(self.home.glob("state_*.sqlite"), key=lambda p: int(re.sub(r"\D", "", p.stem) or 0))
        if dbs:
            try:
                con = sqlite3.connect(f"file:{dbs[-1]}?mode=ro", uri=True, timeout=2)
                try:
                    try:
                        cur = con.execute("select id,rollout_path,updated_at,cwd,title,first_user_message "
                                          "from threads where archived=0")
                    except sqlite3.OperationalError:
                        cur = con.execute("select id,rollout_path,updated_at,cwd,title,'' from threads")
                    for id_, path, upd, cwd, title, first in cur:
                        if only is None or id_ in only:
                            rows[id_] = {"id": id_, "path": path, "updated": upd or 0, "cwd": cwd or "",
                                         "title": one_line(title or first or "", 80)}
                finally:
                    con.close()
            except sqlite3.Error:
                pass
        if only is None or any(i not in rows for i in only):  # threads the index doesn't know (yet)
            for f in (self.home / "sessions").glob("**/rollout-*.jsonl"):
                m = _ID.search(f.name)
                if m and m.group(1) not in rows and (only is None or m.group(1) in only):
                    cwd, title = _meta(f)
                    rows[m.group(1)] = {"id": m.group(1), "path": str(f), "updated": 0, "cwd": cwd, "title": title}
        return list(rows.values())

    def list_sessions(self, panes: dict[str, dict] | None = None, dormant: bool = True) -> list[Session]:
        panes = panes if panes is not None else tmux.list_panes()
        live = self._live_ids()
        if not dormant and not live:
            return []
        out = []
        for r in self._threads(None if dormant else set(live)):
            path = Path(r["path"]) if r["path"] else None
            if path and not path.is_absolute():
                path = self.home / path
            try:
                mtime = path.stat().st_mtime if path else 0.0
            except OSError:
                mtime = 0.0
            upd = r["updated"] / 1000 if r["updated"] > 1e11 else float(r["updated"])
            pid = live.get(r["id"])
            s = self._base(id=r["id"], cwd=r["cwd"], title=r["title"], alive=pid is not None, pid=pid,
                           state="exited", last_activity=mtime or upd, extra={"path": str(path or "")})
            if pid:
                s.pane = tmux.pane_for_pid(pid, panes)
                link_pane(s, panes)
                s.state = "busy" if self._busy(s, mtime) else "idle"
            out.append(s)
        return out

    @staticmethod
    def _busy(s: Session, mtime: float) -> bool:
        age = time.time() - mtime
        if s.pane:  # the TUI shows "Working (… esc to interrupt)" while a turn runs
            try:
                return "to interrupt" in tmux.capture(s.pane, 40, escapes=False).lower() or age < 5
            except RuntimeError:
                pass
        return age < ACTIVE_WINDOW_S

    # -- transcripts ------------------------------------------------------
    def path_for(self, session: Session) -> Path | None:
        p = session.extra.get("path")
        if p and Path(p).exists():
            return Path(p)
        return next(iter((self.home / "sessions").glob(f"**/rollout-*{session.id}.jsonl")), None)

    def stamp(self, session: Session):
        p = self.path_for(session)
        if p is None:
            return session.last_activity
        st = p.stat()
        return (st.st_mtime_ns, st.st_size)

    def messages(self, session: Session) -> list[dict] | None:
        p = self.path_for(session)
        return jsonl_messages(p, CodexBuilder) if p else None

    def iter_files(self, since: float) -> list[Path]:
        return [f for f in (self.home / "sessions").glob("**/rollout-*.jsonl") if f.stat().st_mtime >= since]

    def file_id(self, path: Path) -> str:
        m = _ID.search(path.name)
        return m.group(1) if m else path.stem

    def file_messages(self, path: Path) -> list[dict]:
        b = CodexBuilder()
        for line in path.read_text(errors="replace").splitlines():
            try:
                b.feed(json.loads(line))
            except (ValueError, TypeError, AttributeError):
                continue
        return b.result()

    # -- control ----------------------------------------------------------
    def send(self, session: Session, text: str, enter: bool = True) -> str:
        if session.pane:
            tmux.send_text(session.pane, text, enter)
            return f"tmux {session.pane}"
        if session.alive:
            raise SendError("this session is open in a Desktop terminal outside tmux: reply there, or close it there and send again from the phone (it then resumes here)")
        binary = resolve_binary(self.account.binary)
        if not binary:
            raise SendError(f"{self.account.binary} not found")
        flags = [SKIP_FLAGS["codex"]] if self.account.skip_permissions else []
        subprocess.Popen([binary, "exec", *flags, "resume", session.id, text], cwd=session.cwd or None,
                         env=account_env(self.account), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)
        return "headless resume"
