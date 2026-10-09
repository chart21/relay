import os

import pytest

from conftest import needs_tmux
from notifyd import ssh_entry, tmux
from notifyd.adapters.base import Session


@pytest.fixture
def entry(monkeypatch):
    """Run ssh_entry.main with a given $SSH_ORIGINAL_COMMAND; exec is recorded instead of performed."""
    execs = []
    monkeypatch.setattr(os, "execvp", lambda f, argv: execs.append(argv))

    def run(cmd):
        if cmd is None:
            monkeypatch.delenv("SSH_ORIGINAL_COMMAND", raising=False)
        else:
            monkeypatch.setenv("SSH_ORIGINAL_COMMAND", cmd)
        return ssh_entry.main()

    run.execs = execs
    return run


@pytest.mark.parametrize("cmd", [None, "", "bash", "sh -c id", "serve extra", "attach", "attach a b", "serveX", "scp -t /x"])
def test_everything_else_is_refused(entry, capsys, cmd):
    assert entry(cmd) == 1
    assert "only `serve`, `attach <target>` and `apk`" in capsys.readouterr().err
    assert entry.execs == []


def test_serve_dispatch(entry, monkeypatch):
    called = []

    async def fake_serve():
        called.append(1)
        return 0

    monkeypatch.setattr("notifyd.serve.serve", fake_serve)
    assert entry("serve") == 0 and called == [1]


@pytest.mark.parametrize("target", ["tmux:bad name", "tmux:", "tmux:a;b", "key with space", "../etc/passwd", "a/b"])
def test_attach_rejects_malformed_targets(entry, capsys, target):
    if " " in target and not target.startswith("tmux:"):
        assert entry(f"attach {target}") == 1  # two arguments
    else:
        assert entry(f"attach {target}") == 1
    assert entry.execs == [] and capsys.readouterr().err


@needs_tmux
def test_attach_only_agent_sessions(tmux_server, entry, tmp_path, capsys):
    tmux.new_session("c2-x-1111", str(tmp_path), ["sleep", "30"], options={"@agent_alias": "c2", "@agent_tool": "claude"})
    tmux.new_session("plain", str(tmp_path), ["sleep", "30"])
    assert entry("attach tmux:plain") == 1 and entry.execs == []
    assert "not an agent tmux session" in capsys.readouterr().err
    assert entry("attach tmux:nonexistent") == 1
    assert entry("attach tmux:phone-abc123") == 1  # grouped phone sessions are not attach targets either
    assert entry("attach tmux:c2-x-1111") == 0
    argv = entry.execs[0]
    assert argv[0] == "tmux" and argv[1:3] == ["-L", os.environ["NOTIFYD_TMUX_SOCKET"]]
    assert "new-session" in argv and argv[argv.index("-t") + 1] == "=c2-x-1111"
    assert argv[argv.index("-s") + 1].startswith("phone-") and "destroy-unattached" in argv


def test_attach_by_session_key(entry, monkeypatch, capsys):
    live = Session("claude", "c2", "abc", alive=True, pane="%7", tmux_session="c2-x-1111", attachable=True)
    outside = Session("claude", "c1", "def", alive=True, pane="%8")
    monkeypatch.setattr("notifyd.adapters.find_session",
                        lambda ads, key, dormant=True: (None, {"claude:c2:abc": live, "claude:c1:def": outside}[key]))
    monkeypatch.setattr("notifyd.adapters.build_adapters", lambda cfg: [])
    monkeypatch.setattr(tmux, "list_panes", lambda: {"%7": {"window": "@3"}})
    assert entry("attach claude:c2:abc") == 0
    argv = entry.execs[0]
    assert argv[argv.index("-t") + 1] == "=c2-x-1111" and argv[-1].endswith(":@3")
    assert entry("attach claude:c1:def") == 1
    assert "read-only" in capsys.readouterr().err
    monkeypatch.setattr("notifyd.adapters.find_session", lambda *a, **k: (_ for _ in ()).throw(KeyError("x")))
    assert entry("attach claude:c9:zzz") == 1
    assert "no such live session" in capsys.readouterr().err
