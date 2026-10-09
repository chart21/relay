import json
import time

from notifyd import usage
from notifyd.config import Account, Config


class FakePool:
    renewed = []

    @staticmethod
    def usage(home):
        return {"error": "token expired"} if home.endswith("old") else \
            {"session": 42, "session_reset": "2026-10-06T13:30:00+02:00", "weekly": 51.5, "weekly_reset": None}

    @staticmethod
    def plan(home):
        return "pro/standard"

    @classmethod
    def renew(cls, alias, home):
        cls.renewed.append(alias)


def test_claude_windows_errors_cache_and_renew(monkeypatch, tmp_path):
    monkeypatch.setattr(usage, "_pool", FakePool)
    usage._cache.clear()
    cfg = Config(accounts=[Account("c1", "claude", "C1", str(tmp_path / "c1")), Account("c2", "claude", "C2", str(tmp_path / "old")),
                           Account("g1", "agy", "G", str(tmp_path / "g"))])
    out = {u["alias"]: u for u in usage.all_usage(cfg)}
    assert [w["name"] for w in out["c1"]["windows"]] == ["5 h", "week"] and out["c1"]["windows"][0]["pct"] == 42
    assert out["c1"]["windows"][0]["resets_at"] > 0 and out["c1"]["windows"][1]["resets_at"] is None
    assert out["c2"]["error"] == "token expired" and "error" in out["g1"]
    first = out["c1"]["checked_at"]
    assert {u["alias"]: u for u in usage.all_usage(cfg)}["c1"]["checked_at"] == first  # cached
    usage.all_usage(cfg, renew="c2")
    assert FakePool.renewed == ["c2"]


def test_codex_windows_from_rollout(tmp_path):
    usage._cache.clear()
    d = tmp_path / "codex" / "sessions" / "2026" / "10" / "05"
    d.mkdir(parents=True)
    rl = {"primary": {"used_percent": 2.0, "window_minutes": 43200, "resets_at": 1793813854},
          "secondary": {"used_percent": 30, "window_minutes": 300, "resets_in_seconds": 600}, "plan_type": "free"}
    (d / "rollout-x.jsonl").write_text(json.dumps({"type": "event_msg", "payload": {"type": "token_count", "rate_limits": rl}}) + "\n")
    u = usage.account_usage(Account("x1", "codex", "Codex", str(tmp_path / "codex")))
    assert u["plan"] == "free" and [w["name"] for w in u["windows"]] == ["30 days", "5 h"]
    assert u["windows"][1]["resets_at"] > time.time()
    assert "error" in usage.account_usage(Account("x2", "codex", "Codex", str(tmp_path / "none")))
