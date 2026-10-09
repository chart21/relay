"""What the phone's session list shows: no headless runs, real titles, honest placeholder states (2026-10-08)."""
import json
import os

from notifyd.adapters import placeholder_state
from notifyd.adapters.claude import ClaudeAdapter
from notifyd.config import Account


def adapter(home):
    return ClaudeAdapter(Account("c1", "claude", "Claude", str(home)))


def _transcript(home, sid, lines, folder="-home-u-proj"):
    d = home / "projects" / folder
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{sid}.jsonl").write_text("".join(json.dumps(x, separators=(",", ":")) + "\n" for x in lines))


def _live(home, sid, **kw):
    (home / "sessions").mkdir(exist_ok=True)
    (home / "sessions" / f"{os.getpid()}.json").write_text(json.dumps(
        {"pid": os.getpid(), "sessionId": sid, "cwd": "/home/u/proj", "status": "idle", "statusUpdatedAt": 1000, **kw}))


def test_headless_runs_are_background_conversations_are_not(tmp_path):
    _transcript(tmp_path, "pool-run", [{"type": "user", "entrypoint": "sdk-cli", "cwd": "/home/u/proj",
                                         "message": {"content": "Reply with exactly: ok"}}])
    # a real conversation that Jarvis later resumed headless: the first message decides
    _transcript(tmp_path, "tracker", [{"type": "user", "entrypoint": "cli", "cwd": "/home/u/proj", "message": {"content": "Init"}},
                                      {"type": "user", "entrypoint": "sdk-cli", "message": {"content": "250 g Mehl"}}])
    _transcript(tmp_path, "scratch", [{"type": "user", "entrypoint": "cli", "cwd": "/tmp/x/scratchpad", "message": {"content": "hi"}}])
    out = {s.id: s for s in adapter(tmp_path).list_sessions(panes={})}
    assert out["pool-run"].background and out["scratch"].background and not out["tracker"].background
    assert "background" in out["pool-run"].to_dict() and "background" not in out["tracker"].to_dict()


def test_live_title_prefers_users_name_then_transcript_title(tmp_path):
    _transcript(tmp_path, "s1", [{"type": "user", "entrypoint": "cli", "cwd": "/home/u/proj", "message": {"content": "Fix the build"}},
                                 {"type": "ai-title", "aiTitle": "Fix CI build on main"}])
    _live(tmp_path, "s1", name="proj-81", nameSource="derived", entrypoint="cli")
    assert adapter(tmp_path).list_sessions(panes={}, dormant=False)[0].title == "Fix CI build on main"
    _live(tmp_path, "s1", name="work/api-merge", nameSource="user", entrypoint="cli")
    assert adapter(tmp_path).list_sessions(panes={}, dormant=False)[0].title == "work/api-merge"


def test_live_headless_run_without_terminal_is_background(tmp_path):
    _live(tmp_path, "r1", name="router-62", nameSource="derived", entrypoint="sdk-cli")
    assert adapter(tmp_path).list_sessions(panes={}, dormant=False)[0].background


def test_placeholder_state_from_the_pane():
    assert placeholder_state("zsh", "Resume this session with:\nclaude --resume x", quiet_s=3600) is None  # the agent exited
    assert placeholder_state("zsh", "", quiet_s=2) == ("idle", "starting")  # just created: the shell starts the agent
    assert placeholder_state("claude", " Select login method:\n 1. Claude account") == ("needs_input", "login needed")
    assert placeholder_state("claude", "Do you trust the files in this folder?") == ("needs_input", "trust prompt")
    assert placeholder_state("codex", "") == ("idle", "new, no messages yet")


def test_empty_sessions_are_background_but_image_prompts_count(tmp_path):
    _transcript(tmp_path, "empty", [{"type": "user", "entrypoint": "cli", "cwd": "/home/u/proj",
                                     "message": {"content": "<local-command-caveat>Caveat</local-command-caveat>"}}])
    _transcript(tmp_path, "image", [{"type": "user", "entrypoint": "cli", "cwd": "/home/u/proj", "message": {"content": [
        {"type": "image", "source": {}}, {"type": "text", "text": "What is on this screenshot?"}]}}])
    out = {s.id: s for s in adapter(tmp_path).list_sessions(panes={})}
    assert out["empty"].background and not out["image"].background
    assert out["image"].title == "What is on this screenshot?"


def test_search_in_one_transcript_and_jump_back(tmp_path):
    from notifyd.adapters.base import Adapter, Session

    class A(Adapter):
        tool = "fake"

        def messages(self, session):
            return [{"id": f"m{i}", "role": "assistant" if i % 2 else "user", "text": t, "ts": i}
                    for i, t in enumerate(["Hallo", "Der Tarif Contoso Komfort kostet 34,50 €", "danke", "Und Fabrikam Plus?",
                                           "Fabrikam plus ist günstiger als Contoso"])]
    a = A(Account("f1", "claude", "F", str(tmp_path)))
    s = Session("fake", "f1", "x")
    r = a.find_in(s, "contoso")
    assert [h["index"] for h in r["matches"]] == [4, 1] and r["total_messages"] == 5  # newest first, case-insensitive
    assert "Contoso Komfort" in r["matches"][1]["snippet"]
    assert [h["index"] for h in a.find_in(s, "contoso", roles={"user"})["matches"]] == [4]
    page = a.transcript(s, before="3", start_at=1)  # load back from the cursor to the hit at once
    assert [m["id"] for m in page["messages"]] == ["m1", "m2"] and page["before"] == "1"
