"""Installation helpers: systemd unit, agent hooks, authorized_keys, `notifyd setup accounts`."""
from __future__ import annotations

import json
import os
import re
import shutil
import sys
from pathlib import Path

from .config import CONFIG_PATH, DEFAULT_HOMES, Config, bin_path

REPO = Path(__file__).resolve().parents[3]
CLAUDE_HOOK = str(REPO / "hooks" / "claude-notify-hook")
CODEX_HOOK = str(REPO / "hooks" / "codex-notify-hook")
CLAUDE_EVENTS = ("Stop", "Notification", "UserPromptSubmit", "SessionStart", "SessionEnd")

UNIT = """[Unit]
Description=notifyd (phone relay for AI coding sessions)

[Service]
ExecStart={exe} daemon
Environment=PATH={path}
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
"""


def _backup(f: Path) -> None:
    """Keep the pre-change file once; later runs never overwrite the original backup."""
    b = f.with_name(f.name + ".notifyd-backup")
    if f.exists() and not b.exists():
        shutil.copy2(f, b)


def unit_text(exe: str) -> str:
    from .config import augmented_path
    return UNIT.format(exe=exe, path=augmented_path("/usr/local/bin:/usr/bin:/bin"))


def install(write: bool, pubkey: str | None) -> None:
    exe = bin_path("notifyd")
    unit = unit_text(exe)
    path = Path("~/.config/systemd/user/notifyd.service").expanduser()
    if write:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(unit)
        print(f"wrote {path}\nnext: systemctl --user daemon-reload && systemctl --user enable --now notifyd")
    else:
        print(f"# {path}\n{unit}")
    if pubkey:
        print("# add to ~/.ssh/authorized_keys (key can run ONLY the gateway; no shell, no forwarding):")
        print(authorized_line(pubkey, exe))


def install_hooks(claude_homes: list[str], hook_path: str = CLAUDE_HOOK) -> list[str]:
    """Register the notify hook for all lifecycle events in each Claude config dir (idempotent, one backup)."""
    changed = []
    for home in claude_homes:
        f = Path(home).expanduser() / "settings.json"
        data = json.loads(f.read_text()) if f.exists() else {}
        hooks = data.setdefault("hooks", {})
        dirty = False
        for event in CLAUDE_EVENTS:
            groups = hooks.setdefault(event, [])
            if not any(h.get("command") == hook_path for g in groups for h in g.get("hooks", [])):
                groups.append({"hooks": [{"type": "command", "command": hook_path, "timeout": 5}]})
                dirty = True
        if dirty:
            _backup(f)
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(json.dumps(data, indent=2) + "\n")
            changed.append(str(f))
    return changed


_TABLE = re.compile(r"^\s*\[")
_NOTIFY = re.compile(r"^\s*notify\s*=")


def install_codex_notify(codex_homes: list[str], hook_path: str = CODEX_HOOK) -> tuple[list[str], list[str]]:
    """Add `notify = [hook]` as a top-level key of each CODEX_HOME/config.toml (it must precede the first [table]).

    Returns (changed files, skipped files that already have a different notify program)."""
    changed, skipped = [], []
    line = f"notify = {json.dumps([hook_path])}"
    for home in codex_homes:
        f = Path(home).expanduser() / "config.toml"
        lines = f.read_text().splitlines() if f.exists() else []
        first_table = next((i for i, l in enumerate(lines) if _TABLE.match(l)), len(lines))
        top = lines[:first_table]
        existing = next((i for i, l in enumerate(top) if _NOTIFY.match(l)), None)
        if existing is not None:
            if hook_path in "\n".join(top[existing:existing + 5]):
                continue
            skipped.append(str(f))
            continue
        while first_table > 0 and not lines[first_table - 1].strip():
            first_table -= 1  # insert right after the last top-level line
        block = ["# notifyd: phone notifications on turn completion", line]
        new = lines[:first_table] + block + ([""] if first_table < len(lines) else []) + lines[first_table:]
        _backup(f)
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text("\n".join(new).rstrip("\n") + "\n")
        changed.append(str(f))
    return changed, skipped


def set_claude_bypass(claude_homes: list[str]) -> list[str]:
    """permissions.defaultMode = bypassPermissions (+ no warning dialog) and remoteControlAtStartup in each account's
    settings.json, so even a plain `claude` (not only the aliases) never asks for permission and every interactive
    session is also open in the Claude app (Remote Control)."""
    changed = []
    for home in claude_homes:
        f = Path(home).expanduser() / "settings.json"
        try:
            data = json.loads(f.read_text()) if f.exists() else {}
        except ValueError:
            continue  # never clobber a settings file we cannot parse
        perms = data.setdefault("permissions", {})
        if (perms.get("defaultMode") == "bypassPermissions" and data.get("skipDangerousModePermissionPrompt") is True
                and data.get("remoteControlAtStartup") is True):
            continue
        perms["defaultMode"] = "bypassPermissions"
        data["skipDangerousModePermissionPrompt"] = True
        data["remoteControlAtStartup"] = True
        _backup(f)
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps(data, indent=2) + "\n")
        changed.append(str(f))
    return changed


CODEX_BYPASS = {"approval_policy": '"never"', "sandbox_mode": '"danger-full-access"'}


def set_codex_bypass(codex_homes: list[str]) -> list[str]:
    """Top-level approval_policy = "never" and sandbox_mode = "danger-full-access" in each CODEX_HOME/config.toml."""
    changed = []
    for home in codex_homes:
        f = Path(home).expanduser() / "config.toml"
        lines = f.read_text().splitlines() if f.exists() else []
        first_table = next((i for i, l in enumerate(lines) if _TABLE.match(l)), len(lines))
        new = list(lines)
        missing = []
        for key, value in CODEX_BYPASS.items():
            pat = re.compile(rf"^\s*{key}\s*=")
            i = next((i for i in range(first_table) if pat.match(new[i])), None)
            if i is None:
                missing.append(f"{key} = {value}")
            elif new[i].split("=", 1)[1].strip() != value:
                new[i] = f"{key} = {value}"
        if missing:
            at = first_table
            while at > 0 and not new[at - 1].strip():
                at -= 1
            new = new[:at] + ["# notifyd: always bypass approvals and the sandbox", *missing] + \
                ([""] if at < len(new) else []) + new[at:]
        if new == lines:
            continue
        _backup(f)
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text("\n".join(new).rstrip("\n") + "\n")
        changed.append(str(f))
    return changed


def authorized_line(pubkey: str, exe: str) -> str:
    return f'restrict,pty,command="{exe} ssh-entry" {pubkey.strip()}'


def add_authorized_key(pubkey: str, exe: str, path: str = "~/.ssh/authorized_keys") -> bool:
    """Install the phone key restricted to `notifyd ssh-entry`, replacing an older line of the same key
    (e.g. the previous `command="… serve"` form). Returns False when the file already has exactly this line."""
    f = Path(path).expanduser()
    f.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    key_body = pubkey.split()[1]
    new = authorized_line(pubkey, exe)
    lines = f.read_text().splitlines() if f.exists() else []
    out, replaced, found = [], False, False
    for l in lines:
        if key_body in l.split():
            if l.strip() == new and not found:
                found = True
                out.append(l)
            elif not found:
                found = replaced = True
                out.append(new)
            continue  # drop duplicates of the same key
        out.append(l)
    if found and not replaced and len(out) == len(lines):
        return False
    if not found:
        out.append(new)
    _backup(f)
    f.write_text("\n".join(out) + "\n")
    f.chmod(0o600)
    return True


# -- notifyd setup accounts ---------------------------------------------------
BLOCK_BEGIN, BLOCK_END = "# >>> notifyd >>>", "# <<< notifyd <<<"
TMUX_BLOCK = ["set -g history-limit 50000", "set -g window-size latest"]


def _marked_block(f: Path, body: list[str]) -> bool:
    text = f.read_text() if f.exists() else ""
    block = "\n".join([BLOCK_BEGIN, *body, BLOCK_END]) + "\n"
    pat = re.compile(re.escape(BLOCK_BEGIN) + r".*?" + re.escape(BLOCK_END) + r"\n?", re.S)
    new = pat.sub(lambda _: block, text) if pat.search(text) else text + ("" if text.endswith("\n") or not text else "\n") + block
    if new == text:
        return False
    _backup(f)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(new)
    return True


def aliases_text(cfg: Config, agent_run: str) -> str:
    lines = ["# generated by `notifyd setup accounts`; edit ~/.config/notifyd/config.yaml and re-run instead",
             "# claude & co. live in ~/.local/bin, which is not on PATH in every shell",
             'case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) export PATH="$PATH:$HOME/.local/bin" ;; esac']
    lines += [f"alias {a.alias}='{agent_run} {a.alias}'" for a in cfg.accounts]
    return "\n".join(lines) + "\n"


def setup_accounts(config_path: Path | None = None, home: Path | None = None, claude_hook: str = CLAUDE_HOOK,
                   codex_hook: str = CODEX_HOOK, agent_run: str | None = None) -> list[str]:
    """Idempotent account setup. All paths derive from `home` (default: $HOME) so tests can use a sandbox.
    Returns human-readable lines describing what changed."""
    home = home or Path.home()
    config_path = config_path or CONFIG_PATH
    agent_run = agent_run or bin_path("agent-run")
    report: list[str] = []
    if not config_path.exists():
        Config().save(config_path)
        report.append(f"wrote default config {config_path}")
    cfg = Config.load(config_path)
    claude = [a for a in cfg.accounts if a.tool == "claude"]
    default_home = str(Path(DEFAULT_HOMES["claude"]).expanduser())
    src = next((a for a in claude if a.home == default_home), claude[0] if claude else None)
    for a in claude:
        h = Path(a.home)
        if not h.exists():
            h.mkdir(parents=True)
            report.append(f"created {h} (log in once: run `{a.alias}`, then /login)")
        if src and a is not src and not (h / "settings.json").exists() and (Path(src.home) / "settings.json").exists():
            shutil.copy2(Path(src.home) / "settings.json", h / "settings.json")  # settings only, no credentials
            report.append(f"copied settings.json from {src.alias} to {a.alias}")
    for hook in (claude_hook, codex_hook):
        if os.path.exists(hook):
            os.chmod(hook, os.stat(hook).st_mode | 0o111)
    for f in install_hooks([a.home for a in claude], claude_hook):
        report.append(f"registered Claude hooks in {f}")
    report += [f"set bypassPermissions and Remote Control at startup in {f}" for f in set_claude_bypass([a.home for a in claude])]
    report += [f"set approval_policy=never, sandbox_mode=danger-full-access in {f}"
               for f in set_codex_bypass([a.home for a in cfg.accounts if a.tool == "codex"])]
    changed, skipped = install_codex_notify([a.home for a in cfg.accounts if a.tool == "codex"], codex_hook)
    report += [f"registered codex notify in {f}" for f in changed]
    report += [f"WARNING: {f} already has a different `notify` program; not touched" for f in skipped]
    aliases = home / ".config" / "notifyd" / "aliases.zsh"
    text = aliases_text(cfg, agent_run)
    if not aliases.exists() or aliases.read_text() != text:
        aliases.parent.mkdir(parents=True, exist_ok=True)
        aliases.write_text(text)
        report.append(f"wrote {aliases} ({', '.join(a.alias for a in cfg.accounts)})")
    if _marked_block(home / ".zshrc", [f"[ -f {aliases} ] && source {aliases}"]):
        report.append(f"added the aliases source line to {home / '.zshrc'}")
    if _marked_block(home / ".tmux.conf", TMUX_BLOCK):
        report.append(f"updated {home / '.tmux.conf'} (history-limit, window-size)")
    return report
