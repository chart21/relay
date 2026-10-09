"""Usage limits per account for the phone's Accounts screen.

Claude: an optional helper script (NOTIFYD_CLAUDE_POOL, see README) that reports 5-hour and weekly utilization from the
endpoint Claude Code's /usage uses is imported; without it the Accounts screen shows no Claude limits. Codex: the newest `rate_limits` its own session logs
recorded. agy: no source known. Results are cached for CACHE_S so the phone can't hammer the unofficial endpoint.
"""
from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import os
import time
from datetime import datetime
from pathlib import Path

from .config import Account, Config

CLAUDE_POOL = Path(os.environ.get("NOTIFYD_CLAUDE_POOL", "~/.config/notifyd/claude-pool")).expanduser()
CACHE_S = 120
_cache: dict[str, tuple[float, dict]] = {}
_pool = None


def _claude_pool():
    global _pool
    if _pool is None:
        loader = importlib.machinery.SourceFileLoader("claude_pool", str(CLAUDE_POOL))
        spec = importlib.util.spec_from_loader("claude_pool", loader)
        mod = importlib.util.module_from_spec(spec)
        loader.exec_module(mod)
        _pool = mod
    return _pool


def _ts(iso: str | None) -> float | None:
    try:
        return datetime.fromisoformat(iso).timestamp() if iso else None
    except (TypeError, ValueError):
        return None


def _claude(a: Account, renew: bool = False) -> dict:
    try:
        cp = _claude_pool()
    except (OSError, SyntaxError, ImportError) as e:
        return {"error": f"usage helper unavailable ({type(e).__name__})"}
    if renew:  # a tiny Haiku call on that account makes Claude Code refresh its login token
        try:
            cp.renew(a.alias, a.home)
        except Exception:
            pass
    u = cp.usage(a.home)
    out = {"plan": cp.plan(a.home)}
    if "error" in u:
        return {**out, "error": u["error"]}
    return {**out, "windows": [
        {"name": "5 h", "pct": round(float(u["session"]), 1), "resets_at": _ts(u.get("session_reset"))},
        {"name": "week", "pct": round(float(u["weekly"]), 1), "resets_at": _ts(u.get("weekly_reset"))}]}


def window_name(minutes: int | None) -> str:
    return {300: "5 h", 10080: "week", 43200: "30 days"}.get(int(minutes or 0)) or \
        (f"{minutes / 1440:.0f} days" if (minutes or 0) >= 1440 else f"{(minutes or 0) / 60:.0f} h")


def _codex(a: Account) -> dict:
    """Newest rate_limits event in the account's rollout logs: primary = 5-hour window, secondary = weekly."""
    files = sorted(Path(a.home).glob("sessions/**/rollout-*.jsonl"), key=lambda f: f.stat().st_mtime, reverse=True)
    for f in files[:5]:
        try:
            lines = f.read_text(errors="replace").splitlines()[-400:]
        except OSError:
            continue
        for line in reversed(lines):
            if '"rate_limits"' not in line:
                continue
            try:
                rl = (json.loads(line).get("payload") or {}).get("rate_limits") or {}
            except ValueError:
                continue
            seen = f.stat().st_mtime

            def win(w):
                reset = w.get("resets_at") or (seen + w["resets_in_seconds"] if w.get("resets_in_seconds") is not None else None)
                return {"name": window_name(w.get("window_minutes")), "pct": round(float(w.get("used_percent") or 0), 1),
                        "resets_at": reset}
            return {"plan": rl.get("plan_type") or "?", "windows": [win(w) for w in (rl.get("primary"), rl.get("secondary")) if w],
                    "as_of": seen}
    return {"error": "no data yet (Codex reports its limits once it has been used)"}


def account_usage(a: Account, renew: bool = False, refresh: bool = False) -> dict:
    hit = _cache.get(a.alias)
    if hit and not (refresh or renew) and time.time() - hit[0] < CACHE_S:
        return hit[1]
    if a.tool == "claude":
        u = _claude(a, renew)
    elif a.tool == "codex":
        u = _codex(a)
    else:
        u = {"error": "no usage data for this tool"}
    u = {"alias": a.alias, "tool": a.tool, "label": a.label, "checked_at": time.time(), **u}
    _cache[a.alias] = (time.time(), u)
    return u


def all_usage(cfg: Config, refresh: bool = False, renew: str | None = None) -> list[dict]:
    return [account_usage(a, renew=(a.alias == renew), refresh=refresh) for a in cfg.accounts]
