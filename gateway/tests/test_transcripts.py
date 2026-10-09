import json
import sqlite3
import time

from notifyd.adapters.agy import AgyAdapter, pb_fields
from notifyd.adapters.base import Session
from notifyd.adapters.claude import ClaudeAdapter
from notifyd.adapters.codex import CodexAdapter
from notifyd.config import Account


def jl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(r) for r in rows) + "\n")


# ---------------- Claude
def claude_rows():
    return [
        {"type": "user", "uuid": "u0", "isMeta": True, "message": {"content": "<local-command-caveat>x</local-command-caveat>"}},
        {"type": "user", "uuid": "u1", "timestamp": "2026-10-05T13:00:00.000Z", "message": {"content": "fix the bench"}},
        {"type": "assistant", "uuid": "a1", "message": {"id": "m1", "content": [{"type": "thinking", "thinking": ""}]}},
        {"type": "assistant", "uuid": "a2", "message": {"id": "m1", "content": [{"type": "text", "text": "Looking."}]}},
        {"type": "assistant", "uuid": "a3", "message": {"id": "m1", "content": [
            {"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": "pytest -q", "description": "d"}}]}},
        {"type": "user", "uuid": "u2", "message": {"content": [{"type": "tool_result", "tool_use_id": "t1", "content": "3 passed"}]}},
        {"type": "assistant", "uuid": "a4", "isSidechain": True, "message": {"id": "mx", "content": [{"type": "text", "text": "side"}]}},
        {"type": "assistant", "uuid": "a5", "message": {"id": "m2", "content": [{"type": "text", "text": "## Done\nAll **green**."}]}},
        {"type": "user", "uuid": "u3", "message": {"content": "<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args></command-args>"}},
        {"type": "mode"},
    ]


def test_claude_transcript(tmp_path):
    jl(tmp_path / "projects" / "-x" / "s1.jsonl", claude_rows())
    ad = ClaudeAdapter(Account("c1", "claude", home=str(tmp_path)))
    s = Session("claude", "c1", "s1")
    r = ad.transcript(s)
    roles = [m["role"] for m in r["messages"]]
    assert roles == ["user", "assistant", "tool", "assistant", "user"] and r["before"] is None
    a = r["messages"][1]
    assert a["text"] == "Looking." and a["tools"] == [{"name": "Bash", "summary": "pytest -q"}]
    assert r["messages"][2]["text"] == "3 passed" and r["messages"][2]["tools"][0]["name"] == "Bash"
    assert r["messages"][4]["text"] == "/model"
    assert r["messages"][0]["ts"] > 1.7e9
    assert ad.last_message(s) == "Done All green."
    page = ad.transcript(s, limit=2)
    assert len(page["messages"]) == 2 and page["before"] == "3"
    older = ad.transcript(s, before=page["before"], limit=10)
    assert [m["role"] for m in older["messages"]] == ["user", "assistant", "tool"] and older["before"] is None
    hits = ad.search("green", 0)
    assert hits and hits[0]["session_key"] == "claude:c1:s1"


def test_claude_incremental_append(tmp_path):
    p = tmp_path / "projects" / "-x" / "s1.jsonl"
    jl(p, claude_rows()[:2])
    ad = ClaudeAdapter(Account("c1", "claude", home=str(tmp_path)))
    s = Session("claude", "c1", "s1")
    assert len(ad.messages(s)) == 1
    with p.open("a") as f:
        f.write(json.dumps(claude_rows()[7]) + "\n" + '{"type":"assistant","message":{"id":"m3","content":[{"ty')  # partial line
    msgs = ad.messages(s)
    assert [m["role"] for m in msgs] == ["user", "assistant"]


def test_unparseable_falls_back_to_empty_without_tmux():
    ad = ClaudeAdapter(Account("c1", "claude", home="/nonexistent"))
    assert ad.transcript(Session("claude", "c1", "nope")) == {"messages": [], "before": None}


# ---------------- Codex
ROLLOUT = [
    {"timestamp": "2026-10-05T10:00:00.000Z", "type": "session_meta", "payload": {"id": "019a", "cwd": "/w/bench"}},
    {"timestamp": "2026-10-05T10:00:01.000Z", "type": "turn_context", "payload": {"cwd": "/w/bench"}},
    {"timestamp": "2026-10-05T10:00:02.000Z", "type": "response_item", "payload": {
        "type": "message", "role": "user", "content": [{"type": "input_text", "text": "<environment_context>x</environment_context>"}]}},
    {"timestamp": "2026-10-05T10:00:03.000Z", "type": "response_item", "payload": {
        "type": "message", "role": "user", "content": [{"type": "input_text", "text": "run the bench"}]}},
    {"timestamp": "2026-10-05T10:00:04.000Z", "type": "response_item", "payload": {
        "type": "function_call", "name": "shell", "call_id": "c1", "arguments": json.dumps({"command": ["bash", "-lc", "make bench"]})}},
    {"timestamp": "2026-10-05T10:00:05.000Z", "type": "response_item", "payload": {
        "type": "function_call_output", "call_id": "c1", "output": json.dumps({"output": "ok", "metadata": {}})}},
    {"timestamp": "2026-10-05T10:00:06.000Z", "type": "response_item", "payload": {
        "type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "Bench **finished**."}]}},
    {"timestamp": "2026-10-05T10:00:06.500Z", "type": "event_msg", "payload": {"type": "agent_message", "message": "Bench finished."}},
]
TID = "019a1b2c-3d4e-7f50-8a6b-7c8d9e0f1a2b"


def codex_home(tmp_path, with_db=True):
    home = tmp_path / "codex"
    rp = home / "sessions" / "2026" / "10" / "05" / f"rollout-2026-10-05T10-00-00-{TID}.jsonl"
    jl(rp, ROLLOUT)
    if with_db:
        con = sqlite3.connect(home / "state_5.sqlite")
        con.execute("create table threads (id text primary key, rollout_path text, created_at int, updated_at int, source text,"
                    " model_provider text, cwd text, title text, sandbox_policy text, approval_mode text, archived int default 0,"
                    " first_user_message text default '')")
        con.execute("insert into threads (id,rollout_path,created_at,updated_at,source,model_provider,cwd,title,sandbox_policy,approval_mode)"
                    " values (?,?,?,?,?,?,?,?,?,?)", (TID, str(rp), 1, 1759658406, "cli", "openai", "/w/bench", "Bench run", "x", "y"))
        con.execute("insert into threads (id,rollout_path,created_at,updated_at,source,model_provider,cwd,title,sandbox_policy,approval_mode,archived)"
                    " values ('arch','/nope',1,1,'cli','o','/a','Archived','x','y',1)")
        con.commit()
        con.close()
    return home


def test_codex_transcript_and_sessions_from_threads_table(tmp_path):
    home = codex_home(tmp_path)
    ad = CodexAdapter(Account("x1", "codex", home=str(home)))
    ss = ad.list_sessions()
    assert [(s.id, s.cwd, s.title, s.state, s.key) for s in ss] == [(TID, "/w/bench", "Bench run", "exited", f"codex:x1:{TID}")]
    assert ad.list_sessions(dormant=False) == []
    r = ad.transcript(ss[0])
    assert [m["role"] for m in r["messages"]] == ["user", "assistant", "tool", "assistant"]
    assert r["messages"][0]["text"] == "run the bench"
    assert r["messages"][1]["tools"] == [{"name": "shell", "summary": "make bench"}]
    assert r["messages"][2]["text"] == "ok"
    assert ad.last_message(ss[0]) == "Bench finished."


def test_codex_falls_back_to_globbing_rollouts(tmp_path):
    home = codex_home(tmp_path, with_db=False)
    ss = CodexAdapter(Account("x1", "codex", home=str(home))).list_sessions()
    assert len(ss) == 1 and ss[0].id == TID and ss[0].cwd == "/w/bench" and ss[0].title == "run the bench"


def test_codex_empty_home(tmp_path):
    assert CodexAdapter(Account("x1", "codex", home=str(tmp_path / "none"))).list_sessions() == []


def test_codex_liveness_via_lock(tmp_path):
    import fcntl
    home = codex_home(tmp_path)
    lock = home / "thread-writer-locks" / f"{TID}.lock"
    lock.parent.mkdir()
    fh = open(lock, "w")
    fcntl.flock(fh, fcntl.LOCK_EX)
    try:
        ss = CodexAdapter(Account("x1", "codex", home=str(home))).list_sessions(dormant=False)
        assert len(ss) == 1 and ss[0].alive and ss[0].state in ("busy", "idle")
    finally:
        fh.close()


# ---------------- agy
def _varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def _ld(field, data):
    return _varint(field << 3 | 2) + _varint(len(data)) + data


def _vi(field, n):
    return _varint(field << 3) + _varint(n)


def user_step(text, ts):
    return _vi(1, 14) + _ld(5, _ld(1, _vi(1, ts))) + _ld(19, _ld(2, text.encode()))


def planner_step(text, ts, tool=None):
    body = _ld(1, text.encode()) if text else b""
    if tool:
        body += _ld(7, _ld(1, b"call_1") + _ld(2, tool[0].encode()) + _ld(3, json.dumps(tool[1]).encode()))
    return _vi(1, 15) + _ld(5, _ld(1, _vi(1, ts))) + _ld(20, body)


def test_agy_transcript(tmp_path):
    home = tmp_path / "agy"
    (home / "conversations").mkdir(parents=True)
    con = sqlite3.connect(home / "conversations" / "cid.db")
    con.execute("create table steps (idx integer primary key, step_type integer, step_payload blob)")
    rows = [(0, 14, user_step("hello agy", 1791025769)), (1, 15, planner_step("", 1791025770, ("run_command", {"CommandLine": "ls -la"}))),
            (2, 132, b"\x08\x01"), (3, 15, planner_step("All done.", 1791025790))]
    con.executemany("insert into steps values (?,?,?)", rows)
    con.commit()
    con.close()
    ad = AgyAdapter(Account("g1", "agy", home=str(home)))
    s = Session("agy", "g1", "cid")
    ms = ad.transcript(s)["messages"]
    assert [m["role"] for m in ms] == ["user", "assistant"]
    assert ms[0]["text"] == "hello agy" and ms[0]["ts"] == 1791025769
    assert ms[1]["text"] == "All done." and ms[1]["tools"] == [{"name": "run_command", "summary": "ls -la"}]
    assert ad.last_message(s) == "All done."
    assert pb_fields(b"\xff\xff") == []


def test_agy_missing_conversation_uses_tmux_capture(tmp_path, monkeypatch):
    ad = AgyAdapter(Account("g1", "agy", home=str(tmp_path)))
    monkeypatch.setattr("notifyd.tmux.capture", lambda t, n, escapes=True: "screen text")
    ms = ad.transcript(Session("agy", "g1", "zz", pane="%3"))["messages"]
    assert len(ms) == 1 and ms[0]["role"] == "system" and ms[0]["text"] == "screen text"
