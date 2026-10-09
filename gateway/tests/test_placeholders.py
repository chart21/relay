"""Agent tmux sessions that no adapter has claimed yet (trust prompt, codex before its first message) stay visible."""
import asyncio

from conftest import needs_tmux
from notifyd import ssh_entry, tmux
from notifyd.adapters import all_sessions, build_adapters, find_session
from notifyd.adapters.base import Session
from notifyd.config import Config
from notifyd.monitor import Monitor


@needs_tmux
def test_unclaimed_agent_session_is_listed_and_attachable(tmux_server, tmp_path):
    tmux.new_session("x1-proj-0001", str(tmp_path), ["sleep", "30"], options={"@agent_alias": "x1", "@agent_tool": "codex"})
    tmux.new_session("plain", str(tmp_path), ["sleep", "30"])  # untagged: never listed
    adapters = build_adapters(Config())
    ss = [s for s in all_sessions(adapters, dormant=False) if s.extra.get("placeholder")]
    assert [(s.key, s.tool, s.state, s.attachable, s.tmux_session, s.cwd) for s in ss] == [
        ("codex:x1:tmux-x1-proj-0001", "codex", "idle", True, "x1-proj-0001", str(tmp_path))]
    assert ssh_entry.resolve_target("codex:x1:tmux-x1-proj-0001")[0] == "x1-proj-0001"
    _, s = find_session(adapters, "codex:x1:tmux-x1-proj-0001", dormant=False)
    assert s.pane  # send() can type into it


@needs_tmux
def test_claimed_tmux_session_has_no_placeholder(tmux_server, tmp_path, monkeypatch):
    tmux.new_session("c1-proj-0002", str(tmp_path), ["sleep", "30"], options={"@agent_alias": "c1", "@agent_tool": "claude"})
    adapters = build_adapters(Config())
    claude = next(a for a in adapters if a.account.alias == "c1")
    real = Session("claude", "c1", "abc", alive=True, state="idle", tmux_session="c1-proj-0002", attachable=True)
    monkeypatch.setattr(claude, "list_sessions", lambda panes=None, dormant=True: [real])
    keys = [s.key for s in all_sessions(adapters, dormant=False)]
    assert "claude:c1:abc" in keys and not any(k.startswith("claude:c1:tmux-") for k in keys)


async def test_replaced_placeholder_ends_without_alert():
    events, sessions = [], []

    class A:
        tool = "claude"
        account = Config().account("c1")
        _lm = {}

        def list_sessions(self, panes=None, dormant=True):
            return list(sessions)

        def last_message(self, s):
            return ""

    async def pub(t, p):
        events.append((t, p))

    m = Monitor([A()], pub, 15, 120, lambda: True)
    import notifyd.monitor as mon
    orig = mon.all_sessions
    mon.all_sessions = lambda adapters, dormant=True: list(sessions)
    try:
        await m.check()
        sessions[:] = [Session("claude", "c1", "tmux-c1-p", alive=True, state="idle", tmux_session="c1-p",
                               attachable=True, extra={"placeholder": True})]
        await m.check()
        sessions[:] = [Session("claude", "c1", "real", alive=True, state="idle", tmux_session="c1-p", attachable=True)]
        await m.check()
    finally:
        mon.all_sessions = orig
    kinds = [(t, p.get("session_key") or p["session"]["key"], p.get("by_user")) for t, p in events]
    assert ("session_started", "claude:c1:tmux-c1-p", None) in kinds
    assert ("session_ended", "claude:c1:tmux-c1-p", True) in kinds  # by_user -> the phone shows no "ended" alert
    assert ("session_started", "claude:c1:real", None) in kinds
