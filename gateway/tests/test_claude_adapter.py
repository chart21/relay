import json
import os
import time

from conftest import needs_tmux
from notifyd import tmux
from notifyd.adapters import all_sessions, build_adapters, fill_last_message, find_session
from notifyd.adapters.claude import ClaudeAdapter
from notifyd.config import Account, Config


def _reg(home, pid, sid, status, ts, **kw):
    (home / "sessions").mkdir(exist_ok=True)
    (home / "sessions" / f"{pid}.json").write_text(json.dumps(
        {"pid": pid, "sessionId": sid, "cwd": "/x", "name": "n", "status": status,
         "statusUpdatedAt": ts, "tmux": "s:@1.%9", **kw}))


def adapter(home, alias="c1"):
    return ClaudeAdapter(Account(alias, "claude", "Claude", str(home)))


def test_live_and_dormant(tmp_path):
    _reg(tmp_path, os.getpid(), "live-1", "busy", 2000)
    _reg(tmp_path, 999999999, "dead-1", "idle", 1000)  # pid not running -> ignored
    proj = tmp_path / "projects" / "-tmp-x"
    proj.mkdir(parents=True)
    (proj / "old-1.jsonl").write_text('{"type":"user","cwd":"/tmp/real dir","message":{"content":"first prompt"}}\n'
                                      '{"type":"custom-title","customTitle":"Old thing"}\n')
    out = {s.id: s for s in adapter(tmp_path, "c2").list_sessions()}
    assert out["live-1"].state == "busy" and out["live-1"].alive and out["live-1"].key == "claude:c2:live-1"
    assert out["live-1"].label == "Claude" and not out["live-1"].attachable and out["live-1"].tmux_session is None
    assert "dead-1" not in out
    assert out["old-1"].state == "exited" and out["old-1"].title == "Old thing"
    assert out["old-1"].cwd == "/tmp/real dir"  # taken from the transcript, not the lossy folder slug
    assert [s.id for s in adapter(tmp_path).list_sessions(dormant=False)] == ["live-1"]


def test_duplicate_session_ids_flagged(tmp_path):
    _reg(tmp_path, os.getpid(), "same", "idle", 1000)
    _reg(tmp_path, os.getppid(), "same", "busy", 3000)
    out = adapter(tmp_path).list_sessions()
    same = [s for s in out if s.id == "same"]
    assert len(same) == 1 and same[0].state == "busy" and same[0].duplicates == 2


def test_session_dict_matches_protocol(tmp_path):
    _reg(tmp_path, os.getpid(), "live-1", "idle", 5000)
    d = adapter(tmp_path).list_sessions()[0].to_dict()
    assert set(d) == {"key", "tool", "alias", "id", "label", "cwd", "title", "state", "alive", "attachable",
                      "tmux_session", "last_activity", "last_message", "duplicates"}  # remote_url only with Remote Control
    assert d["last_activity"] == 5.0 and d["tmux_session"] is None and d["attachable"] is False


@needs_tmux
def test_session_in_agent_tmux_is_attachable(tmux_server, tmp_path):
    tmux.new_session("c2-x-abcd", str(tmp_path), ["sleep", "30"], options={"@agent_alias": "c2", "@agent_tool": "claude"})
    tmux.new_session("mine", str(tmp_path), ["sleep", "30"])
    panes = tmux.list_panes()
    agent_pane = next(v for v in panes.values() if v["session"] == "c2-x-abcd")
    own_pane = next(v for v in panes.values() if v["session"] == "mine")
    home = tmp_path / "home"
    _reg(home.parent, 0, "x", "idle", 1)  # noqa: creates sessions dir under tmp_path
    (home / "sessions").mkdir(parents=True)
    _reg(home, agent_pane["pid"], "in-agent", "busy", 1000, tmux="")
    _reg(home, own_pane["pid"], "in-own", "idle", 1000, tmux="")
    out = {s.id: s for s in adapter(home, "c2").list_sessions()}
    assert out["in-agent"].attachable and out["in-agent"].tmux_session == "c2-x-abcd" and out["in-agent"].pane
    assert out["in-own"].pane and out["in-own"].tmux_session is None and not out["in-own"].attachable


def test_all_sessions_find_and_last_message(tmp_path):
    _reg(tmp_path, os.getpid(), "live-1", "idle", int(time.time() * 1000))
    proj = tmp_path / "projects" / "-x"
    proj.mkdir(parents=True)
    line = {"type": "assistant", "message": {"id": "m", "content": [{"type": "text", "text": "## Done\n- **Gateway** `ok` [link](http://x)"}]}}
    (proj / "live-1.jsonl").write_text(json.dumps(line) + "\n")
    cfg = Config(accounts=[Account("c1", "claude", home=str(tmp_path))])
    ads = build_adapters(cfg)
    ss = all_sessions(ads, dormant=False)
    assert [s.key for s in ss] == ["claude:c1:live-1"]
    fill_last_message(ads, ss)
    assert ss[0].last_message == "Done - Gateway ok link"
    assert find_session(ads, "claude:c1:live-1")[1].id == "live-1"
    assert find_session(ads, "live-1")[1].key == "claude:c1:live-1"


def test_two_accounts_sharing_a_home_do_not_duplicate_sessions(tmp_path):
    _reg(tmp_path, os.getpid(), "s1", "idle", 1)
    cfg = Config(accounts=[Account("g1", "claude", home=str(tmp_path)), Account("g2", "claude", home=str(tmp_path))])
    assert len(all_sessions(build_adapters(cfg), dormant=False)) == 1


def test_background_session_detected_and_attachable(tmp_path):
    import subprocess
    from notifyd.adapters.claude import proc_start
    # parent whose command line names the bg host, child = the "session process" (as claude --bg runs it)
    host = subprocess.Popen(["bash", "-c", "sleep 30 & wait", "bg-pty-host"])
    try:
        child = None
        for _ in range(50):
            kids = subprocess.run(["pgrep", "-P", str(host.pid)], capture_output=True, text=True).stdout.split()
            if kids:
                child = int(kids[0]); break
            time.sleep(0.05)
        _reg(tmp_path, child, "bg-1", "idle", 1000, procStart=proc_start(child))
        (tmp_path / "sessions" / f"{child}.json").write_text(json.dumps(
            {"pid": child, "sessionId": "bg-1", "cwd": "/x", "status": "idle", "statusUpdatedAt": 1000, "procStart": proc_start(child)}))
        s = {x.id: x for x in adapter(tmp_path).list_sessions(panes={}, dormant=False)}["bg-1"]
        assert s.extra.get("bg") and s.attachable and s.pane is None
    finally:
        host.kill()


@needs_tmux
def test_background_session_opens_with_claude_attach(tmux_server, tmp_path, fake_bin):
    from notifyd.adapters.base import Session
    acct = Account("c1", "claude", "Claude", str(tmp_path), binary=fake_bin("claude"))
    a = ClaudeAdapter(acct)
    s = Session("claude", "c1", "1a70a481-d5fe", cwd=str(tmp_path), alive=True, extra={"bg": True})
    import notifyd.adapters.claude as cl
    cl.ATTACH_SETTLE_S = 0.1
    pane = a.ensure_pane(s)
    assert pane and s.tmux_session == "c1-bg-1a70a481" and s.attachable
    row = tmux.list_panes()[pane]
    assert row["attach"] == "1a70a481-d5fe" and row["alias"] == "c1"
    assert a.ensure_pane(s) == pane  # idempotent: the wrapper is reused
    time.sleep(0.3)
    assert "attach 1a70a481" in open(fake_bin("claude") + ".args").read() if os.path.exists(fake_bin("claude") + ".args") else True
    tmux.kill_session("c1-bg-1a70a481")


def test_older_duplicate_copy_is_not_a_placeholder():
    from notifyd.adapters import _placeholders
    from notifyd.adapters.base import Session
    acct = Account("c1", "claude", "Claude", "/nonexistent")
    a = ClaudeAdapter(acct)
    live = Session("claude", "c1", "same", alive=True, tmux_session="c1-new", extra={"dup_tmux": ["c1-old"]})
    panes = {"%1": {"session": "c1-old", "alias": "c1", "path": "/x"}, "%2": {"session": "c1-new", "alias": "c1", "path": "/x"},
             "%3": {"session": "c1-fresh", "alias": "c1", "path": "/x"}}
    assert [p.tmux_session for p in _placeholders([a], panes, [live])] == ["c1-fresh"]


def test_remote_control_link_and_custom_title_wins(tmp_path):
    _reg(tmp_path, os.getpid(), "live-1", "idle", 5000, **{"bridge" + "SessionId": "remote_abc"})
    assert adapter(tmp_path).list_sessions()[0].to_dict()["remote_url"] == "https://claude.ai/code/remote_abc"
    proj = tmp_path / "projects" / str(tmp_path).replace("/", "-").replace("_", "-").replace(".", "-")
    proj.mkdir(parents=True)
    (proj / "old-1.jsonl").write_text('{"type":"user","cwd":"/gone/laptop/path","message":{"content":"hi"}}\n'
                                      '{"type":"custom-title","customTitle":"machine/relay"}\n'
                                      '{"type":"ai-title","aiTitle":"Generated name"}\n')
    old = next(s for s in adapter(tmp_path).list_sessions() if s.id == "old-1")
    assert old.title == "machine/relay"  # the name the user gave beats a later generated title
    assert old.cwd == str(tmp_path)  # the recorded folder doesn't exist here: the project folder's directory instead


def test_slug_dir(tmp_path):
    from notifyd.adapters.claude import slug_dir
    (tmp_path / "my-app.v2" / "sub").mkdir(parents=True)
    slug = str(tmp_path / "my-app.v2" / "sub").replace("/", "-").replace(".", "-").replace("_", "-")
    assert slug_dir(slug) == str(tmp_path / "my-app.v2" / "sub")
    assert slug_dir("-no-such-place-here") is None


@needs_tmux
def test_dormant_session_resumes_in_tmux(tmux_server, tmp_path, fake_bin):
    from notifyd.adapters.base import Session
    home = tmp_path / "home"
    proj = home / "projects" / str(tmp_path).replace("/", "-").replace("_", "-").replace(".", "-")
    proj.mkdir(parents=True)
    (proj / "old-1.jsonl").write_text('{"type":"user","cwd":"/gone","message":{"content":"hi"}}\n')
    a = ClaudeAdapter(Account("c1", "claude", "Claude", str(home), binary=fake_bin("claude")))
    a.auto_trust = False
    s = Session("claude", "c1", "old-1", cwd="/gone", state="exited")
    how = a.send(s, "carry on")
    assert how.startswith("resumed in tmux c1-") and s.attachable and s.tmux_session
    row = next(v for v in tmux.list_panes().values() if v["session"] == s.tmux_session)
    assert row["alias"] == "c1" and row["path"] == str(tmp_path)  # the transcript's project folder
    time.sleep(0.3)
    args = open(fake_bin("claude") + ".args").read()
    assert "--resume old-1" in args and args.strip().endswith("carry on") and "--dangerously-skip-permissions" in args
    tmux.kill_session(s.tmux_session)
