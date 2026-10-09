import sqlite3

from notifyd.adapters.agy import AgyAdapter, _ts
from notifyd.config import Account


def test_dormant_conversation(tmp_path):
    con = sqlite3.connect(tmp_path / "conversation_summaries.db")
    con.execute("create table conversation_summaries (conversation_id text, title text, not_fully_idle numeric,"
                " last_modified_time text, workspace_uris text, killed numeric, parent_conversation_id text)")
    con.execute("insert into conversation_summaries values ('c1','Fix it',1,'2026-10-03 13:46:46.631222248+00:00',"
                "'[\"file:///home/u\"]',0,'')")
    con.execute("insert into conversation_summaries values ('c2','Sub',0,'2026-10-03 13:46:46+00:00','[]',0,'c1')")
    con.commit()
    ad = AgyAdapter(Account("g1", "agy", home=str(tmp_path)))
    s = ad.list_sessions()[0]
    assert s.state == "exited" and s.cwd == "/home/u" and s.title == "Fix it" and s.key == "agy:g1:c1"  # no lock -> not alive
    assert ad.list_sessions(dormant=False) == []


def test_missing_store_is_not_an_error(tmp_path):  # agy 1.0.3 has no conversation store at all
    ad = AgyAdapter(Account("g1", "agy", home=str(tmp_path / "nothing")))
    assert ad.list_sessions() == []
    assert ad.transcript(__import__("notifyd.adapters.base", fromlist=["Session"]).Session("agy", "g1", "x"))["messages"] == []


def test_ts_nanoseconds():
    assert _ts("2026-10-03 13:46:46.631222248+00:00") > 1.7e9
