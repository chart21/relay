from notifyd import memory


def _proc(root, pid, ppid, name, pss_kb, swap_kb=0):
    d = root / str(pid)
    d.mkdir(parents=True)
    (d / "stat").write_text(f"{pid} ({name}) S {ppid} 1 1 0 -1")
    (d / "smaps_rollup").write_text(f"Rss: {pss_kb + 10} kB\nPss: {pss_kb} kB\nSwapPss: {swap_kb} kB\n")


def test_session_trees_and_others(tmp_path):
    (tmp_path / "meminfo").write_text("MemTotal: 16000000 kB\nMemAvailable: 7000000 kB\nSwapTotal: 11000000 kB\nSwapFree: 4000000 kB\n")
    _proc(tmp_path, 100, 1, "zsh", 4000)              # pane shell
    _proc(tmp_path, 101, 100, "claude", 400000, 100000)  # the agent
    _proc(tmp_path, 102, 101, "node", 150000)         # its MCP server
    _proc(tmp_path, 200, 1, "firefox", 900000)
    _proc(tmp_path, 201, 200, "firefox", 300000, 50000)  # "Web Content" children group under their name
    _proc(tmp_path, 300, 1, "claude", 2000)           # someone else's: not in the session
    snap = memory.snapshot({"claude:c1:a": 100}, proc=tmp_path)
    assert snap["total_mb"] == 15625 and snap["available_mb"] == 6835 and snap["swap_used_mb"] == 6836
    assert snap["sessions"]["claude:c1:a"] == {"pids": 3, "mb": 541, "swap_mb": 97}
    assert snap["others"][0] == {"name": "firefox", "mb": 1171, "swap_mb": 48, "count": 2}


async def test_reopen_resumes_claude_in_tmux(monkeypatch, tmp_path):
    from notifyd import daemon as dmod
    from notifyd.adapters.base import Session
    from notifyd.config import Config
    from notifyd.state import State
    d = dmod.Daemon(Config(), State(tmp_path / "s.db"), tmp_path / "cfg.yaml")
    d.prestart_router = False
    s = Session("claude", "c1", "abc-123", cwd=str(tmp_path), state="exited", alive=False)
    monkeypatch.setattr(d, "_find", lambda key: (None, s))
    calls = []

    class R:
        tmux_session, target = "c1-x-0001", "tmux:c1-x-0001"
    monkeypatch.setattr(dmod, "launch", lambda cfg, alias, cwd, prompt, name, args: calls.append((alias, cwd, args)) or R())
    d.state.set("closed_sessions", '[{"key": "claude:c1:abc-123"}, {"key": "claude:c1:other"}]')
    r = await d.rpc_reopen({"key": "claude:c1:abc-123"}, None)
    assert r["tmux_session"] == "c1-x-0001" and calls == [("c1", str(tmp_path), ["--resume", "abc-123"])]
    assert [c["key"] for c in d._closed()] == ["claude:c1:other"]


async def test_memory_rpc_runs_with_sqlite_on_the_loop(monkeypatch, tmp_path):
    from notifyd import daemon as dmod, memory as mmod
    from notifyd.config import Config
    from notifyd.state import State
    d = dmod.Daemon(Config(), State(tmp_path / "s.db"), tmp_path / "cfg.yaml")
    d.prestart_router = False
    monkeypatch.setattr(dmod.tmux, "list_panes", lambda: {})
    monkeypatch.setattr(dmod, "all_sessions", lambda adapters, dormant: [])
    monkeypatch.setattr(mmod, "snapshot", lambda roots: {"total_mb": 1, "available_mb": 1, "swap_total_mb": 0,
                                                         "swap_used_mb": 0, "sessions": {}, "others": []})
    d.state.set("closed_sessions", '[{"key": "claude:c1:x"}]')
    r = await d.rpc_memory({}, None)
    assert r["closed"] == [{"key": "claude:c1:x"}] and r["sessions"] == []
