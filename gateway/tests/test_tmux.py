import time

import pytest

from conftest import needs_tmux
from notifyd import tmux
from notifyd.tmux import pane_from_hint


def test_pane_hint():
    assert pane_from_hint("claude-webapp:@26.%37") == "%37"
    assert pane_from_hint("20:@20.%29") == "%29"
    assert pane_from_hint(None) is None


def test_send_keys_validates_names():
    with pytest.raises(ValueError):
        tmux.send_keys("%1", ["-X"])
    with pytest.raises(ValueError):
        tmux.send_keys("%1", ["C-c;rm"])


@needs_tmux
def test_session_capture_keys_and_text(tmux_server, tmp_path):
    tmux.new_session("t-cat-1", str(tmp_path), ["cat"], options={"@agent_alias": "c1", "@agent_tool": "claude"})
    pane = next(p for p, v in tmux.list_panes().items() if v["session"] == "t-cat-1")
    tmux.send_text(pane, "hello\nworld")
    time.sleep(0.4)
    out = tmux.capture(pane, 50, escapes=False)
    assert "hello" in out and "world" in out
    tmux.send_keys(pane, ["C-c"])
    time.sleep(0.3)
    assert not tmux.session_exists("t-cat-1")  # ^C killed cat -> session gone
    with pytest.raises(RuntimeError):
        tmux.send_text(pane, "x")


@needs_tmux
def test_grouped_attach_creates_destroy_unattached_session(tmux_server, tmp_path):
    import os
    import pty
    import signal
    tmux.new_session("agent-1", str(tmp_path), ["sleep", "30"], options={"@agent_alias": "c1", "@agent_tool": "claude"})
    win = next(v["window"] for v in tmux.list_panes().values() if v["session"] == "agent-1")
    argv = tmux.grouped_attach_argv("agent-1", win)
    assert argv[argv.index("-s") + 1].startswith("phone-") and "destroy-unattached" in argv
    assert tmux.list_agent_sessions().keys() == {"agent-1"}
    pid, fd = pty.fork()
    if pid == 0:  # the "ssh-entry" side: a client attached on a pty (sshd sets TERM from the pty request)
        os.environ.setdefault("TERM", "xterm-256color")
        os.execvp(argv[0], argv)
    try:
        for _ in range(50):
            phone = [n for n in tmux._run("list-sessions", "-F", "#{session_name}").split() if n.startswith("phone-")]
            if phone:
                break
            time.sleep(0.1)
        assert phone and tmux.get_option(phone[0], "destroy-unattached") == "on"
        assert tmux.get_option(phone[0], "mouse") == "on"  # phone swipes arrive as wheel events
        assert set(tmux.list_agent_sessions()) == {"agent-1"}  # phone sessions never count as agent sessions
        assert tmux.list_panes()[next(iter(tmux.list_panes()))]["session"] == "agent-1"  # grouped duplicate collapsed
    finally:
        os.kill(pid, signal.SIGHUP)
        os.waitpid(pid, 0)
        os.close(fd)
    for _ in range(50):
        if not tmux.session_exists(phone[0]):
            break
        time.sleep(0.1)
    assert not tmux.session_exists(phone[0]) and tmux.session_exists("agent-1")


@needs_tmux
def test_typing_leaves_copy_mode(tmux_server, tmp_path):
    tmux.new_session("t-cat-2", str(tmp_path), ["cat"], options={"@agent_alias": "c1", "@agent_tool": "claude"})
    pane = next(p for p, v in tmux.list_panes().items() if v["session"] == "t-cat-2")
    tmux._check("copy-mode", "-t", pane)
    assert tmux._run("display-message", "-p", "-t", pane, "#{pane_in_mode}").strip() == "1"
    tmux.send_text(pane, "typed after scrolling")
    time.sleep(0.4)
    assert tmux._run("display-message", "-p", "-t", pane, "#{pane_in_mode}").strip() == "0"
    assert "typed after scrolling" in tmux.capture(pane, 50, escapes=False)
    tmux.send_keys(pane, ["C-c"])
