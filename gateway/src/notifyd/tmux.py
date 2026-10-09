"""tmux helpers: map processes to panes, create/inspect agent sessions, type into them.

Set NOTIFYD_TMUX_SOCKET to talk to a private tmux server (`tmux -L <name>`), used by the tests.
"""
from __future__ import annotations

import os
import re
import secrets
import shlex
import subprocess
from pathlib import Path

PHONE_PREFIX = "phone-"
_SEP = "\t"
_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_\-#^]{0,30}$")


def base() -> list[str]:
    sock = os.environ.get("NOTIFYD_TMUX_SOCKET")
    return ["tmux", "-L", sock] if sock else ["tmux"]


def _proc(*args: str, timeout: float = 5) -> subprocess.CompletedProcess | None:
    try:
        return subprocess.run([*base(), *args], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None


def _run(*args: str) -> str:
    r = _proc(*args)
    return r.stdout if r and r.returncode == 0 else ""


def _check(*args: str) -> str:
    r = _proc(*args)
    if r is None:
        raise RuntimeError("tmux is not available")
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or f"tmux {args[0]} failed")
    return r.stdout


def list_panes() -> dict[str, dict]:
    """pane_id -> {tty, pid, cmd, session, window, alias, tool}. Grouped (phone-*) duplicates are collapsed."""
    fmt = _SEP.join(["#{pane_id}", "#{pane_tty}", "#{pane_pid}", "#{pane_current_command}", "#{session_name}",
                     "#{window_id}", "#{@agent_alias}", "#{@agent_tool}", "#{pane_current_path}", "#{session_activity}",
                     "#{@agent_attach}"])
    panes: dict[str, dict] = {}
    for line in _run("list-panes", "-a", "-F", fmt).splitlines():
        parts = line.split(_SEP)
        if len(parts) < 8:
            continue
        pid_, tty, ppid, cmd, sess, win, alias, tool = parts[:8]
        row = {"tty": tty, "pid": int(ppid), "cmd": cmd, "session": sess, "window": win, "alias": alias, "tool": tool,
               "path": parts[8] if len(parts) > 8 else "",
               "activity": float(parts[9]) if len(parts) > 9 and parts[9].isdigit() else 0.0,
               "attach": parts[10] if len(parts) > 10 else ""}
        old = panes.get(pid_)
        if old and (bool(old["alias"]), not old["session"].startswith(PHONE_PREFIX)) >= \
                (bool(alias), not sess.startswith(PHONE_PREFIX)):
            continue  # a grouped pane shows up once per session: keep the tagged, non-phone one
        panes[pid_] = row
    return panes


def list_agent_sessions() -> dict[str, dict]:
    """tmux session name -> {alias, tool, attached, created} for sessions tagged with @agent_alias."""
    fmt = _SEP.join(["#{session_name}", "#{@agent_alias}", "#{@agent_tool}", "#{session_attached}", "#{session_created}"])
    out = {}
    for line in _run("list-sessions", "-F", fmt).splitlines():
        parts = line.split(_SEP)
        if len(parts) < 5 or not parts[1] or parts[0].startswith(PHONE_PREFIX):
            continue
        out[parts[0]] = {"alias": parts[1], "tool": parts[2], "attached": int(parts[3] or 0),
                         "created": int(parts[4] or 0)}
    return out


def session_target(name: str) -> str:
    """Exact-match target for a session's active pane."""
    return f"={name}:"


def active_pane(name: str) -> str | None:
    """Pane id (%N) of a session's active pane, None if the session is gone."""
    return _run("display-message", "-p", "-t", session_target(name), "#{pane_id}").strip() or None


def session_exists(name: str) -> bool:
    r = _proc("has-session", "-t", f"={name}")
    return bool(r and r.returncode == 0)


def pane_from_hint(hint: str | None) -> str | None:
    """Claude registry stores e.g. 'claude-webapp:@26.%37' -> '%37'."""
    if hint and "%" in hint:
        return "%" + hint.rsplit("%", 1)[1]
    return None


def _ppid(pid: int) -> int:
    try:
        return int(Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[1])
    except (OSError, ValueError, IndexError):
        return 0


def pane_for_pid(pid: int, panes: dict[str, dict] | None = None) -> str | None:
    """Pane whose tty a process uses, else the pane whose root process is one of its ancestors."""
    panes = panes if panes is not None else list_panes()
    for fd in (0, 1, 2):
        try:
            tty = os.readlink(f"/proc/{pid}/fd/{fd}")
        except OSError:
            continue
        for pane_id, p in panes.items():
            if p["tty"] == tty:
                return pane_id
    by_pid = {p["pid"]: pane_id for pane_id, p in panes.items()}
    cur = pid
    for _ in range(8):
        cur = _ppid(cur)
        if cur <= 1:
            break
        if cur in by_pid:
            return by_pid[cur]
    return None


def pane_exists(pane: str) -> bool:
    return pane in list_panes()


def new_session(name: str, cwd: str, argv: list[str], env: dict[str, str] | None = None,
                options: dict[str, str] | None = None) -> None:
    """Create a detached session running argv; options (e.g. @agent_alias) are set in the same tmux invocation."""
    cmd = shlex.join(argv)
    if env:
        cmd = shlex.join(["env", *(f"{k}={v}" for k, v in env.items())]) + " " + cmd
    args = ["new-session", "-d", "-s", name, "-c", cwd, cmd]
    for k, v in (options or {}).items():
        args += [";", "set-option", "-t", session_target(name), k, v]
    try:
        _check(*args)
    except RuntimeError:
        _proc("kill-session", "-t", f"={name}")  # never leave an untagged session behind
        raise


def get_option(target: str, name: str) -> str:
    return _run("show-options", "-v", "-t", target, name).strip()


def set_option(target: str, name: str, value: str) -> None:
    _check("set-option", "-t", target, name, value)


def switch_client(name: str) -> None:
    _check("switch-client", "-t", f"={name}")


def kill_session(name: str) -> None:
    _check("kill-session", "-t", f"={name}")


def capture(target: str, lines: int = 2000, escapes: bool = True) -> str:
    """Pane text incl. `lines` of history; lines=0: only the visible screen."""
    args = ["capture-pane", "-p", "-J", "-t", target]
    if int(lines) > 0:
        args[3:3] = ["-S", f"-{int(lines)}"]
    if escapes:
        args.insert(1, "-e")
    return _check(*args).rstrip()


def leave_copy_mode(pane: str) -> None:
    """Swiping on the phone scrolls a pane through tmux copy mode, which would swallow typed input: leave it first."""
    if _run("display-message", "-p", "-t", pane, "#{pane_in_mode}").strip() == "1":
        _run("send-keys", "-t", pane, "-X", "cancel")


def send_keys(target: str, keys: list[str]) -> None:
    """Special keys by tmux name (Escape, C-c, Enter, BTab, Up, ...)."""
    for k in keys:
        if not _KEY.match(k):
            raise ValueError(f"invalid key name {k!r}")
    leave_copy_mode(target)
    _check("send-keys", "-t", target, *keys)


def send_text(pane: str, text: str, enter: bool = True) -> None:
    """Type text literally into a pane (bracketed paste keeps newlines intact)."""
    if not pane_exists(pane):
        raise RuntimeError(f"tmux pane {pane} no longer exists")
    leave_copy_mode(pane)
    buf = f"notifyd-{os.getpid()}"
    _check("set-buffer", "-b", buf, text)
    _check("paste-buffer", "-p", "-d", "-b", buf, "-t", pane)
    if enter:
        _check("send-keys", "-t", pane, "Enter")


def grouped_attach_argv(target: str, window: str | None = None) -> list[str]:
    """argv that creates a grouped session phone-<6hex> on `target` and attaches to it in one go.

    destroy-unattached must be set while the client is attached (tmux destroys a detached session that has it),
    so everything is one chained tmux invocation: nothing is left behind if the exec never happens."""
    name = PHONE_PREFIX + secrets.token_hex(3)
    argv = [*base(), "new-session", "-s", name, "-t", f"={target}", ";", "set-option", "-t", name, "destroy-unattached", "on",
            ";", "set-option", "-t", name, "mouse", "on"]  # the app turns swipes into wheel events: scroll history
    if window:
        argv += [";", "select-window", "-t", f"{name}:{window}"]
    return argv
