import json
import os
import stat

from notifyd.config import Config
from notifyd.install import (CLAUDE_EVENTS, add_authorized_key, authorized_line, install_codex_notify,
                             install_hooks, setup_accounts)

KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIabc relay-phone"
OTHER = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIother me@laptop"


def test_hooks_idempotent_and_preserve_existing(tmp_path):
    (tmp_path / "settings.json").write_text(json.dumps({"model": "x", "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "other"}]}]}}))
    assert install_hooks([str(tmp_path)], "/h/hook")
    assert not install_hooks([str(tmp_path)], "/h/hook")  # second run changes nothing
    d = json.loads((tmp_path / "settings.json").read_text())
    assert d["model"] == "x" and len(d["hooks"]["Stop"]) == 2
    assert set(d["hooks"]) == set(CLAUDE_EVENTS)
    assert all(len(g) >= 1 for g in d["hooks"].values())
    assert (tmp_path / "settings.json.notifyd-backup").exists()


def test_hooks_upgrade_old_two_event_registration(tmp_path):
    old = {"hooks": {e: [{"hooks": [{"type": "command", "command": "/h/hook"}]}] for e in ("Stop", "Notification")}}
    (tmp_path / "settings.json").write_text(json.dumps(old))
    assert install_hooks([str(tmp_path)], "/h/hook")
    d = json.loads((tmp_path / "settings.json").read_text())
    assert len(d["hooks"]["Stop"]) == 1 and len(d["hooks"]["SessionEnd"]) == 1


def test_codex_notify_top_level_before_tables(tmp_path):
    home = tmp_path / "codex"
    home.mkdir()
    cfg = home / "config.toml"
    cfg.write_text('model = "gpt-5.6-terra"\napprovals_reviewer = "user"\n\n[projects."/home/u"]\ntrust_level = "trusted"\n')
    assert install_codex_notify([str(home)], "/h/codex-hook") == ([str(cfg)], [])
    text = cfg.read_text()
    assert text.index('notify = ["/h/codex-hook"]') < text.index("[projects.")
    assert text.index("approvals_reviewer") < text.index("notify =")
    assert text.count("notify =") == 1 and 'trust_level = "trusted"' in text
    assert (home / "config.toml.notifyd-backup").exists()
    assert install_codex_notify([str(home)], "/h/codex-hook") == ([], [])  # idempotent
    assert cfg.read_text() == text


def test_codex_notify_new_file_only_tables_and_foreign_notify(tmp_path):
    a, b, c = (tmp_path / n for n in "abc")
    for d in (a, b, c):
        d.mkdir()
    (b / "config.toml").write_text('[tui]\ntheme = "x"\n')
    (c / "config.toml").write_text('notify = ["/usr/bin/other"]\n[tui]\n')
    changed, skipped = install_codex_notify([str(a), str(b), str(c)], "/h/hook")
    assert changed == [str(a / "config.toml"), str(b / "config.toml")] and skipped == [str(c / "config.toml")]
    assert (a / "config.toml").read_text().strip().endswith('notify = ["/h/hook"]')
    assert (b / "config.toml").read_text().index("notify =") < (b / "config.toml").read_text().index("[tui]")
    assert (c / "config.toml").read_text() == 'notify = ["/usr/bin/other"]\n[tui]\n'  # never clobber a user's notify


def test_authorized_key_restricted_and_idempotent(tmp_path):
    f = tmp_path / "authorized_keys"
    f.write_text("ssh-rsa EXISTING me")  # no trailing newline
    assert add_authorized_key(KEY, "/x/notifyd", str(f))
    assert not add_authorized_key(KEY, "/x/notifyd", str(f))
    lines = f.read_text().splitlines()
    assert lines[0] == "ssh-rsa EXISTING me"
    assert lines[1] == f'restrict,pty,command="/x/notifyd ssh-entry" {KEY}'
    assert stat.S_IMODE(os.stat(f).st_mode) == 0o600


def test_authorized_key_replaces_old_serve_form(tmp_path):
    f = tmp_path / "authorized_keys"
    f.write_text(f'ssh-rsa EXISTING me\nrestrict,command="/old/notifyd serve" {KEY}\n{OTHER}\n')
    assert add_authorized_key(KEY, "/new/notifyd", str(f))
    lines = f.read_text().splitlines()
    assert lines == ["ssh-rsa EXISTING me", authorized_line(KEY, "/new/notifyd"), OTHER]
    assert (tmp_path / "authorized_keys.notifyd-backup").exists()
    assert not add_authorized_key(KEY, "/new/notifyd", str(f))
    # a new exe path for the same key also replaces
    assert add_authorized_key(KEY, "/moved/notifyd", str(f))
    assert f.read_text().count(KEY) == 1


def test_setup_accounts_idempotent_in_sandbox(tmp_path):
    home = tmp_path / "home"
    (home / ".claude").mkdir(parents=True)
    (home / ".claude" / "settings.json").write_text(json.dumps({"skipDangerousModePermissionPrompt": True}))
    (home / ".claude" / ".credentials.json").write_text("SECRET")
    (home / ".codex").mkdir()
    (home / ".zshrc").write_text("export FOO=1\n")
    (home / ".tmux.conf").write_text("set -g mouse on\n")
    cfg_path = tmp_path / "config.yaml"
    cfg = Config()  # defaults reference ~/ -> point them into the sandbox
    for a in cfg.accounts:
        a.home = a.home.replace(os.path.expanduser("~"), str(home))
    cfg.save(cfg_path)
    hook, chook = tmp_path / "claude-hook", tmp_path / "codex-hook"
    for h in (hook, chook):
        h.write_text("#!/bin/sh\n")
    kw = dict(config_path=cfg_path, home=home, claude_hook=str(hook), codex_hook=str(chook), agent_run="/bin/agent-run")
    first = setup_accounts(**kw)
    assert any("registered Claude hooks" in l for l in first) and any("aliases" in l for l in first)
    for alias in ("c2", "c3"):
        d = home / f".claude-{alias}"
        assert json.loads((d / "settings.json").read_text())["skipDangerousModePermissionPrompt"] is True
        assert not (d / ".credentials.json").exists()  # credentials are never copied
        assert set(json.loads((d / "settings.json").read_text())["hooks"]) == set(CLAUDE_EVENTS)
    assert not any(p.name == ".credentials.json" for p in home.glob(".claude-*/.credentials.json"))
    assert f'notify = ["{chook}"]' in (home / ".codex" / "config.toml").read_text()
    aliases = (home / ".config" / "notifyd" / "aliases.zsh").read_text()
    for a in Config.load(cfg_path).accounts:
        assert f"alias {a.alias}='/bin/agent-run {a.alias}'" in aliases
    zshrc = (home / ".zshrc").read_text()
    assert zshrc.startswith("export FOO=1\n") and zshrc.count("aliases.zsh") == 2  # source test + source
    tmuxconf = (home / ".tmux.conf").read_text()
    assert "set -g history-limit 50000" in tmuxconf and "set -g window-size latest" in tmuxconf
    assert tmuxconf.startswith("set -g mouse on\n")
    assert (home / ".zshrc.notifyd-backup").read_text() == "export FOO=1\n"
    snapshot = {p: p.read_text() for p in home.rglob("*") if p.is_file()}
    assert setup_accounts(**kw) == []  # second run: nothing to do
    assert {p: p.read_text() for p in home.rglob("*") if p.is_file()} == snapshot


def test_setup_accounts_writes_default_config_when_missing(tmp_path):
    home = tmp_path / "h"
    cfg_path = tmp_path / "new" / "config.yaml"
    # run against a sandbox home so default account homes (~/…) of the *test* HOME are used, not the real one
    report = setup_accounts(config_path=cfg_path, home=home, claude_hook="/nonexistent/a", codex_hook="/nonexistent/b",
                            agent_run="/bin/agent-run")
    assert cfg_path.exists() and report[0].startswith("wrote default config")
    assert (home / ".config" / "notifyd" / "aliases.zsh").exists()
