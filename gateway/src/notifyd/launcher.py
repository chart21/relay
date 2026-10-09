"""`agent-run <alias> [args...]`: start an agent in its own tagged tmux session. Shared by shell aliases and the launch RPC."""
from __future__ import annotations

import os
import re
import secrets
import sys
from dataclasses import dataclass, field

from . import tmux, trust
from .config import Account, Config, augmented_path, resolve_binary

SKIP_FLAGS = {
    "claude": "--dangerously-skip-permissions",
    "codex": "--dangerously-bypass-approvals-and-sandbox",
    "agy": "--dangerously-skip-permissions",
}


class LaunchError(RuntimeError):
    pass


@dataclass
class LaunchResult:
    tmux_session: str
    argv: list[str] = field(default_factory=list)
    env: dict = field(default_factory=dict)

    @property
    def target(self) -> str:
        return f"tmux:{self.tmux_session}"


def launch_env(acct: Account) -> dict[str, str]:
    """Environment overrides for running the tool under this account."""
    env = {"PATH": augmented_path()}  # tmux servers started elsewhere (systemd) may lack ~/.local/bin
    if acct.tool == "claude" and not acct.default_home:
        env["CLAUDE_CONFIG_DIR"] = acct.home
    elif acct.tool == "codex":
        env["CODEX_HOME"] = acct.home
    env.update(acct.env)
    return env


def account_env(acct: Account) -> dict[str, str]:
    """Full process environment (for headless runs)."""
    return {**os.environ, **launch_env(acct)}


def build_argv(acct: Account, prompt: str | None = None, name: str | None = None,
               extra: list[str] | tuple[str, ...] = ()) -> list[str]:
    binary = resolve_binary(acct.binary)
    if not binary:
        raise LaunchError(f"{acct.binary!r} not found (set `binary:` for account {acct.alias} in the config)")
    argv = [binary]
    if acct.skip_permissions:
        argv.append(SKIP_FLAGS[acct.tool])
    if name and acct.tool == "claude":
        argv += ["--name", name]
    argv += list(extra)
    if prompt:
        if acct.tool == "agy":
            argv += ["-i", prompt]  # --prompt-interactive: run the prompt, stay in the TUI
        else:
            argv += (["--", prompt] if prompt.startswith("-") else [prompt])
    return argv


def _slug(s: str, default: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]+", "-", s).strip("-")[:30] or default


def session_name(alias: str, cwd: str) -> str:
    base = _slug(os.path.basename(cwd.rstrip("/")), "root")
    for _ in range(50):
        name = f"{_slug(alias, 'agent')}-{base}-{secrets.token_hex(2)}"
        if not tmux.session_exists(name):
            return name
    raise LaunchError("could not find a free tmux session name")


def launch_account(acct: Account, cwd: str, prompt: str | None = None, name: str | None = None,
                   args: list[str] | tuple[str, ...] = (), auto_trust: bool = True) -> LaunchResult:
    """The agent in a new tagged tmux session (bypass permissions per account; Claude's Remote Control comes from
    `remoteControlAtStartup` in its settings, not a flag: `--remote-control [name]` would swallow the prompt)."""
    cwd = os.path.abspath(os.path.expanduser(cwd))
    if not os.path.isdir(cwd):
        raise LaunchError(f"not a directory: {cwd}")
    argv = build_argv(acct, prompt, name, args)
    env = launch_env(acct)
    session = session_name(acct.alias, cwd)
    if auto_trust:
        trust.pretrust(acct, cwd)  # no "trust this folder?" stop for agents we start
    try:
        tmux.new_session(session, cwd, argv, env, {"@agent_alias": acct.alias, "@agent_tool": acct.tool})
    except RuntimeError as e:
        raise LaunchError(f"tmux: {e}") from e
    if auto_trust:
        trust.spawn_watcher(session)  # accepts any trust dialog that still shows up
    return LaunchResult(session, argv, env)


def launch(cfg: Config, alias: str, cwd: str, prompt: str | None = None, name: str | None = None,
           args: list[str] | tuple[str, ...] = (), attach: bool = False) -> LaunchResult:
    """Create the tmux session. attach=True: switch the current tmux client to it, or attach when outside tmux."""
    acct = cfg.account(alias)
    if acct is None:
        raise LaunchError(f"unknown alias {alias!r}; configured: {', '.join(a.alias for a in cfg.accounts)}")
    res = launch_account(acct, cwd, prompt, name, args, cfg.auto_trust)
    session = res.tmux_session
    if attach:
        if os.environ.get("TMUX"):
            try:
                tmux.switch_client(session)
            except RuntimeError:
                pass  # created anyway; the user can switch manually
        else:
            os.execvp(tmux.base()[0], [*tmux.base(), "attach-session", "-t", f"={session}"])
    return res


USAGE = "usage: agent-run <alias> [tool args...]   (starts the agent in a new tmux session in the current directory)"


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    cfg = Config.load()
    if not argv or argv[0] in ("-h", "--help"):
        print(USAGE)
        print("aliases: " + ", ".join(f"{a.alias} ({a.tool})" for a in cfg.accounts))
        return 0 if argv else 2
    try:
        launch(cfg, argv[0], os.getcwd(), args=argv[1:], attach=True)
    except LaunchError as e:
        print(f"agent-run: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
