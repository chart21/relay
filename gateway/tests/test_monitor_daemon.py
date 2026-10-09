import asyncio
import json
import time

import pytest
from conftest import needs_tmux

from notifyd.adapters.base import Adapter, Session
from notifyd.config import Account, Config
from notifyd.daemon import Daemon
from notifyd.monitor import Monitor
from notifyd.state import State


class FakeAdapter(Adapter):
    tool = "fake"

    def __init__(self, alias="a"):
        self.account = Account(alias, "claude", "Fake", "/nonexistent")
        self.home = None
        self._lm = {}
        self.state, self.sent, self.alive, self.msg = "busy", [], True, "all done"
        self.sid = "s1"
        self.extra = []

    def list_sessions(self, panes=None, dormant=True):
        if not self.alive:
            return []
        return [Session("fake", self.account.alias, self.sid, state=self.state, alive=True, title="t",
                        label="Fake", last_activity=1000.0)]

    def tail_text(self, session):
        return self.msg

    def stamp(self, session):
        return self.msg

    def messages(self, session):
        return [{"id": "1", "role": "assistant", "text": self.msg, "tools": [], "ts": 1.0}, *self.extra]

    def send(self, session, text, enter=True):
        self.sent.append(text)
        return "fake"


@pytest.fixture(autouse=True)
def fast_settle(monkeypatch):
    monkeypatch.setattr("notifyd.monitor.SETTLE_STEP_S", 0.01)


def mon(ad, events, clients=lambda: True):
    async def pub(t, p):
        events.append((t, p))
    return Monitor([ad], pub, 15, 120, clients)


async def test_ready_event_once_after_baseline():
    ad, ev = FakeAdapter(), []
    m = mon(ad, ev)
    await m.check()  # baseline: busy, nothing emitted
    assert ev == []
    ad.state = "idle"
    await m.check()
    await m.check()  # still idle: must not re-notify
    assert [e[0] for e in ev] == ["session_state"]
    p = ev[0][1]
    assert p["prev_state"] == "busy" and p["session"]["state"] == "idle" and p["session"]["last_message"] == "all done"
    assert p["reason"] == "poll"


async def test_no_notification_for_session_that_starts_idle():
    ad, ev = FakeAdapter(), []
    ad.state = "idle"
    m = mon(ad, ev)
    await m.check()
    await m.check()
    assert ev == []


async def test_started_and_ended_events():
    ad, ev = FakeAdapter(), []
    ad.alive = False
    m = mon(ad, ev)
    await m.check()
    ad.alive = True
    await m.check()
    assert [e[0] for e in ev] == ["session_started"] and ev[0][1]["session"]["key"] == "fake:a:s1"
    ad.alive = False
    await m.check()
    assert ev[-1][0] == "session_ended" and ev[-1][1]["by_user"] is False and ev[-1][1]["session_key"] == "fake:a:s1"
    ad.alive = True
    await m.check()
    m.mark_killed("fake:a:s1")
    ad.alive = False
    await m.check()
    assert ev[-1][1]["by_user"] is True


async def test_hook_stop_is_instant_and_carries_reason():
    ad, ev = FakeAdapter(), []
    m = mon(ad, ev)
    await m.check()
    # the registry still says busy (lagging) but the Stop hook arrived
    await m.on_hook({"tool": "fake", "event": "Stop", "session_id": "s1"})
    assert [(e[0], e[1]["prev_state"], e[1]["session"]["state"], e[1]["reason"]) for e in ev] == [
        ("session_state", "busy", "idle", "hook:Stop")]
    await m.on_hook({"tool": "fake", "event": "UserPromptSubmit", "session_id": "s1"})
    assert ev[-1][1]["session"]["state"] == "busy" and ev[-1][1]["reason"] == "hook:UserPromptSubmit"


async def test_hook_notification_needs_input_and_idle_prompt_ignored():
    ad, ev = FakeAdapter(), []
    ad.state = "idle"
    m = mon(ad, ev)
    await m.check()
    await m.on_hook({"tool": "fake", "event": "Notification", "session_id": "s1",
                     "message": "Claude is waiting for your input", "notification_type": "idle_prompt"})
    assert ev == []
    await m.on_hook({"tool": "fake", "event": "Notification", "session_id": "s1",
                     "message": "Claude needs your permission to use Bash"})
    assert len(ev) == 1 and ev[0][1]["session"]["state"] == "needs_input" and ev[0][1]["reason"] == "hook:Notification"
    assert ev[0][1]["message"] == "Claude needs your permission to use Bash" and ev[0][1]["prev_state"] == "idle"
    await m.on_hook({"tool": "fake", "event": "UserPromptSubmit", "session_id": "s1"})
    assert ev[-1][1]["session"]["state"] == "busy"


async def test_stop_waits_for_the_reply_to_reach_the_transcript():
    ad, ev = FakeAdapter(), []
    m = mon(ad, ev)
    ad.msg = "previous answer"
    await m.check()
    await m.on_hook({"tool": "fake", "event": "UserPromptSubmit", "session_id": "s1"})
    calls = 0
    real = ad.tail_text

    def lagging(session):  # Claude fires Stop before flushing the reply: the first reads still see the old turn
        nonlocal calls
        calls += 1
        return "previous answer" if calls < 3 else "fresh reply"
    ad.tail_text = lagging
    ad.stamp = lambda session: calls  # defeat the cache like a growing file would
    await m.on_hook({"tool": "fake", "event": "Stop", "session_id": "s1"})
    assert ev[-1][1]["session"]["state"] == "idle" and ev[-1][1]["session"]["last_message"] == "fresh reply"
    ad.tail_text = real


async def test_hook_session_end_emits_ended():
    ad, ev = FakeAdapter(), []
    m = mon(ad, ev)
    await m.check()
    await m.on_hook({"tool": "fake", "event": "SessionEnd", "session_id": "s1"})
    assert ev[-1][0] == "session_ended"


async def test_codex_hook_uses_notify_reason_and_message():
    ad, ev = FakeAdapter(), []
    m = mon(ad, ev)
    await m.check()
    await m.on_hook({"tool": "fake", "event": "agent-turn-complete", "session_id": "s1", "message": "**Fixed** it"})
    assert ev[0][1]["reason"] == "hook:agent-turn-complete" and ev[0][1]["session"]["last_message"] == "Fixed it"


def test_poll_interval_slows_without_clients():
    flag = {"c": True}
    m = mon(FakeAdapter(), [], lambda: flag["c"])
    assert m.interval() == 15
    flag["c"] = False
    assert m.interval() == 120


# ---------------- daemon over the socket
class Conn:
    def __init__(self, r, w):
        self.r, self.w, self.n, self.extra = r, w, 0, []

    async def send(self, msg):
        self.w.write((json.dumps(msg) + "\n").encode())
        await self.w.drain()

    async def recv(self, timeout=3):
        if self.extra:
            return self.extra.pop(0)
        return json.loads(await asyncio.wait_for(self.r.readline(), timeout))

    async def rpc(self, method, **params):
        self.n += 1
        await self.send({"type": "rpc", "id": str(self.n), "method": method, "params": params})
        while True:
            m = json.loads(await asyncio.wait_for(self.r.readline(), 3))
            if m.get("type") == "rpc_result" and m["id"] == str(self.n):
                return m
            self.extra.append(m)


@pytest.fixture
async def daemon(tmp_path):
    cfg = Config(accounts=[Account("a", "claude", "Fake", str(tmp_path / "home"))])
    d = Daemon(cfg, State(tmp_path / "s.db"), tmp_path / "cfg.yaml")
    d.prestart_router = False
    ad = FakeAdapter()
    d.adapters = d.monitor.adapters = [ad]
    srv = await asyncio.start_unix_server(d.on_client, path=str(tmp_path / "sock"))
    conns = []

    async def connect():
        r, w = await asyncio.open_unix_connection(str(tmp_path / "sock"))
        c = Conn(r, w)
        conns.append(c)
        return c

    d.ad, d.connect, d.tmp = ad, connect, tmp_path
    yield d
    for c in conns:
        c.w.close()
    srv.close()
    await d.router_agent.close()


async def hello(c, since=0):
    await c.send({"type": "hello", "since_seq": since, "client": "t", "version": 2})
    evs = []
    while True:
        m = await c.recv()
        if m["type"] == "hello_ok":
            return evs, m
        evs.append(m)


async def test_hello_ok_and_rpcs(daemon):
    c = await daemon.connect()
    evs, ok = await hello(c)
    assert evs == [] and ok["server_version"] == 2 and ok["seq"] == 0
    assert ok["sessions"][0]["key"] == "fake:a:s1" and ok["accounts"][0]["alias"] == "a"
    assert ok["config"] == {"overview": {"alias": "c1", "model": "haiku", "effort": "low"}, "poll_fallback_s": 15}
    assert (await c.rpc("ping")) == {"type": "rpc_result", "id": "1", "ok": True, "result": {}}
    r = await c.rpc("list_sessions")
    assert r["result"]["sessions"][0]["last_message"] == "all done"
    r = await c.rpc("accounts")
    assert set(r["result"]["accounts"][0]) == {"alias", "tool", "label", "home", "logged_in"}
    bad = await c.rpc("nope")
    assert bad["ok"] is False and "unknown rpc" in bad["error"]
    assert (await c.rpc("transcript", key="nothing"))["ok"] is False


async def test_old_message_types_are_gone(daemon):
    c = await daemon.connect()
    for t in ("get_status", "request", "reply_session"):
        await c.send({"type": t, "id": "x"})
        m = await c.recv()
        assert m["type"] == "error" and "unknown message type" in m["error"]


async def test_send_rules(daemon):
    c = await daemon.connect()
    await hello(c)
    r = await c.rpc("send", key="fake:a:s1", text="x")
    assert r["ok"] is False and r["error"] == "session is busy"  # fake starts busy
    assert (await c.rpc("send", key="fake:a:s1", text="x", force=True))["result"] == {"how": "fake"}
    daemon.ad.state = "idle"
    await daemon.monitor.check()
    assert (await c.rpc("send", key="fake:a:s1", text="go"))["result"]["how"] == "fake"
    assert daemon.ad.sent == ["x", "go"]
    assert (await c.rpc("send", key="fake:a:s1"))["ok"] is False  # nothing to send
    assert (await c.rpc("dispatch", session_key="fake:a:s1", text="d"))["result"] == {"ok": True, "result": "fake"}


@needs_tmux
async def test_send_by_tmux_target(tmux_server, daemon, tmp_path):
    from notifyd import tmux
    tmux.new_session("c1-x-0001", str(tmp_path), ["cat"], options={"@agent_alias": "a", "@agent_tool": "claude"})
    c = await daemon.connect()
    await hello(c)
    r = await c.rpc("send", target="tmux:c1-x-0001", text="via target")
    assert r["ok"] is True and r["result"]["how"].startswith("tmux %")
    await asyncio.sleep(0.4)
    assert "via target" in tmux.capture(tmux.session_target("c1-x-0001"), 50, escapes=False)
    assert (await c.rpc("send", target="tmux:nope", text="x"))["ok"] is False  # not an agent session
    tmux.kill_session("c1-x-0001")


async def test_dispatch_waits_for_the_reply(daemon, monkeypatch):
    monkeypatch.setattr("notifyd.daemon.DISPATCH_POLL_S", 0.05)
    daemon.ad.state = "idle"
    await daemon.monitor.check()
    c = await daemon.connect()
    await hello(c)

    async def tracker_answers():  # tool call first, then the final answer
        await asyncio.sleep(0.15)
        daemon.ad.extra.append({"id": "2", "role": "assistant", "text": "", "tools": [{"name": "Bash", "summary": "mfp"}], "ts": time.time()})
        await asyncio.sleep(0.15)
        daemon.ad.extra.append({"id": "3", "role": "assistant", "text": "Eingetragen: 200 g Quark. Noch 1365 kcal.", "tools": [], "ts": time.time()})
    asyncio.create_task(tracker_answers())
    r = await c.rpc("dispatch", session_key="fake:a:s1", text="200 g Quark", wait_s=5)
    assert r["result"]["reply"] == "Eingetragen: 200 g Quark. Noch 1365 kcal."
    r = await c.rpc("dispatch", session_key="fake:a:s1", text="noch was", wait_s=0.3)  # nothing new in time
    assert "reply" not in r["result"] and "note" in r["result"]


async def test_dispatch_by_title(daemon, monkeypatch):
    monkeypatch.setattr("notifyd.daemon.DISPATCH_POLL_S", 0.05)
    daemon.ad.state = "idle"
    await daemon.monitor.check()
    c = await daemon.connect()
    await hello(c)
    r = await c.rpc("dispatch_title", title="T", text="hi")  # titles match case-insensitively
    assert r["result"]["ok"] is True and r["result"]["session"] == "t" and daemon.ad.sent[-1] == "hi"
    r = await c.rpc("dispatch_title", title="release notes", text="x")
    assert r["result"]["ok"] is False and "no session titled" in r["result"]["error"]


async def test_hello_and_rpc_report_published_app(daemon, monkeypatch, tmp_path):
    from notifyd import appupdate
    monkeypatch.setattr(appupdate, "info", lambda: {"version_code": 7, "version_name": "x"})
    c = await daemon.connect()
    _, ok = await hello(c)
    assert ok["app"]["version_code"] == 7
    assert (await c.rpc("app_update"))["result"]["app"]["version_name"] == "x"


async def test_hello_records_the_phone_app_version(daemon):
    c = await daemon.connect()
    c.w.write((json.dumps({"type": "hello", "since_seq": 0, "app_version": "2.0-x (400800)"}) + "\n").encode())
    await c.w.drain()
    while (await c.recv())["type"] != "hello_ok":
        pass
    assert json.loads(daemon.state.get("phone_app"))["app_version"] == "2.0-x (400800)"


async def test_phone_request_round_trip(daemon, tmp_path, monkeypatch):
    from notifyd import phonebridge
    daemon.bridge.inbox = tmp_path / "inbox"
    phone = await daemon.connect()
    await hello(phone)
    agent = await daemon.connect()
    req = asyncio.create_task(agent.rpc("phone_request", kind="message", app="threema", to="Lea", text="Ja!", wait_s=5))
    ev = await phone.recv()
    while ev["type"] != "send_request":
        ev = await phone.recv()
    assert ev["app"] == "ch.threema.app" and ev["text"] == "Ja!" and ev["seq"] > 0
    assert (await phone.rpc("send_result", request_id=ev["request_id"], ok=True))["ok"]
    assert (await req)["result"]["status"] == "sent"
    r = await phone.rpc("inbox_put", kind="sms", items=[{"id": "sms-1", "ts": 1791300000.0, "address": "+49", "body": "Code 1234", "box": "inbox"}])
    assert r["result"] == {"stored": 1, "duplicates": 0}
    assert (await agent.rpc("phone_request", kind="nope"))["ok"] is False


async def test_notice_reaches_the_phone_and_persists(daemon):
    phone = await daemon.connect()
    await hello(phone)
    agent = await daemon.connect()
    r = await agent.rpc("notice", title="MFP needs a new login", text="Cookie expired", source="mfp keepalive", tag="mfp-login")
    assert r["result"]["delivered"] is True
    ev = await phone.recv()
    while ev["type"] != "notice":
        ev = await phone.recv()
    assert ev["title"] == "MFP needs a new login" and ev["tag"] == "mfp-login" and ev["seq"] > 0
    assert (await agent.rpc("notice", title=" ", text=""))["ok"] is False


async def test_events_replay_and_live(daemon):
    c = await daemon.connect()
    await hello(c)
    await daemon.publish("monitor_error", {"error": "boom"})
    live = await c.recv()
    assert live["type"] == "monitor_error" and live["seq"] == 1 and "replay" not in live and live["ts"] > 0
    c2 = await daemon.connect()
    evs, ok = await hello(c2, since=0)
    assert [(e["type"], e["seq"], e["replay"]) for e in evs] == [("monitor_error", 1, True)] and ok["seq"] == 1
    c3 = await daemon.connect()
    evs, _ = await hello(c3, since=1)
    assert evs == []


async def test_hello_runs_immediate_check_and_clients_tracked(daemon):
    daemon.ad.alive = False
    await daemon.monitor.check()
    daemon.ad.alive = True  # appeared while nobody was connected
    assert not daemon.clients
    c = await daemon.connect()
    evs, ok = await hello(c)
    assert [e["type"] for e in evs] == ["session_started"] and evs[0]["replay"] is True
    assert daemon.monitor.interval() == 15
    c.w.close()
    await asyncio.sleep(0.2)
    assert daemon.monitor.interval() == 120 and not daemon.clients


async def test_long_calls_do_not_block(daemon):
    gate = asyncio.Event()

    async def slow(text, on_delta=None, timing=None):
        await gate.wait()
        return "slow answer"

    daemon.router_agent.ask = slow
    c = await daemon.connect()
    await hello(c)
    await c.send({"type": "rpc", "id": "A", "method": "ask", "params": {"text": "hi"}})
    assert (await c.rpc("ping"))["ok"]  # answered while ask is pending
    gate.set()
    m = await c.recv()
    assert m["id"] == "A" and m["result"]["text"] == "slow answer"


async def test_ask_streams_progress_and_logs_timing(daemon):
    async def ask(text, on_delta=None, timing=None):
        on_delta("Moment. ")
        on_delta("Fertig.")
        timing.update(first_text_ms=5, tools=[["list_sessions", 7]])
        return "Fertig."

    daemon.router_agent.ask = ask
    c = await daemon.connect()
    await hello(c)
    await c.send({"type": "rpc", "id": "S", "method": "ask", "params": {"text": "hi", "stream": True, "source": "voice"}})
    got = [await c.recv() for _ in range(3)]
    assert [m["delta"] for m in got[:2]] == ["Moment. ", "Fertig."] and {m["type"] for m in got[:2]} == {"rpc_progress"}
    assert got[2]["id"] == "S" and got[2]["result"]["text"] == "Fertig." and got[2]["result"]["timing"]["first_text_ms"] == 5
    assert (await c.rpc("voice_timing", stages={"heard": 900, "first_audio": 2100, "Bad Key": 1}, stt="device"))["ok"]
    rows = [json.loads(x) for x in (daemon.state.path.parent / "voice-timing.jsonl").read_text().splitlines()]
    assert rows[0]["kind"] == "ask" and rows[0]["source"] == "voice" and rows[0]["tools"] == [["list_sessions", 7]]
    assert rows[1]["kind"] == "voice" and rows[1]["stages"] == {"heard": 900, "first_audio": 2100}


async def test_dirs_mkdir_recent(daemon, tmp_path):
    (tmp_path / "proj" / ".git").mkdir(parents=True)
    (tmp_path / "plain").mkdir()
    (tmp_path / ".hidden").mkdir()
    (tmp_path / "file.txt").write_text("x")
    c = await daemon.connect()
    await hello(c)
    r = (await c.rpc("list_dirs", path=str(tmp_path)))["result"]
    assert [(d["name"], d["is_git"]) for d in r["dirs"]] == [("plain", False), ("proj", True)]
    assert r["parent"] == str(tmp_path.parent)
    assert [d["name"] for d in (await c.rpc("list_dirs", path=str(tmp_path), show_hidden=True))["result"]["dirs"]][0] == ".hidden"
    assert (await c.rpc("list_dirs", path=str(tmp_path / "file.txt")))["ok"] is False
    assert (await c.rpc("list_dirs", path="/"))["result"]["parent"] is None
    made = (await c.rpc("mkdir", path=str(tmp_path / "new" / "deep")))["result"]["path"]
    assert made.endswith("new/deep") and (tmp_path / "new" / "deep").is_dir()
    assert "dirs" in (await c.rpc("recent_dirs"))["result"]


async def test_launch_rpc_returns_immediately(daemon, tmp_path, fake_bin):
    from conftest import HAVE_TMUX
    if not HAVE_TMUX:
        pytest.skip("tmux")
    import os
    os.environ["NOTIFYD_TMUX_SOCKET"] += "-launch"
    try:
        daemon.cfg.accounts = [Account("a", "claude", "Fake", str(tmp_path / "h"), binary=fake_bin())]
        c = await daemon.connect()
        await hello(c)
        r = await c.rpc("launch", alias="a", cwd=str(tmp_path), prompt="go")
        assert r["ok"] and r["result"]["target"] == "tmux:" + r["result"]["tmux_session"]
        assert r["result"]["tmux_session"].startswith("a-")
        assert (await c.rpc("launch", alias="zz", cwd=str(tmp_path)))["ok"] is False
        scr = await c.rpc("screen", target=r["result"]["target"], lines=20, escapes=False)
        assert scr["ok"] and "text" in scr["result"]
        assert (await c.rpc("screen", target="tmux:nonexistent"))["ok"] is False
    finally:
        import subprocess
        subprocess.run(["tmux", "-L", os.environ["NOTIFYD_TMUX_SOCKET"], "kill-server"], capture_output=True)
        os.environ["NOTIFYD_TMUX_SOCKET"] = os.environ["NOTIFYD_TMUX_SOCKET"][:-len("-launch")]


async def test_kill_refuses_non_attachable(daemon):
    c = await daemon.connect()
    await hello(c)
    r = await c.rpc("kill", key="fake:a:s1")
    assert r["ok"] is False and "agent tmux session" in r["error"]


async def test_watch_streams_transcript_append_and_drops_on_disconnect(daemon, monkeypatch):
    monkeypatch.setattr("notifyd.daemon.WATCH_TICK_S", 0.05)
    task = asyncio.create_task(daemon._watch_loop())
    try:
        c = await daemon.connect()
        assert (await c.rpc("watch", key="fake:a:s1"))["ok"] is False  # hello first
        await hello(c)
        assert (await c.rpc("watch", key="fake:a:s1"))["ok"]
        daemon.ad.msg = "new text"
        m = await c.recv()
        assert m["type"] == "transcript_append" and m["session_key"] == "fake:a:s1" and m["messages"][0]["text"] == "new text"
        assert "seq" not in m  # ephemeral
        assert (await c.rpc("unwatch", key="fake:a:s1"))["ok"]
        c2 = await daemon.connect()
        await hello(c2)
        await c2.rpc("watch", key="fake:a:s1")
        c2.w.close()
        await asyncio.sleep(0.2)
        assert not daemon.watches.get(next(iter(daemon.watches), None))  # dropped with the connection
    finally:
        task.cancel()


async def test_confirmation_flow_and_events(daemon):
    c = await daemon.connect()
    await hello(c)
    ran = []

    async def action():
        ran.append(1)
        return "done"

    aid = await daemon.request_confirmation("do it", action)
    ev = await c.recv()
    assert ev["type"] == "needs_confirmation" and ev["action_id"] == aid and ev["description"] == "do it"
    r = await c.rpc("confirm", action_id=aid, ok=True)
    assert r["result"] == {"result": "done"} and ran == [1]
    assert (await c.recv())["type"] == "confirmation_resolved"
    assert (await c.rpc("confirm", action_id=aid, ok=True))["ok"] is False
    aid2 = await daemon.request_confirmation("no", action)
    await c.recv()
    assert (await c.rpc("confirm", action_id=aid2, ok=False))["result"] == {"result": "denied"} and ran == [1]


async def test_config_get_set(daemon):
    c = await daemon.connect()
    await hello(c)
    assert (await c.rpc("get_config"))["result"]["overview"] == {"alias": "c1", "model": "haiku", "effort": "low"}
    assert (await c.rpc("set_config", overview_alias="nope"))["ok"] is False
    daemon.cfg.accounts.append(Account("c2", "claude", "C2", str(daemon.tmp / "c2home")))
    r = await c.rpc("set_config", overview_alias="c2", overview_model="opus")
    assert r["result"] == {"overview": {"alias": "c2", "model": "opus", "effort": "low"}, "poll_fallback_s": 15}
    assert (await c.rpc("set_config", overview_effort="medium"))["result"]["overview"]["effort"] == "medium"
    assert daemon.router_agent.effort == "medium"
    assert (await c.rpc("set_config", overview_effort="turbo"))["ok"] is False
    assert daemon.router_agent.model == "opus" and daemon.router_agent.home == str(daemon.tmp / "c2home")
    assert Config.load(daemon.tmp / "cfg.yaml").overview.alias == "c2"
    assert (await c.rpc("set_config", overview_model="bad model;"))["ok"] is False


async def test_hook_message_path(daemon):
    c = await daemon.connect()
    await hello(c)
    daemon.ad.state = "idle"
    await c.send({"type": "hook", "tool": "fake", "event": "Stop", "session_id": "s1", "message": ""})
    for _ in range(40):
        await asyncio.sleep(0.05)
        if daemon.state.last_seq():
            break
    m = await c.recv()
    assert m["type"] == "session_state" and m["reason"] == "hook:Stop"


async def test_search_and_digest_rpcs(daemon, monkeypatch):
    from notifyd import daemon as daemon_mod
    from notifyd.launcher import LaunchResult
    started = []
    monkeypatch.setattr(daemon_mod, "launch", lambda cfg, alias, cwd, prompt, name: started.append((alias, prompt)) or LaunchResult("a-x-0001"))
    c = await daemon.connect()
    await hello(c)
    d = await c.rpc("session_digest", session_key="fake:a:s1")
    assert d["ok"] and "all done" in d["result"]["digest"]
    assert (await c.rpc("search_transcripts", query="x"))["result"] == {"hits": []}
    r = await c.rpc("new_session", alias="a", cwd="/nonexistent")
    assert r["result"]["ok"] is False
    r = await c.rpc("new_session", alias="a", cwd=str(daemon.tmp), prompt="p")  # starts at once, no phone approval
    assert r["result"]["ok"] and r["result"]["target"] == "tmux:a-x-0001" and started == [("a", "p")]


async def test_fake_phone_tool_against_daemon(daemon):
    import subprocess
    import sys
    from pathlib import Path
    tool = Path(__file__).resolve().parents[2] / "tools" / "fake_phone.py"
    out = await asyncio.to_thread(subprocess.run, [sys.executable, str(tool), "--socket", str(daemon.tmp / "sock"), "list"],
                                  capture_output=True, text=True, timeout=10)
    assert out.returncode == 0 and "fake:a:s1" in out.stdout and "server_version=2" in out.stdout
    out = await asyncio.to_thread(subprocess.run, [sys.executable, str(tool), "--socket", str(daemon.tmp / "sock"),
                                                   "rpc", "ping"], capture_output=True, text=True, timeout=10)
    assert '"ok": true' in out.stdout


async def test_app_published_reaches_connected_phones(daemon, monkeypatch):
    from notifyd import appupdate
    monkeypatch.setattr(appupdate, "info", lambda: {"version_code": 7, "sha256": "ab"})
    phone = await daemon.connect()
    await hello(phone)
    cli = await daemon.connect()
    assert (await cli.rpc("app_published"))["result"] == {"clients": 1}
    m = await phone.recv()
    assert m == {"type": "app_update", "app": {"version_code": 7, "sha256": "ab"}}


async def test_background_runs_send_no_events():
    class Headless(FakeAdapter):
        def list_sessions(self, panes=None, dormant=True):
            return [Session("fake", "a", "pool-run", state=self.state, alive=True, title="Reply with: ok",
                            label="Fake", last_activity=1000.0, background=True)] if self.alive else []
    ad, ev = Headless(), []
    m = mon(ad, ev)
    await m.check()
    ad.alive = True
    ad.state = "idle"
    await m.check()  # busy -> idle of a helper run: no "ready" notification
    ad.alive = False
    await m.check()  # it ends: no "ended" alert either
    assert ev == []


async def test_voice_vocab_file_plus_sessions(daemon, monkeypatch, tmp_path):
    import notifyd.router as rmod
    (tmp_path / "voice-vocab.txt").write_text("# comment\nGizmo\nWidget\n\ncloud code => Claude Code\nbad line =>\n")
    monkeypatch.setattr(rmod, "ROUTER_DIR", tmp_path)
    c = await daemon.connect()
    await hello(c)
    r = (await c.rpc("voice_vocab"))["result"]
    assert r["terms"][:2] == ["Gizmo", "Widget"] and "a" in r["terms"] and "t" in r["terms"]  # alias, live title
    assert r["corrections"] == [["cloud code", "Claude Code"]]


async def test_voice_vocab_add(daemon, monkeypatch, tmp_path):
    import notifyd.router as rmod
    (tmp_path / "voice-vocab.txt").write_text("Widget\n")
    monkeypatch.setattr(rmod, "ROUTER_DIR", tmp_path)
    c = await daemon.connect()
    await hello(c)
    assert (await c.rpc("voice_vocab_add", heard="fern wood", meant="Fernwald Gate"))["result"]["added"] == "fern wood => Fernwald Gate"
    assert (await c.rpc("voice_vocab_add", meant="Riverside"))["ok"]
    assert (await c.rpc("voice_vocab_add", meant="a => b"))["ok"] is False
    r = (await c.rpc("voice_vocab"))["result"]
    assert "Riverside" in r["terms"] and ["fern wood", "Fernwald Gate"] in r["corrections"]
