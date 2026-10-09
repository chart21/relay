import os
import re
import time

import pytest

from conftest import needs_tmux
from notifyd import launcher, tmux
from notifyd.config import Account, Config
from notifyd.launcher import LaunchError, build_argv, launch, launch_env, session_name


def acct(tool, fake, **kw):
    return Account(kw.pop("alias", tool[0] + "9"), tool, binary=fake, **kw)


def test_claude_argv_and_env(fake_bin):
    fake = fake_bin()
    c1 = Account("c1", "claude", home="~/.claude", binary=fake)
    c2 = Account("c2", "claude", home="~/.claude-c2", binary=fake)
    assert build_argv(c1) == [fake, "--dangerously-skip-permissions"]
    assert "CLAUDE_CONFIG_DIR" not in launch_env(c1)  # default home: no override
    assert launch_env(c2)["CLAUDE_CONFIG_DIR"] == c2.home
    assert build_argv(c2, "fix the bug", name="bench") == [
        fake, "--dangerously-skip-permissions", "--name", "bench", "fix the bug"]
    assert build_argv(c2, "-x") [-2:] == ["--", "-x"]
    assert build_argv(c2, extra=["--resume", "abc"])[-2:] == ["--resume", "abc"]


def test_codex_agy_argv_and_env(fake_bin):
    fake = fake_bin()
    x1 = Account("x1", "codex", home="~/.codex", binary=fake)
    g1 = Account("g1", "agy", binary=fake)
    assert build_argv(x1, "hi") == [fake, "--dangerously-bypass-approvals-and-sandbox", "hi"]
    assert launch_env(x1)["CODEX_HOME"] == x1.home
    assert build_argv(g1, "hi") == [fake, "--dangerously-skip-permissions", "-i", "hi"]
    assert "CODEX_HOME" not in launch_env(g1) and "CLAUDE_CONFIG_DIR" not in launch_env(g1)


def test_skip_permissions_off_and_custom_env(fake_bin):
    a = Account("w", "claude", home="~/.claude-w", binary=fake_bin(), skip_permissions=False, env={"FOO": "bar"})
    assert "--dangerously-skip-permissions" not in build_argv(a)
    assert launch_env(a)["FOO"] == "bar"
    assert ".local/bin" in launch_env(a)["PATH"]


def test_missing_binary_is_reported():
    with pytest.raises(LaunchError, match="not found"):
        build_argv(Account("c9", "claude", binary="no-such-binary-xyz"))


def test_unknown_alias_and_bad_cwd(tmp_path):
    cfg = Config()
    with pytest.raises(LaunchError, match="unknown alias"):
        launch(cfg, "zz", str(tmp_path))
    with pytest.raises(LaunchError, match="not a directory"):
        launch(cfg, "c1", str(tmp_path / "missing"))


def test_session_name_shape(tmp_path, monkeypatch):
    d = tmp_path / "my project.v2"
    d.mkdir()
    monkeypatch.setattr(tmux, "session_exists", lambda n: False)
    assert re.fullmatch(r"c2-my-project-v2-[0-9a-f]{4}", session_name("c2", str(d)))
    assert re.fullmatch(r"c2-root-[0-9a-f]{4}", session_name("c2", "/"))


@needs_tmux
def test_launch_creates_tagged_session(tmux_server, tmp_path, fake_bin):
    fake = fake_bin()
    cfg = Config(accounts=[Account("c2", "claude", "Claude #2", str(tmp_path / "home2"), binary=fake)])
    work = tmp_path / "webapp"
    work.mkdir()
    r = launch(cfg, "c2", str(work), prompt="do it", name="n1")
    assert re.fullmatch(r"c2-webapp-[0-9a-f]{4}", r.tmux_session) and r.target == f"tmux:{r.tmux_session}"
    agents = tmux.list_agent_sessions()
    assert agents[r.tmux_session]["alias"] == "c2" and agents[r.tmux_session]["tool"] == "claude"
    assert tmux.get_option(tmux.session_target(r.tmux_session), "@agent_alias") == "c2"
    for _ in range(50):  # the fake binary records its argv and env-derived args
        if os.path.exists(fake + ".args"):
            break
        time.sleep(0.1)
    assert open(fake + ".args").read().strip() == "started --dangerously-skip-permissions --name n1 do it"
    panes = tmux.list_panes()
    assert any(p["session"] == r.tmux_session and p["alias"] == "c2" for p in panes.values())
    two = launch(cfg, "c2", str(work))
    assert two.tmux_session != r.tmux_session  # same dir twice -> distinct sessions


@needs_tmux
def test_agent_run_main_errors(capsys):
    assert launcher.main(["nope"]) == 1
    assert "unknown alias" in capsys.readouterr().err
    assert launcher.main([]) == 2
    assert "usage" in capsys.readouterr().out
