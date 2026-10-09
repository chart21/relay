"""Folder trust: agents started via agent-run / the launch RPC never stop at a "trust this folder?" dialog.

1. pretrust(): before launch, write the same trust record the tool writes when you accept the dialog
   (Claude: projects[<git root or folder>].hasTrustDialogAccepted in the account's .claude.json;
   Codex: [projects."<git root or folder>"] trust_level = "trusted" in CODEX_HOME/config.toml).
2. watch(): a short-lived watcher answers start-up dialogs that still appear: any trust dialog (Claude's
   session-only home folder trust, a fresh account without .claude.json, a racing config write) and Claude's
   "make auto mode your default?" offer (declined, so bypass permissions stays the default).
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path

from . import tmux
from .config import Account

# Defaults Claude itself uses for a new projects[...] entry.
_CLAUDE_PROJECT = {"allowedTools": [], "mcpContextUris": [], "mcpServers": {}, "enabledMcpjsonServers": [],
                   "disabledMcpjsonServers": [], "hasTrustDialogAccepted": False,
                   "hasClaudeMdExternalIncludesApproved": False, "hasClaudeMdExternalIncludesWarningShown": False}


def trust_key(cwd: str) -> str:
    """The folder both tools key trust by: the git top level when inside a repo, else the folder itself."""
    path = os.path.realpath(os.path.expanduser(cwd))
    try:
        r = subprocess.run(["git", "-C", path, "rev-parse", "--show-toplevel"], capture_output=True, text=True,
                           timeout=3)
        if r.returncode == 0 and r.stdout.strip():
            return os.path.realpath(r.stdout.strip())
    except (OSError, subprocess.TimeoutExpired):
        pass
    return path


def _atomic_write(f: Path, text: str) -> None:
    mode = f.stat().st_mode & 0o777 if f.exists() else 0o600
    fd, tmp = tempfile.mkstemp(dir=f.parent, prefix=f".{f.name}.", suffix=".notifyd-tmp")
    try:
        with os.fdopen(fd, "w") as fh:
            fh.write(text)
        os.chmod(tmp, mode)
        os.replace(tmp, f)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def claude_config_file(acct: Account) -> Path:
    return Path("~/.claude.json").expanduser() if acct.default_home else Path(acct.home) / ".claude.json"


def trust_claude(config_file: Path, key: str) -> bool:
    """Mark `key` trusted in a Claude global config. Only edits an existing, parseable file (a fresh account gets
    its file on first login; the watcher covers that case)."""
    try:
        data = json.loads(config_file.read_text())
    except (OSError, ValueError):
        return False
    projects = data.setdefault("projects", {})
    entry = projects.get(key)
    if isinstance(entry, dict) and entry.get("hasTrustDialogAccepted") is True:
        return False
    projects[key] = {**_CLAUDE_PROJECT, **(entry if isinstance(entry, dict) else {}), "hasTrustDialogAccepted": True}
    _atomic_write(config_file, json.dumps(data, indent=2) + "\n")
    return True


def _toml_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def trust_codex(config_file: Path, key: str) -> bool:
    """Ensure `[projects."<key>"] trust_level = "trusted"` in a codex config.toml."""
    text = config_file.read_text() if config_file.exists() else ""
    header = f"[projects.{_toml_str(key)}]"
    lines = text.splitlines()
    if header in (l.strip() for l in lines):
        start = next(i for i, l in enumerate(lines) if l.strip() == header)
        end = next((i for i in range(start + 1, len(lines)) if lines[i].lstrip().startswith("[")), len(lines))
        for i in range(start + 1, end):
            if re.match(r"\s*trust_level\s*=", lines[i]):
                if '"trusted"' in lines[i]:
                    return False
                lines[i] = 'trust_level = "trusted"'
                break
        else:
            lines.insert(start + 1, 'trust_level = "trusted"')
        new = "\n".join(lines) + "\n"
    else:
        new = text + ("" if not text or text.endswith("\n") else "\n") + f'\n{header}\ntrust_level = "trusted"\n'
    config_file.parent.mkdir(parents=True, exist_ok=True)
    _atomic_write(config_file, new)
    return True


def pretrust(acct: Account, cwd: str) -> bool:
    """Best effort; never raises."""
    try:
        key = trust_key(cwd)
        if acct.tool == "claude":
            return trust_claude(claude_config_file(acct), key)
        if acct.tool == "codex":
            return trust_codex(Path(acct.home) / "config.toml", key)
    except Exception:
        pass
    return False


# -- watcher ----------------------------------------------------------------------------------------------
# (dialog marker, option to pick). Answers keep agents unattended: trust the folder, keep bypass permissions.
RULES = [
    (re.compile(r"trust this folder|trust the files in this folder|do you trust", re.I),
     re.compile(r"\b(yes,? i trust|trust and continue|yes,? continue|yes,? proceed)\b", re.I)),
    (re.compile(r"make auto mode your default permission mode", re.I),  # offered once bypass is the default
     re.compile(r"\bno,? keep\b", re.I)),
    (re.compile(r"bypass permissions mode", re.I),  # warning, normally off via skipDangerousModePermissionPrompt
     re.compile(r"\byes,? i accept\b", re.I)),
]
_CURSOR = ("❯", "›", ">")


def trust_action(screen: str) -> str | None:
    """tmux key to press for a known dialog on screen (Enter / Down / Up), or None when there is none."""
    lines = screen.splitlines()
    for marker, option in RULES:
        if not marker.search(screen):
            continue
        want = next((i for i in reversed(range(len(lines))) if option.search(lines[i])), None)  # live dialog is lowest
        if want is None:
            continue
        cursor = next((i for i in reversed(range(len(lines)))
                       if lines[i].lstrip().startswith(_CURSOR) and len(lines[i].strip()) > 1), None)
        if cursor is None:
            return None
        return "Enter" if cursor == want else "Down" if cursor < want else "Up"
    return None


def watch(session: str, timeout: float = 45, step: float = 0.5) -> int:
    """Accept trust dialogs in an agent tmux session for `timeout` seconds. Returns the number of dialogs accepted."""
    target, accepted, deadline = tmux.session_target(session), 0, time.time() + timeout
    while time.time() < deadline:
        if not tmux.session_exists(session):
            break
        try:
            key = trust_action(tmux.capture(target, 0, escapes=False))
        except RuntimeError:
            break
        if key:
            tmux.send_keys(target, [key])
            if key == "Enter":
                accepted += 1
                time.sleep(1.0)  # let the next screen render before looking again
                continue
        time.sleep(step)
    return accepted


def spawn_watcher(session: str) -> None:
    """Detached `notifyd trust-watch <session>` (survives the caller; inherits NOTIFYD_TMUX_SOCKET)."""
    import sys
    if os.environ.get("NOTIFYD_TRUST_WATCH", "1") == "0":
        return
    subprocess.Popen([sys.executable, "-m", "notifyd", "trust-watch", session], stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
