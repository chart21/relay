from __future__ import annotations

import os
import time

from .. import tmux
from ..config import Account, Config
from .agy import AgyAdapter
from .base import Adapter, SendError, Session
from .claude import ClaudeAdapter
from .codex import CodexAdapter

_CLASSES = {"claude": ClaudeAdapter, "agy": AgyAdapter, "codex": CodexAdapter}


def build_adapters(cfg: Config) -> list[Adapter]:
    out = [_CLASSES[a.tool](a) for a in cfg.accounts]
    for a in out:
        a.auto_trust = cfg.auto_trust
    return out


EXCLUDE_CWDS: list[str] = []  # e.g. the overview agent's own workspace: it must not route to itself


def _visible(sessions: list[Session]) -> list[Session]:
    return [s for s in sessions if not any(s.cwd.startswith(x) for x in EXCLUDE_CWDS if x)]


def all_sessions(adapters: list[Adapter], dormant: bool = True) -> list[Session]:
    """Sessions of all accounts. dormant=False only reads what is live (cheap enough for polling)."""
    panes = tmux.list_panes()
    out: list[Session] = []
    for a in adapters:
        try:
            out.extend(_visible(a.list_sessions(panes, dormant)))
        except Exception:  # one broken tool must not hide the others
            continue
    out = _dedupe(out)
    for s in out:  # every tool: finished runs in scratch folders are tests and delegated tasks, not conversations
        if not s.alive and (s.cwd in ("/tmp", "/var/tmp") or s.cwd.startswith(("/tmp/", "/var/tmp/"))):
            s.background = True
    return out + _placeholders(adapters, panes, out)


SHELLS = {"zsh", "bash", "sh", "fish", "dash", "ksh", "tcsh"}


def placeholder_state(cmd: str, screen: str, quiet_s: float = 3600) -> tuple[str, str] | None:
    """(state, note) for an unclaimed agent tmux session from its pane, or None when only the shell is left (the
    agent exited; its conversation, if any, is listed from its transcript). quiet_s: seconds since the pane's last
    output; a pane that was just created also shows the shell for a moment while it starts the agent."""
    if cmd in SHELLS:
        return None if quiet_s > 30 else ("idle", "starting")
    s = screen.lower()
    if "select login method" in s or "/login" in s or "invalid api key" in s or "not logged in" in s:
        return "needs_input", "login needed"
    if "trust the files" in s or "trust this folder" in s or "do you trust" in s:
        return "needs_input", "trust prompt"
    return "idle", "new, no messages yet"


def _placeholders(adapters: list[Adapter], panes: dict[str, dict], sessions: list[Session]) -> list[Session]:
    """Agent tmux sessions no adapter has claimed yet: an agent at its login or folder-trust prompt, codex before its
    first message, a tool without a readable store. Listed (attachable) so the phone sees every session it started,
    labelled by what the pane shows; tmux sessions where only the shell is left are not listed."""
    claimed = {s.tmux_session for s in sessions if s.alive and s.tmux_session}
    claimed |= {t for s in sessions for t in s.extra.get("dup_tmux", []) if t}  # older copies of an open session
    by_alias = {a.account.alias: a for a in adapters}
    out: list[Session] = []
    for pane, p in panes.items():
        name, alias = p["session"], p["alias"]
        if not alias or alias not in by_alias or name in claimed or name.startswith(tmux.PHONE_PREFIX):
            continue
        claimed.add(name)
        a = by_alias[alias]
        path = p.get("path") or ""
        try:
            screen = tmux.capture(pane, 0, escapes=False) if p.get("cmd") not in SHELLS else ""
        except Exception:
            screen = ""
        st = placeholder_state(p.get("cmd") or "", screen, time.time() - (p.get("activity") or 0))
        if st is None:
            continue
        out.append(Session(tool=a.tool, alias=alias, id=f"tmux-{name}", cwd=path, title=f"{os.path.basename(path) or name} ({st[1]})",
                           state=st[0], alive=True, pane=pane, label=a.account.label, tmux_session=name, attachable=True,
                           last_activity=p.get("activity", 0.0), extra={"placeholder": True, "tmux_alias": alias}))
    return out


def _dedupe(sessions: list[Session]) -> list[Session]:
    """Accounts sharing one data dir (e.g. two agy aliases) see the same sessions: the tmux tag decides the owner."""
    by_id: dict[tuple[str, str], list[Session]] = {}
    for s in sessions:
        by_id.setdefault((s.tool, s.id), []).append(s)
    out = []
    for group in by_id.values():
        if len(group) == 1:
            out.append(group[0])
            continue
        tagged = [s for s in group if s.extra.get("tmux_alias") == s.alias]
        out.append((tagged or group)[0])
    return out


def adapter_for(adapters: list[Adapter], session: Session) -> Adapter:
    return next(a for a in adapters if a.account.alias == session.alias)


def fill_last_message(adapters: list[Adapter], sessions: list[Session]) -> list[Session]:
    for s in sessions:
        try:
            s.last_message = adapter_for(adapters, s).last_message(s)
        except Exception:
            s.last_message = ""
    return sessions


def find_session(adapters: list[Adapter], key: str, dormant: bool = True) -> tuple[Adapter, Session]:
    for s in all_sessions(adapters, dormant):
        if s.key == key or s.id == key:
            return adapter_for(adapters, s), s
    raise KeyError(key)


__all__ = ["Adapter", "Session", "SendError", "build_adapters", "all_sessions", "find_session",
           "fill_last_message", "adapter_for", "Account"]
