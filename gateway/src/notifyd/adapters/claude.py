"""Claude Code: live registry (<home>/sessions/*.json) + transcripts (<home>/projects/*/<id>.jsonl)."""
from __future__ import annotations

import json
import os
import re
import subprocess
import time
from pathlib import Path

from .. import tmux
from ..config import Account, resolve_binary
from ..launcher import LaunchError, account_env, launch_account, launch_env
from .base import Adapter, SendError, Session, clean_text, jsonl_messages, link_pane, msg, one_line, parse_ts

_TAGS = re.compile(r"<(system-reminder|local-command-caveat)>.*?</\1>", re.S)
_CMD = re.compile(r"<command-name>(.*?)</command-name>.*?(?:<command-args>(.*?)</command-args>)?\s*$", re.S)
_STDOUT = re.compile(r"<local-command-stdout>(.*?)</local-command-stdout>", re.S)
_ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
_SUMMARY_KEYS = ("command", "file_path", "notebook_path", "path", "pattern", "url", "query", "description", "prompt")


def proc_start(pid: int) -> str | None:
    try:
        stat = Path(f"/proc/{pid}/stat").read_text()
    except OSError:
        return None
    return stat.rsplit(")", 1)[1].split()[19]  # field 22 (starttime)


def tool_summary(inp) -> str:
    if not isinstance(inp, dict):
        return one_line(inp or "")
    for k in _SUMMARY_KEYS:
        if isinstance(inp.get(k), str) and inp[k]:
            return one_line(inp[k])
    return next((one_line(v) for v in inp.values() if isinstance(v, str) and v), "")


def _result_text(c) -> str:
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "\n".join(b.get("text", "[image]") if isinstance(b, dict) else str(b) for b in c)
    return ""


_RC_KEY = "bridge" + "SessionId"  # field of Claude's live-session registry that holds the Remote Control id


class ClaudeBuilder:
    """Folds Claude jsonl lines into normalised messages (one API message spans several lines)."""

    def __init__(self):
        self.messages: list[dict] = []
        self.calls: dict[str, dict] = {}
        self.last_mid: str | None = None

    def result(self) -> list[dict]:
        return self.messages

    def _user_text(self, s: str) -> tuple[str, str]:
        """-> (role, text); empty text means skip."""
        if "<command-name>" in s:
            m = _CMD.search(s)
            return ("user", f"{m.group(1).strip()} {(m.group(2) or '').strip()}".strip()) if m else ("user", "")
        m = _STDOUT.search(s)
        if m:
            return "system", _ANSI.sub("", m.group(1)).strip()
        s = _TAGS.sub("", s).strip()
        if s.startswith("[Request interrupted"):
            return "system", s
        return "user", s

    def feed(self, d: dict) -> None:
        t = d.get("type")
        if t not in ("user", "assistant") or d.get("isSidechain") or d.get("isMeta"):
            return
        m = d.get("message") or {}
        c = m.get("content")
        blocks = [{"type": "text", "text": c}] if isinstance(c, str) else [b for b in c or [] if isinstance(b, dict)]
        ts, uid = parse_ts(d.get("timestamp")), d.get("uuid") or str(len(self.messages))
        if t == "assistant":
            texts, tools = [], []
            for b in blocks:
                if b.get("type") == "text" and b.get("text", "").strip():
                    texts.append(b["text"])
                elif b.get("type") == "tool_use":
                    tool = {"name": b.get("name", "tool"), "summary": tool_summary(b.get("input"))}
                    self.calls[b.get("id", "")] = tool
                    tools.append(tool)
            if not texts and not tools:
                return
            mid, last = m.get("id"), self.messages[-1] if self.messages else None
            if last and last["role"] == "assistant" and mid and mid == self.last_mid:
                if texts:
                    last["text"] = (last["text"] + "\n\n" if last["text"] else "") + "\n\n".join(texts)
                last["tools"] += tools
            else:
                self.messages.append(msg(uid, "assistant", "\n\n".join(texts), tools, ts))
            self.last_mid = mid
            return
        texts = []
        for i, b in enumerate(blocks):
            if b.get("type") == "text":
                role, text = self._user_text(b.get("text", ""))
                if text:
                    self.messages.append(msg(f"{uid}:{i}" if len(blocks) > 1 else uid, role, text, ts=ts))
            elif b.get("type") == "tool_result":
                call = self.calls.get(b.get("tool_use_id", ""), {"name": "tool", "summary": ""})
                self.messages.append(msg(f"{uid}:{i}", "tool", _result_text(b.get("content"))[:4000], [call], ts))
            elif b.get("type") == "image":
                texts.append("[image]")
        if texts:
            self.messages.append(msg(f"{uid}:img", "user", " ".join(texts), ts=ts))
        self.last_mid = None


def parse_file(path: Path) -> list[dict]:
    b = ClaudeBuilder()
    for line in path.read_text(errors="replace").splitlines():
        try:
            b.feed(json.loads(line))
        except (ValueError, TypeError, AttributeError):
            continue
    return b.result()


ATTACH_SETTLE_S = 2.0


def is_background(pid: int) -> bool:
    """True for a session process hosted by Claude's background daemon (`claude --bg`, `bg-pty-host`)."""
    try:
        ppid = int(Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[1])
        return b"bg-pty-host" in Path(f"/proc/{ppid}/cmdline").read_bytes()
    except (OSError, ValueError, IndexError):
        return False


_RC_KEY = "bridge" + "SessionId"  # field of Claude's live-session registry that holds the Remote Control id


class ClaudeAdapter(Adapter):
    tool = "claude"

    def __init__(self, account: Account):
        super().__init__(account)
        self._paths: dict[str, Path] = {}
        self._meta_cache: dict[Path, tuple[tuple, dict]] = {}

    # -- discovery --------------------------------------------------------
    def list_sessions(self, panes: dict[str, dict] | None = None, dormant: bool = True) -> list[Session]:
        panes = panes if panes is not None else tmux.list_panes()
        sessions: dict[str, Session] = {}
        for f in (self.home / "sessions").glob("*.json"):
            try:
                d = json.loads(f.read_text())
                pid = int(d["pid"])
            except (OSError, ValueError, KeyError):
                continue
            start = proc_start(pid)
            if start is None or (d.get("procStart") and start != str(d["procStart"])):
                continue
            user_named = d.get("nameSource") == "user"  # else derived, like "project-81": the transcript's title is better
            s = self._base(
                id=d["sessionId"], cwd=d.get("cwd", ""), title=d.get("name", "") if user_named else "",
                state="busy" if d.get("status") == "busy" else "idle", alive=True, pid=pid,
                pane=tmux.pane_from_hint(d.get("tmux")) or tmux.pane_for_pid(pid, panes),
                last_activity=(d.get("statusUpdatedAt") or d.get("updatedAt") or 0) / 1000,
                remote_url=f"https://claude.ai/code/{d[_RC_KEY]}" if d.get(_RC_KEY) else "",
            )
            if not s.pane and is_background(pid):  # `claude --bg`: hosted by Claude's own daemon, not a terminal
                s.extra["bg"] = True
                s.attachable = True  # opened on demand with `claude attach` (ensure_pane)
                s.pane = next((p for p, v in panes.items() if v.get("attach") == s.id), None)
            link_pane(s, panes)
            s.background = d.get("entrypoint") == "sdk-cli" and not s.pane and not s.attachable and not s.extra.get("bg")
            if not s.title:
                f = self.path_for(s)
                s.title = (self._meta(f)["title"] if f else "") or d.get("name", "")
            prev = sessions.get(s.id)
            if prev:  # same session open twice: keep the freshest; the other copy's tmux session is not a new agent
                s.duplicates = prev.duplicates + 1
                if prev.last_activity > s.last_activity:
                    prev.duplicates = s.duplicates
                    prev.extra.setdefault("dup_tmux", []).append(s.tmux_session)
                    continue
                s.extra["dup_tmux"] = [*prev.extra.get("dup_tmux", []), prev.tmux_session]
            sessions[s.id] = s
        if dormant:  # resumable headlessly
            for f in (self.home / "projects").glob("*/*.jsonl"):
                if f.stem in sessions:
                    continue
                meta = self._meta(f)
                sessions[f.stem] = self._base(id=f.stem, cwd=meta["cwd"], title=meta["title"], state="exited",
                                              last_activity=f.stat().st_mtime, background=meta["background"])
        return list(sessions.values())

    def _meta(self, f: Path) -> dict:
        st = f.stat()
        stamp = (st.st_mtime_ns, st.st_size)
        hit = self._meta_cache.get(f)
        if hit and hit[0] == stamp:
            return hit[1]
        cwd = first = ""
        title = ""
        entry = None  # how the first message came in: "cli" (a conversation) or "sdk-cli" (`claude -p`)
        try:
            with f.open("rb") as fh:
                head = fh.read(65536)
                fh.seek(max(0, st.st_size - 131072))
                tail = fh.read()
        except OSError:
            head = tail = b""
        for line in head.splitlines():
            if not cwd and b'"cwd"' in line:
                try:
                    cwd = json.loads(line).get("cwd", "")
                except ValueError:
                    pass
            if not first and b'"type":"user"' in line and b'"isMeta":true' not in line:
                try:
                    d = json.loads(line)
                    c = d.get("message", {}).get("content")
                except ValueError:
                    continue
                entry = entry or d.get("entrypoint")
                if isinstance(c, list):  # text blocks (with an image, or pasted): the first text counts
                    c = next((b.get("text") for b in c if isinstance(b, dict) and b.get("type") == "text"), "")
                if isinstance(c, str) and c.strip() and not c.startswith("<"):
                    first = one_line(c, 60)
        ai = ""
        for line in reversed(tail.splitlines()):  # a name the user gave (custom-title) beats the generated one
            if b'"custom-title"' in line or (not ai and b'"ai-title"' in line):
                try:
                    d = json.loads(line)
                except ValueError:
                    continue
                title, ai = d.get("customTitle") or title, ai or d.get("aiTitle") or ""
                if title:
                    break
        if not cwd or not os.path.isdir(cwd):  # recorded elsewhere (a copied laptop transcript): its project folder here
            cwd = slug_dir(f.parent.name) or cwd or _slug_to_cwd(f.parent.name)
        # not a conversation: a headless run, a scratch folder, or opened and closed without a single message
        background = entry == "sdk-cli" or cwd in ("/tmp", "/var/tmp") or cwd.startswith(("/tmp/", "/var/tmp/")) or not (title or ai or first)
        meta = {"cwd": cwd, "title": title or ai or first, "background": background}
        self._meta_cache[f] = (stamp, meta)
        return meta

    # -- transcripts ------------------------------------------------------
    def path_for(self, session: Session) -> Path | None:
        p = self._paths.get(session.id)
        if p is None or not p.exists():
            p = next(iter((self.home / "projects").glob(f"*/{session.id}.jsonl")), None)
            if p is None:
                return None
            self._paths[session.id] = p
        return p

    def stamp(self, session: Session):
        p = self.path_for(session)
        if p is None:
            return session.last_activity
        st = p.stat()
        return (st.st_mtime_ns, st.st_size)

    def messages(self, session: Session) -> list[dict] | None:
        p = self.path_for(session)
        return jsonl_messages(p, ClaudeBuilder) if p else None

    def tail_text(self, session: Session) -> str:
        p = self.path_for(session)
        if p is None:
            return ""
        with p.open("rb") as fh:
            fh.seek(0, 2)
            fh.seek(max(0, fh.tell() - 400_000))
            lines = fh.read().splitlines()
        for line in reversed(lines):
            if b"assistant" not in line:
                continue
            try:
                d = json.loads(line)
                content = d["message"]["content"] if d.get("type") == "assistant" and not d.get("isSidechain") else None
            except (ValueError, KeyError, TypeError):
                continue
            if not isinstance(content, list):
                continue
            text = " ".join(c.get("text", "") for c in content if isinstance(c, dict) and c.get("type") == "text")
            if text.strip():
                return clean_text(text)
        return ""

    def iter_files(self, since: float) -> list[Path]:
        return [f for f in (self.home / "projects").glob("*/*.jsonl") if f.stat().st_mtime >= since]

    def file_messages(self, path: Path) -> list[dict]:
        return parse_file(path)

    # -- control ----------------------------------------------------------
    def ensure_pane(self, session: Session) -> str | None:
        """Background sessions have no terminal of ours: open one with `claude attach <id>` in a tagged tmux session
        (once), so chat, screen and terminal work as for any agent. Nothing is closed or forked."""
        if session.pane or not session.extra.get("bg"):
            return session.pane
        binary = resolve_binary(self.account.binary)
        if not binary:
            raise SendError(f"{self.account.binary} not found")
        name = f"{self.account.alias}-bg-{session.id[:8]}"
        if not tmux.session_exists(name):
            job = session.id[:8]  # `claude attach` takes the background job id (`claude agents --json`), not the UUID
            tmux.new_session(name, session.cwd or str(Path.home()), [binary, "attach", job], launch_env(self.account),
                             {"@agent_alias": self.account.alias, "@agent_tool": "claude", "@agent_attach": session.id})
            time.sleep(ATTACH_SETTLE_S)  # let `claude attach` draw before anything is typed
        session.pane = tmux.active_pane(name)
        session.tmux_session, session.attachable = name, True
        return session.pane

    def send(self, session: Session, text: str, enter: bool = True) -> str:
        if not session.pane and session.extra.get("bg"):
            self.ensure_pane(session)
        if session.pane:
            tmux.send_text(session.pane, text, enter)
            return f"tmux {session.pane}"
        if session.alive:
            raise SendError("this session is open in a Desktop terminal outside tmux: reply there, or close it there and send again from the phone (it then resumes here)")
        cwd = session.cwd if session.cwd and os.path.isdir(session.cwd) else self._project_dir(session)
        try:  # reopened as a real session (tmux, Remote Control) rather than a headless run nobody can watch
            r = launch_account(self.account, cwd, text, args=["--resume", session.id], auto_trust=self.auto_trust)
        except LaunchError as e:
            raise SendError(f"resume failed: {e}") from None
        session.tmux_session, session.attachable = r.tmux_session, True
        return f"resumed in tmux {r.tmux_session}"

    def _project_dir(self, session: Session) -> str:
        """The folder whose project holds the transcript (`claude --resume` only finds it from there), else home."""
        p = self.path_for(session)
        return (slug_dir(p.parent.name) if p else None) or str(Path.home())


def _slug(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "-", name)


def slug_dir(slug: str) -> str | None:
    """The existing directory whose Claude project slug (every non-alphanumeric character as "-") is [slug]."""
    def walk(base: Path, rest: str) -> str | None:
        if not rest:
            return str(base)
        try:
            entries = sorted(e for e in os.listdir(base) if (base / e).is_dir())
        except OSError:
            return None
        for e in entries:
            s = _slug(e)
            if rest == s or rest.startswith(s + "-"):
                hit = walk(base / e, rest[len(s) + 1:])
                if hit:
                    return hit
        return None
    return walk(Path("/"), slug[1:]) if slug.startswith("-") else None


def _slug_to_cwd(slug: str) -> str:
    return "/" + slug.lstrip("-").replace("-", "/")  # lossy; only a hint
