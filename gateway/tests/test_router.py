import json

from notifyd.router import Router, format_status
from notifyd.state import State


async def test_fast_path_status_without_model(tmp_path):
    async def status():
        return [{"state": "idle", "tool": "claude", "alias": "c1", "title": "webapp", "id": "abcdef12",
                 "cwd": "/w/webapp", "last_activity": 1}]

    r = Router(State(tmp_path / "s.db"), "haiku", "/nonexistent-binary", status, root=tmp_path)
    out = await r.ask("status")
    assert "webapp" in out and "c1" in out and r.proc is None


def test_format_status_empty():
    assert format_status([]) == "No live sessions."


def test_cmd_and_env_use_overview_account(tmp_path):
    (tmp_path / "CLAUDE.md").write_text("prompt")
    r = Router(State(tmp_path / "s.db"), "sonnet", "claude", None, root=tmp_path, home=str(tmp_path / "h2"))
    cmd = r._cmd()
    assert cmd[cmd.index("--model") + 1] == "sonnet" and "--session-id" in cmd
    assert r._env()["CLAUDE_CONFIG_DIR"] == str(tmp_path / "h2")
    r.home = None
    assert "CLAUDE_CONFIG_DIR" not in r._env()
    assert "--resume" not in r._cmd()  # separate session id per account


def test_session_per_day_and_size(tmp_path, monkeypatch):
    import notifyd.router as rmod
    (tmp_path / "CLAUDE.md").write_text("prompt")
    home = tmp_path / "h"
    r = Router(State(tmp_path / "s.db"), "sonnet", "claude", None, root=tmp_path, home=str(home))
    day = {"v": "2026-10-07"}
    monkeypatch.setattr(Router, "today", staticmethod(lambda: day["v"]))
    sid, new = r._session()
    assert new and r._session() == (sid, True)  # nothing written yet: still the same new session
    t = home / "projects" / "-router" / f"{sid}.jsonl"
    t.parent.mkdir(parents=True)
    t.write_text("x")
    assert r._session() == (sid, False)  # resumed the same day
    t.write_text("x" * rmod.SESSION_MAX_BYTES)
    big, new = r._session()
    assert big != sid and new  # too big: fresh session
    day["v"] = "2026-10-08"
    r.sid = big
    assert r._stale()  # the next ask restarts the process on the new day's session


def test_cmd_has_effort_partial_messages_and_preload(tmp_path, monkeypatch):
    import notifyd.router as rmod
    (tmp_path / "CLAUDE.md").write_text("prompt")
    (tmp_path / "ws").mkdir()
    (tmp_path / "ws" / "memory.md").write_text("Mag Quark.")
    monkeypatch.setattr(rmod, "PRELOAD", (("Memory", str(tmp_path / "ws" / "memory.md")),))
    cmd = Router(State(tmp_path / "s.db"), "sonnet", "claude", None, root=tmp_path, home=str(tmp_path / "h"))._cmd()
    assert cmd[cmd.index("--effort") + 1] == "low" and "--include-partial-messages" in cmd
    prompt = cmd[cmd.index("--append-system-prompt") + 1]
    assert prompt.startswith("prompt") and "Mag Quark." in prompt


async def test_read_result_streams_text_and_times_tools(tmp_path):
    import asyncio
    r = Router(State(tmp_path / "s.db"), "sonnet", "claude", None, root=tmp_path)

    def ev(e):
        return json.dumps({"type": "stream_event", "event": e})
    lines = [
        ev({"type": "content_block_start", "content_block": {"type": "text"}}),
        ev({"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Moment."}}),
        ev({"type": "content_block_start", "content_block": {"type": "tool_use", "name": "mcp__notifyd__list_sessions"}}),
        "not json",
        ev({"type": "content_block_start", "content_block": {"type": "text"}}),
        ev({"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Noch 80 g Protein."}}),
        json.dumps({"type": "result", "result": "Noch 80 g Protein.", "num_turns": 2, "duration_api_ms": 900}),
    ]
    reader = asyncio.StreamReader()
    reader.feed_data(("\n".join(lines) + "\n").encode())

    class P:
        stdout = reader
    r.proc = P()
    got, timing = [], {}
    out = await r._read_result(got.append, timing, __import__("time").monotonic())
    assert out == "Noch 80 g Protein." and "".join(got) == "Moment.\nNoch 80 g Protein."
    assert timing["tools"][0][0] == "list_sessions" and timing["turns"] == 2 and "first_text_ms" in timing
