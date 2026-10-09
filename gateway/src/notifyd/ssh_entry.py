"""`notifyd ssh-entry`: the only program the phone's SSH key may run. Dispatches on $SSH_ORIGINAL_COMMAND.

    serve            JSON-lines bridge to the daemon (control channel)
    attach <target>  terminal: grouped tmux session onto an agent's tmux session (needs a PTY)
    apk              the published Relay app build, gzip (self-update)
"""
from __future__ import annotations

import asyncio
import os
import re
import sys

from . import tmux
from .config import Config

_NAME = re.compile(r"[A-Za-z0-9_.\-]{1,100}")
_KEY = re.compile(r"[A-Za-z0-9_.:\-]{1,200}")


def resolve_target(target: str) -> tuple[str, str | None]:
    """-> (agent tmux session, window id | None). Raises ValueError with a message safe to show the client."""
    if target.startswith("tmux:"):
        name = target[5:]
        if not _NAME.fullmatch(name):
            raise ValueError("invalid tmux session name")
        if name not in tmux.list_agent_sessions():
            raise ValueError(f"{name!r} is not an agent tmux session")
        return name, None
    if not _KEY.fullmatch(target):
        raise ValueError("invalid session key")
    from .adapters import build_adapters, find_session
    try:
        adapter, s = find_session(build_adapters(Config.load()), target, dormant=False)
    except KeyError:
        raise ValueError("no such live session") from None
    if s.extra.get("bg") and not s.tmux_session:
        try:
            adapter.ensure_pane(s)  # background session: open it with `claude attach` in a tagged tmux session
        except Exception as e:
            raise ValueError(f"cannot open the background session: {e}") from None
    if not (s.attachable and s.tmux_session):
        raise ValueError("session does not run in an agent tmux session (read-only on the phone)")
    return s.tmux_session, tmux.list_panes().get(s.pane or "", {}).get("window")


def attach(target: str) -> int:
    try:
        session, window = resolve_target(target)
        argv = tmux.grouped_attach_argv(session, window)
    except ValueError as e:
        sys.stderr.write(f"notifyd: {e}\n")
        return 1
    os.environ.setdefault("TERM", "xterm-256color")  # tmux refuses to attach without a terminal type
    try:
        os.execvp(argv[0], argv)
    except OSError as e:
        sys.stderr.write(f"notifyd: cannot exec tmux: {e}\n")
        return 1
    return 0  # unreachable


def main() -> int:
    parts = os.environ.get("SSH_ORIGINAL_COMMAND", "").split(None, 1)
    verb, rest = (parts[0] if parts else ""), (parts[1].strip() if len(parts) > 1 else "")
    if verb == "serve" and not rest:
        from .serve import serve
        return asyncio.run(serve())
    if verb == "attach" and rest and " " not in rest:
        return attach(rest)
    if verb == "apk" and not rest:
        from .appupdate import stream
        return stream()
    sys.stderr.write("notifyd: only `serve`, `attach <target>` and `apk` are allowed\n")
    return 1
