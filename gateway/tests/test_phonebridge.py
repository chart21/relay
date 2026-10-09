import asyncio
import json
import time
from datetime import datetime

import pytest

from notifyd.phonebridge import Bridge, BridgeError
from notifyd.state import State


def bridge(tmp_path):
    return Bridge(State(tmp_path / "s.db"), tmp_path / "inbox")


def test_store_writes_jsonl_per_day_and_dedupes(tmp_path):
    b = bridge(tmp_path)
    ts = 1791300000.0
    n = {"id": "com.whatsapp|k1|1", "ts": ts, "app": "com.whatsapp", "title": "Anna", "text": "hi", "has_reply": True}
    assert b.store("notification", [n, {**n, "id": "com.whatsapp|k2|2", "text": "ü"}]) == {"stored": 2, "duplicates": 0}
    assert b.store("notification", [n]) == {"stored": 0, "duplicates": 1}
    f = tmp_path / "inbox" / "notification" / (datetime.fromtimestamp(ts).strftime("%Y-%m-%d") + ".jsonl")
    lines = [json.loads(l) for l in f.read_text().splitlines()]
    assert [l["id"] for l in lines] == ["com.whatsapp|k1|1", "com.whatsapp|k2|2"] and "received_at" in lines[0]
    assert "ü" in f.read_text()  # UTF-8, not escaped
    b.store("health", [{"id": "steps-1-2", "type": "steps", "start": ts, "end": ts + 60, "value": 120, "unit": "count"}])
    assert (tmp_path / "inbox" / "health").is_dir()
    for bad in (("mail", []), ("sms", [{"ts": 1}]), ("sms", [{"id": "x"}]), ("sms", "nope")):
        with pytest.raises(BridgeError):
            b.store(*bad)


def test_recent_filters(tmp_path):
    b = bridge(tmp_path)
    now = time.time()
    b.store("notification", [{"id": "a", "ts": now - 60, "app": "com.whatsapp", "text": "Pizza heute?"},
                             {"id": "b", "ts": now - 30, "app": "com.example.bank", "text": "Card payment 12.50 EUR"},
                             {"id": "c", "ts": now - 3 * 86400, "app": "com.whatsapp", "text": "old"}])
    assert [i["id"] for i in b.recent()] == ["b", "a"]
    assert [i["id"] for i in b.recent(app="whatsapp")] == ["a"]
    assert [i["id"] for i in b.recent(query="card payment")] == ["b"]
    assert [i["id"] for i in b.recent(hours=100)] == ["b", "a", "c"]


async def test_request_lifecycle(tmp_path):
    b = bridge(tmp_path)
    etype, ev = b.create({"kind": "message", "app": "whatsapp", "to": "Anna", "text": "Bin gleich da", "requested_by": "x"})
    assert etype == "send_request" and ev["app"] == "com.whatsapp" and ev["executor"] == "phone" and ev["expires_at"] > time.time()
    waiter = asyncio.create_task(b.wait(ev["request_id"], 5))
    await asyncio.sleep(0.05)
    assert b.resolve(ev["request_id"], True)["status"] == "sent"
    assert (await waiter)["status"] == "sent"
    assert b.resolve(ev["request_id"], False, "denied")["status"] == "sent"  # first answer wins
    _, mail = b.create({"kind": "mail", "summary": "Reply to Max: invoice attached"})
    assert mail["executor"] == "desktop" and b.resolve(mail["request_id"], False, "denied")["status"] == "denied"
    _, loc = b.create({"kind": "location", "reason": "nearby"})
    assert (await b.wait(loc["request_id"], 0.05))["status"] == "pending"
    st = b.resolve(loc["request_id"], True, data={"lat": 48.1, "lon": 11.5, "accuracy_m": 12})
    assert st["status"] == "done" and st["lat"] == 48.1
    b.state.db.execute("update requests set expires=0 where id=?", (mail["request_id"],))
    _, old = b.create({"kind": "post", "summary": "x"})
    b.state.db.execute("update requests set expires=0 where id=?", (old["request_id"],))
    assert b.status(old["request_id"])["status"] == "expired" and b.pending_payload(old["request_id"]) is None
    for bad in ({"kind": "message", "app": "whatsapp", "text": "x"}, {"kind": "mail"}, {"kind": "nuke"}):
        with pytest.raises(BridgeError):
            b.create(bad)
