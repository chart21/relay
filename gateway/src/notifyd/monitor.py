"""Session state tracking: agent hooks give instant transitions, a cheap poll covers agy and anything missed.

Emits session_started / session_state / session_ended. No model calls. While no phone is connected the poll
slows down (the log still fills, the phone replays it on its next hello).
"""
from __future__ import annotations

import asyncio
import re
import time
from typing import Awaitable, Callable

from .adapters import Adapter, Session, adapter_for, all_sessions
from .adapters.base import clean_text

Publish = Callable[[str, dict], Awaitable[object]]

IDLE_PROMPT = re.compile(r"waiting for your input", re.I)  # Claude's "still idle" notification, not a question
STICKY_S = 5  # a hook-reported state wins over a lagging poll for this long
SETTLE_TRIES, SETTLE_STEP_S = 6, 0.5  # Stop fires before Claude flushes the reply to its transcript: re-read briefly
KILL_MEMORY_S = 60
ENDED_MEMORY_S = 10  # a SessionEnd-hook'd key stays hidden while the registry catches up


class Monitor:
    def __init__(self, adapters: list[Adapter], publish: Publish, poll_s: float = 15, idle_poll_s: float = 120,
                 has_clients: Callable[[], bool] = lambda: True):
        self.adapters = adapters
        self.publish = publish
        self.poll_s, self.idle_poll_s, self.has_clients = poll_s, idle_poll_s, has_clients
        self.prev: dict[str, str] = {}  # key -> last published state
        self.known: dict[str, Session] = {}  # key -> last seen live session
        self.sticky: dict[str, tuple[str, float]] = {}  # key -> (state, until)
        self.needs: dict[str, tuple[str, float]] = {}  # key -> (message, since): waiting for the user
        self.reasons: dict[str, str] = {}
        self.finished: dict[str, str] = {}  # key -> last_message from a hook, for the next event
        self.turn_start: dict[str, str] = {}  # key -> last_message when the prompt was submitted (stale for Stop)
        self.ended: dict[str, float] = {}  # keys a SessionEnd hook reported
        self.killed: dict[str, float] = {}
        self._wake = asyncio.Event()
        self._lock = asyncio.Lock()
        self._primed = False
        self._last_error = ""

    def interval(self) -> float:
        return self.poll_s if self.has_clients() else self.idle_poll_s

    def set_poll(self, seconds: float) -> None:
        self.poll_s = max(2, float(seconds))
        self._wake.set()

    def check_now(self) -> None:
        self._wake.set()

    def mark_killed(self, key: str) -> None:
        self.killed[key] = time.time()

    def live_sessions(self) -> list[Session]:
        return list(self.known.values())

    # -- hooks ------------------------------------------------------------
    def _find(self, tool: str | None, sid: str | None) -> str | None:
        return next((k for k, s in self.known.items() if s.id == sid and (not tool or s.tool == tool)), None)

    async def on_hook(self, h: dict) -> None:
        """h: {tool, event, session_id, message, notification_type, ...} from hooks/*-notify-hook."""
        tool, event, sid = h.get("tool") or "claude", h.get("event") or "", h.get("session_id")
        message = h.get("message") or ""
        reason = "notify:codex" if tool == "codex" else f"hook:{event}"
        key = self._find(tool, sid)
        if key is None and event != "SessionEnd":
            await self.check(reason)  # a brand-new session: discover it first
            key = self._find(tool, sid)
            if key is None and event == "SessionStart":
                asyncio.get_running_loop().call_later(1.0, self.check_now)  # registry may lag the hook
        now = time.time()
        if key:
            if event in ("Stop", "agent-turn-complete"):
                self.needs.pop(key, None)
                self.sticky[key] = ("idle", now + STICKY_S)
                if message:
                    self.finished[key] = message
            elif event == "UserPromptSubmit":
                self.needs.pop(key, None)
                self.sticky[key] = ("busy", now + STICKY_S)
                known = self.known.get(key)
                if known:
                    try:
                        self.turn_start[key] = await asyncio.to_thread(adapter_for(self.adapters, known).last_message, known)
                    except Exception:
                        pass
            elif event == "Notification":
                kind = h.get("notification_type") or ""
                if kind != "idle_prompt" and not (not kind and IDLE_PROMPT.search(message)):
                    self.needs[key] = (message or "needs input", now)
            elif event == "SessionEnd":
                self.ended[key] = now
            self.reasons[key] = reason
        await self.check(reason)

    # -- one pass ---------------------------------------------------------
    async def check(self, reason: str = "poll") -> list[tuple[str, dict]]:
        async with self._lock:
            sessions = await asyncio.to_thread(all_sessions, self.adapters, False)
            now = time.time()
            for table, ttl in ((self.ended, ENDED_MEMORY_S), (self.killed, KILL_MEMORY_S)):
                for k in [k for k, t in table.items() if now - t > ttl]:
                    del table[k]
            # headless runs (helpers, Jarvis, tests) are not the user's sessions: no events, no notifications
            live = {s.key: s for s in sessions if s.alive and not s.background and s.key not in self.ended}
            events: list[tuple[str, dict]] = []
            for key, s in live.items():
                state = s.state
                sticky = self.sticky.get(key)
                if sticky and now < sticky[1]:
                    state = sticky[0]
                elif sticky:
                    del self.sticky[key]
                need = self.needs.get(key)
                if need and state == "idle" and s.last_activity > need[1]:
                    del self.needs[key]  # the agent moved on after asking
                    need = None
                if need:
                    state = "needs_input"
                s.state = state
                old = self.known.get(key)
                if old:
                    s.last_message = old.last_message
                was = self.prev.get(key)
                if self._primed and (was is None or was != state):
                    settle = was == "busy" and state == "idle"
                    stale = self.turn_start.pop(key, s.last_message) if settle else s.last_message
                    await self._describe(s, self.finished.pop(key, ""), settle, stale)
                    if was is None:
                        events.append(("session_started", {"session": s.to_dict()}))
                    else:
                        payload = {"session": s.to_dict(), "prev_state": was, "reason": self.reasons.get(key, "poll")}
                        if state == "needs_input":
                            payload["message"] = need[0] if need else ""
                        events.append(("session_state", payload))
                self.prev[key] = state
                self.known[key] = s
            for key in [k for k in self.prev if k not in live]:
                last = self.known.pop(key, None)
                del self.prev[key]
                self.needs.pop(key, None)
                self.sticky.pop(key, None)
                self.finished.pop(key, None)
                self.turn_start.pop(key, None)
                if self._primed and last:
                    # a placeholder (agent tmux session not yet claimed by its tool) was replaced or never started:
                    # report it as user-driven so the phone only drops it from the list, without an "ended" alert
                    events.append(("session_ended", {"session_key": key, "session": last.to_dict(),
                                                     "by_user": key in self.killed or bool(last.extra.get("placeholder"))}))
            self.reasons.clear()
            self._primed = True  # first pass only records a baseline: no notification storm on startup
        for type_, payload in events:
            await self.publish(type_, payload)
        return events

    async def _describe(self, s: Session, hook_message: str, settle: bool = False, stale: str = "") -> None:
        """settle: a turn just finished; re-read until the transcript shows something other than `stale`."""
        if hook_message:
            s.last_message = clean_text(hook_message)
            return
        before = stale
        for i in range(SETTLE_TRIES if settle else 1):
            if i:
                await asyncio.sleep(SETTLE_STEP_S)
            try:
                text = await asyncio.to_thread(adapter_for(self.adapters, s).last_message, s)
            except Exception:
                return
            if text and text != before:
                break
        s.last_message = text

    async def run(self) -> None:
        while True:
            try:
                await self.check()
                self._last_error = ""
            except Exception as exc:  # keep monitoring through transient errors
                if repr(exc) != self._last_error:
                    self._last_error = repr(exc)
                    await self.publish("monitor_error", {"error": self._last_error})
            try:
                await asyncio.wait_for(self._wake.wait(), self.interval())
            except asyncio.TimeoutError:
                pass
            self._wake.clear()
