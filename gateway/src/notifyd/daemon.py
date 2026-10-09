"""notifyd daemon: unix-socket JSON-lines server (PROTOCOL.md), event bus, monitor host."""
from __future__ import annotations

import asyncio
import contextvars
import json
import os
import re
import time
import uuid
from pathlib import Path

from . import adapters as adapters_mod
from . import appupdate, tmux
from .adapters import SendError, adapter_for, all_sessions, build_adapters, fill_last_message
from .config import CONFIG_PATH, SOCKET_PATH, STATE_DIR, Config
from .launcher import LaunchError, launch
from .monitor import Monitor
from .phonebridge import Bridge, BridgeError
from .state import State

SERVER_VERSION = 2
PERSISTED = {"session_started", "session_state", "session_ended", "needs_confirmation",
             "confirmation_resolved", "monitor_error", "send_request", "send_resolved", "location_request", "notice"}
WATCH_TICK_S = 2.0
IDLE_POLL_S = 120
_MODEL = re.compile(r"[A-Za-z0-9._:\[\]\-]{1,100}")
EFFORTS = ("", "low", "medium", "high", "xhigh", "max")
RPC_ID: contextvars.ContextVar = contextvars.ContextVar("rpc_id", default=None)
TIMING_LOG = "voice-timing.jsonl"  # in the state dir: one line per ask (gateway) and per voice turn (phone)


class RpcError(Exception):
    pass


def _need(p: dict, name: str):
    v = p.get(name)
    if v in (None, ""):
        raise RpcError(f"missing parameter {name!r}")
    return v


def _digest(m: dict) -> tuple:
    return m["text"], tuple((t.get("name"), t.get("summary")) for t in m["tools"])


DISPATCH_MAX_WAIT_S = 90  # the overview agent's own turn limit is 165 s, the phone's `ask` 180 s
DISPATCH_POLL_S = 1.5


class Daemon:
    def __init__(self, cfg: Config | None = None, state: State | None = None, config_path: Path | None = None):
        self.cfg = cfg or Config.load()
        self.config_path = config_path or CONFIG_PATH
        self.state = state or State()
        self.bridge = Bridge(self.state)
        self.adapters = build_adapters(self.cfg)
        self.clients: set[asyncio.Queue] = set()  # connections that said hello
        self.prestart_router = True  # tests switch it off (it would start the real CLI)
        self.watches: dict[asyncio.Queue, dict[str, dict]] = {}
        self.pending: dict[str, dict] = {}  # action_id -> action awaiting phone confirmation
        self.tasks: set[asyncio.Task] = set()
        self._watch_wake = asyncio.Event()
        self.monitor = Monitor(self.adapters, self.publish, self.cfg.poll_fallback_s, IDLE_POLL_S,
                               lambda: bool(self.clients))
        from .router import ROUTER_DIR, Router
        adapters_mod.EXCLUDE_CWDS[:] = [str(ROUTER_DIR), str(STATE_DIR / "summary")]  # Jarvis and spoken summaries
        acct = self.cfg.account(self.cfg.overview.alias)
        self.router_agent = Router(self.state, self.cfg.overview.model, self.cfg.router_binary, self._live_status,
                                   home=acct.home if acct and not acct.default_home else None,
                                   effort=self.cfg.overview.effort or None)

    async def _live_status(self) -> list[dict]:
        return [s.to_dict() for s in await asyncio.to_thread(all_sessions, self.adapters, False) if s.alive]

    async def publish(self, type_: str, payload: dict) -> dict:
        ev = self.state.add_event(type_, payload)
        for q in list(self.clients):
            q.put_nowait(ev)
        return ev

    async def request_confirmation(self, description: str, action) -> str:
        aid = uuid.uuid4().hex[:8]
        self.pending[aid] = {"description": description, "run": action}
        await self.publish("needs_confirmation", {"action_id": aid, "description": description})
        return aid

    def _spawn(self, coro) -> None:
        t = asyncio.create_task(coro)
        self.tasks.add(t)
        t.add_done_callback(lambda t: (self.tasks.discard(t), t.cancelled() or t.exception()))

    # -- sessions ---------------------------------------------------------
    def _sessions(self, dormant: bool, limit: int, background: bool = False) -> list[dict]:
        """background: also headless `claude -p` runs (scripts, helpers, tests), hidden by default."""
        ss = all_sessions(self.adapters, dormant)
        if not dormant:
            ss = [s for s in ss if s.alive]
        if not background:
            ss = [s for s in ss if not s.background]
        ss.sort(key=lambda s: -s.last_activity)
        ss = fill_last_message(self.adapters, ss[:limit])
        for s in ss:
            if s.alive and s.key in self.monitor.prev:
                s.state = self.monitor.prev[s.key]  # includes hook-derived needs_input
        return [s.to_dict() for s in ss]

    def _find(self, key: str):
        try:
            return adapters_mod.find_session(self.adapters, key, dormant=False)
        except KeyError:
            try:
                return adapters_mod.find_session(self.adapters, key, dormant=True)
            except KeyError:
                raise RpcError(f"no such session: {key}") from None

    # -- connections ------------------------------------------------------
    async def on_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        q: asyncio.Queue = asyncio.Queue()
        pump = asyncio.create_task(self._pump(q, writer))
        try:
            async for line in reader:
                try:
                    msg = json.loads(line)
                    assert isinstance(msg, dict)
                except (ValueError, AssertionError):
                    q.put_nowait({"type": "error", "error": "invalid json"})
                    continue
                t = msg.get("type")
                if t == "hello":
                    await self._hello(msg, q)
                elif t == "rpc":
                    self._spawn(self._rpc_task(msg, q))
                elif t == "hook":  # from hooks/*-notify-hook: no reply
                    self._spawn(self.monitor.on_hook(msg))
                else:
                    q.put_nowait({"type": "error", "id": msg.get("id"), "error": f"unknown message type {t!r}"})
        finally:
            self.clients.discard(q)
            self.watches.pop(q, None)  # nobody to watch for
            pump.cancel()
            writer.close()

    async def _hello(self, msg: dict, q: asyncio.Queue) -> None:
        await self.monitor.check()  # one immediate state check: what changed while the phone was away is logged
        sessions = await asyncio.to_thread(self._sessions, False, 100)
        try:
            since = int(msg.get("since_seq") or 0)
        except (TypeError, ValueError):
            since = 0
        if msg.get("app_version"):  # which app build the phone runs (`notifyd status` shows it)
            self.state.set("phone_app", json.dumps({"app_version": str(msg["app_version"])[:80], "ts": time.time()}))
        # no awaits from here: replay, subscription and hello_ok are atomic with respect to publish()
        for ev in self.state.events_since(since):
            if ev["type"] in PERSISTED:
                q.put_nowait({**ev, "replay": True})
        self.clients.add(q)
        if self.prestart_router:
            self._spawn(self.router_agent.prestart())
        q.put_nowait({"type": "hello_ok", "seq": self.state.last_seq(), "server_version": SERVER_VERSION,
                      "sessions": sessions, "accounts": [a.to_public() for a in self.cfg.accounts],
                      "config": self.cfg.public(), "app": appupdate.info()})

    @staticmethod
    async def _pump(q: asyncio.Queue, writer: asyncio.StreamWriter) -> None:
        while True:
            writer.write((json.dumps(await q.get()) + "\n").encode())
            await writer.drain()

    # -- rpc --------------------------------------------------------------
    async def _rpc_task(self, msg: dict, q: asyncio.Queue) -> None:
        rid, method = msg.get("id"), str(msg.get("method") or "")
        RPC_ID.set(rid)
        try:
            fn = getattr(self, f"rpc_{method}", None) if re.fullmatch(r"[a-z_]+", method) else None
            if fn is None:
                raise RpcError(f"unknown rpc {method!r}")
            params = msg.get("params")
            reply = {"type": "rpc_result", "id": rid, "ok": True, "result": await fn(params if isinstance(params, dict) else {}, q)}
        except RpcError as e:
            reply = {"type": "rpc_result", "id": rid, "ok": False, "error": str(e)}
        except Exception as e:  # never kill the connection on a handler bug
            reply = {"type": "rpc_result", "id": rid, "ok": False, "error": repr(e)}
        q.put_nowait(reply)

    async def rpc_ping(self, p, q):
        return {}

    async def rpc_list_sessions(self, p, q):
        sessions = await asyncio.to_thread(self._sessions, bool(p.get("include_dormant")), int(p.get("limit") or 100),
                                           bool(p.get("include_background")))
        return {"sessions": sessions}

    async def rpc_accounts(self, p, q):
        return {"accounts": [a.to_public() for a in self.cfg.accounts]}

    async def rpc_list_dirs(self, p, q):
        path = os.path.abspath(os.path.expanduser(p.get("path") or "~"))
        show_hidden = bool(p.get("show_hidden"))

        def scan():
            dirs = []
            with os.scandir(path) as it:
                for e in it:
                    try:
                        if not e.is_dir() or (e.name.startswith(".") and not show_hidden):
                            continue
                    except OSError:
                        continue
                    dirs.append({"name": e.name, "path": e.path, "is_git": os.path.exists(os.path.join(e.path, ".git"))})
            return sorted(dirs, key=lambda d: d["name"].lower())

        try:
            dirs = await asyncio.to_thread(scan)
        except (NotADirectoryError, FileNotFoundError):
            raise RpcError(f"not a directory: {path}") from None
        except OSError as e:
            raise RpcError(f"cannot read {path}: {e.strerror}") from None
        parent = os.path.dirname(path)
        return {"path": path, "parent": parent if parent != path else None, "dirs": dirs}

    async def rpc_recent_dirs(self, p, q):
        limit = int(p.get("limit") or 30)

        def collect():
            seen: dict[str, dict] = {}
            for s in all_sessions(self.adapters, True):
                if s.cwd and os.path.isdir(s.cwd):
                    d = seen.setdefault(s.cwd, {"path": s.cwd, "last_used": 0.0, "tools": set()})
                    d["last_used"] = max(d["last_used"], s.last_activity)
                    d["tools"].add(s.tool)
            rows = sorted(seen.values(), key=lambda d: -d["last_used"])[:limit]
            return [{**d, "tools": sorted(d["tools"])} for d in rows]

        return {"dirs": await asyncio.to_thread(collect)}

    async def rpc_mkdir(self, p, q):
        path = os.path.abspath(os.path.expanduser(_need(p, "path")))
        try:
            os.makedirs(path, exist_ok=True)
        except OSError as e:
            raise RpcError(f"cannot create {path}: {e.strerror}") from None
        return {"path": path}

    async def rpc_launch(self, p, q):
        try:
            r = await asyncio.to_thread(launch, self.cfg, await self._auto_alias(_need(p, "alias")), _need(p, "cwd"),
                                        p.get("prompt") or None, p.get("name") or None)
        except LaunchError as e:
            raise RpcError(str(e)) from None
        loop = asyncio.get_running_loop()
        for delay in (1.5, 5):  # tools without a start hook show up on the next check
            loop.call_later(delay, self.monitor.check_now)
        return {"tmux_session": r.tmux_session, "target": r.target}

    async def rpc_transcript(self, p, q):
        adapter, s = await asyncio.to_thread(self._find, _need(p, "key"))
        start_at = int(p["from"]) if str(p.get("from", "")).isdigit() else None
        return await asyncio.to_thread(adapter.transcript, s, p.get("before"), int(p.get("limit") or 50), start_at)

    async def rpc_transcript_search(self, p, q):
        """Search inside one session's whole transcript (the chat's search): matches with their message index."""
        adapter, s = await asyncio.to_thread(self._find, _need(p, "key"))
        query = str(_need(p, "query"))
        if len(query.strip()) < 2:
            raise RpcError("query needs at least 2 characters")
        roles = {r.strip() for r in str(p.get("roles") or "").split(",") if r.strip()} or None  # e.g. "user,assistant"
        return await asyncio.to_thread(adapter.find_in, s, query, min(int(p.get("limit") or 100), 300), roles)

    async def rpc_screen(self, p, q):
        if p.get("key"):
            adapter, s = await asyncio.to_thread(self._find, p["key"])
            if not s.pane and s.extra.get("bg"):
                await asyncio.to_thread(adapter.ensure_pane, s)
            target = s.pane or (tmux.session_target(s.tmux_session) if s.tmux_session else None)
            if not target:
                raise RpcError("session is not running in tmux")
        else:
            from .ssh_entry import resolve_target
            try:
                name, _ = await asyncio.to_thread(resolve_target, _need(p, "target"))
            except ValueError as e:
                raise RpcError(str(e)) from None
            target = tmux.session_target(name)
        try:
            text = await asyncio.to_thread(tmux.capture, target, int(p.get("lines") or 2000), p.get("escapes", True))
        except RuntimeError as e:
            raise RpcError(str(e)) from None
        return {"text": text}

    async def rpc_send(self, p, q):
        text, keys = p.get("text"), p.get("keys")
        if not text and not keys:
            raise RpcError("nothing to send: give text and/or keys")
        if not p.get("key"):  # `target` (tmux:<name>): a terminal the phone has open, registered or not
            from .ssh_entry import resolve_target
            try:
                name, _ = await asyncio.to_thread(resolve_target, _need(p, "target"))
                pane = await asyncio.to_thread(tmux.active_pane, name)
                if not pane:
                    raise RuntimeError(f"tmux session {name} is gone")
                if text:
                    await asyncio.to_thread(tmux.send_text, pane, text, p.get("enter", True))
                if keys:
                    await asyncio.to_thread(tmux.send_keys, pane, [str(k) for k in keys])
            except (RuntimeError, ValueError) as e:
                raise RpcError(str(e)) from None
            return {"how": f"tmux {pane}"}
        adapter, s = await asyncio.to_thread(self._find, _need(p, "key"))
        force, how = bool(p.get("force")), ""
        try:
            if text:
                if s.duplicates > 1 and not force:
                    raise RpcError(f"session is open in {s.duplicates} panes; ambiguous")
                if self.monitor.prev.get(s.key, s.state) == "busy" and not force:
                    raise RpcError("session is busy")
                how = await asyncio.to_thread(adapter.send, s, text, p.get("enter", True))
            if keys:
                if not s.pane and s.extra.get("bg"):
                    await asyncio.to_thread(adapter.ensure_pane, s)
                if not s.pane:
                    raise RpcError("session is not in tmux; cannot send keys")
                await asyncio.to_thread(tmux.send_keys, s.pane, [str(k) for k in keys])
                how = how or f"tmux {s.pane}"
        except (SendError, RuntimeError, ValueError) as e:
            raise RpcError(str(e)) from None
        return {"how": how}

    async def rpc_kill(self, p, q):
        _, s = await asyncio.to_thread(self._find, _need(p, "key"))
        if not (s.attachable and s.tmux_session):
            raise RpcError("only sessions in an agent tmux session can be killed")
        self.monitor.mark_killed(s.key)
        try:
            await asyncio.to_thread(tmux.kill_session, s.tmux_session)
        except RuntimeError as e:
            raise RpcError(str(e)) from None
        asyncio.get_running_loop().call_later(0.5, self.monitor.check_now)
        return {}

    async def rpc_watch(self, p, q):
        key = _need(p, "key")
        if q not in self.clients:
            raise RpcError("send hello first")
        adapter, s = await asyncio.to_thread(self._find, key)
        msgs = await asyncio.to_thread(adapter.messages, s) or []
        self.watches.setdefault(q, {})[key] = {"adapter": adapter, "session": s,
                                              "seen": {m["id"]: _digest(m) for m in msgs}}
        self._watch_wake.set()
        return {}

    async def rpc_unwatch(self, p, q):
        self.watches.get(q, {}).pop(_need(p, "key"), None)
        return {}

    async def rpc_ask(self, p, q):
        """stream: true sends {"type": "rpc_progress", "id", "delta"} lines with the reply text as it is written
        (the phone speaks it sentence by sentence); the final rpc_result carries the whole reply and the timing."""
        rid, timing = RPC_ID.get(), {}
        on_delta = (lambda t: q.put_nowait({"type": "rpc_progress", "id": rid, "delta": t})) if p.get("stream") else None
        text = await self.router_agent.ask(str(_need(p, "text")), on_delta=on_delta, timing=timing)
        self._log_timing({"kind": "ask", "source": str(p.get("source") or "")[:20], "stream": bool(on_delta),
                          "model": self.router_agent.model, "effort": self.router_agent.effort, **timing})
        return {"text": text, "timing": timing}

    def _log_timing(self, row: dict) -> None:
        try:
            with (self.state.path.parent / TIMING_LOG).open("a") as f:
                f.write(json.dumps({"ts": round(time.time(), 1), **row}) + "\n")
        except OSError:
            pass

    async def rpc_voice_timing(self, p, q):
        """The phone's stages of one voice turn (ms since the wake word or mic tap): see `notifyd timing`."""
        stages = {k: int(v) for k, v in (p.get("stages") or {}).items()
                  if isinstance(v, (int, float)) and re.fullmatch(r"[a-z_]{1,30}", str(k))}
        self._log_timing({"kind": "voice", "stt": str(p.get("stt") or "")[:10], "trigger": str(p.get("trigger") or "")[:10],
                          "stages": stages})
        return {}

    async def rpc_speak_summary(self, p, q):
        """1-3 spoken sentences about a session's last reply (the phone reads finished sessions aloud)."""
        from .summary import summarize
        key = _need(p, "key")
        try:
            adapter, s = await asyncio.to_thread(self._find, key)
            msgs = (await asyncio.to_thread(adapter.transcript, s, None, 8)).get("messages") or []
        except RpcError:
            raise
        except Exception as e:
            raise RpcError(f"cannot read {key}: {e}") from None
        text = next((m.get("text") or "" for m in reversed(msgs) if m.get("role") == "assistant" and (m.get("text") or "").strip()), "")
        name = str(p.get("name") or s.title or os.path.basename(s.cwd or "") or "The session")[:80]
        lang = str(p.get("lang") or "auto")
        return await summarize(text, name, lang, home=self.router_agent.home)

    async def rpc_voice_vocab(self, p, q):
        """Vocabulary for the phone's speech recognition: router/voice-vocab.txt or its example (terms and "heard => meant"
        corrections) plus the live session titles and account aliases."""
        from .router import vocab_file
        terms, fixes = [], []
        try:
            for line in vocab_file().read_text().splitlines():
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=>" in line:
                    heard, meant = (x.strip() for x in line.split("=>", 1))
                    if heard and meant:
                        fixes.append([heard, meant])
                else:
                    terms.append(line)
        except OSError:
            pass
        live = await asyncio.to_thread(self._sessions, False, 100)
        terms += [a.alias for a in self.cfg.accounts]
        terms += [s["title"] for s in live if s.get("title") and len(s["title"].split()) <= 5]
        seen, out = set(), []
        for t in terms:
            if t.casefold() not in seen:
                seen.add(t.casefold())
                out.append(t[:60])
        return {"terms": out[:200], "corrections": fixes[:200]}

    async def rpc_voice_vocab_add(self, p, q):
        """Teach the recognition from the phone: a term, or a correction heard -> meant (appended to the file)."""
        from .router import ROUTER_DIR, vocab_file

        def clean(v) -> str:
            v = " ".join(str(v or "").split())
            if len(v) > 60 or "=>" in v or v.startswith("#"):
                raise RpcError("terms are single lines of at most 60 characters without '=>' or a leading '#'")
            return v
        meant, heard = clean(_need(p, "meant")), clean(p.get("heard"))
        line = f"{heard} => {meant}" if heard else meant
        vocab = ROUTER_DIR / "voice-vocab.txt"
        if not vocab.exists() and vocab_file().exists():  # start from the shipped example
            vocab.write_text(vocab_file().read_text())
        with vocab.open("a") as f:
            f.write(line + "\n")
        return {"added": line}

    # -- RAM monitor: memory per session, close to free it, reopen later ----------------------------------------------
    def _closed(self) -> list[dict]:
        try:
            return json.loads(self.state.get("closed_sessions") or "[]")
        except ValueError:
            return []

    async def rpc_memory(self, p, q):
        """Desktop RAM and swap, memory per live session (its whole process tree), the biggest other programs, and the
        sessions closed from the phone (reopen them with `reopen`)."""
        from . import memory

        def run():
            panes = tmux.list_panes()
            live = [s for s in all_sessions(self.adapters, False) if s.alive and not s.background]
            roots = {s.key: r for s in live if (r := memory.root_pid(s, panes))}
            snap = memory.snapshot(roots)
            rows = []
            for s in live:
                m = snap["sessions"].get(s.key, {"mb": 0, "swap_mb": 0, "pids": 0})
                rows.append({"key": s.key, "title": s.title, "alias": s.alias, "tool": s.tool, "cwd": s.cwd,
                             "state": s.state, "closable": bool(s.tmux_session or s.pid),
                             **m})
            rows.sort(key=lambda r: -(r["mb"] + r["swap_mb"]))
            return {**{k: v for k, v in snap.items() if k != "sessions"}, "sessions": rows}

        prev = dict(self.monitor.prev)  # read here: sqlite and the monitor belong to the loop thread
        out = await asyncio.to_thread(run)
        for r in out["sessions"]:
            r["state"] = prev.get(r["key"], r["state"])
        return {**out, "closed": self._closed()[:20]}

    async def rpc_close(self, p, q):
        """Stop a session to free its memory; the conversation stays on disk and `reopen` resumes it in tmux."""
        import signal
        _, s = await asyncio.to_thread(self._find, _need(p, "key"))
        if not s.alive:
            raise RpcError("the session is not running")
        self.monitor.mark_killed(s.key)  # no "ended unexpectedly" alert
        try:
            if s.tmux_session and not s.extra.get("bg"):
                for t in [s.tmux_session, *[d for d in s.extra.get("dup_tmux", []) if d]]:  # older copies hold RAM too
                    await asyncio.to_thread(tmux.kill_session, t)
            elif s.pid:
                os.kill(s.pid, signal.SIGTERM)
            else:
                raise RpcError("no process to stop")
        except (RuntimeError, ProcessLookupError) as e:
            raise RpcError(str(e)) from None
        entry = {"key": s.key, "title": s.title, "alias": s.alias, "tool": s.tool, "id": s.id, "cwd": s.cwd,
                 "closed_at": time.time(), "mb": int(p.get("mb") or 0)}
        self.state.set("closed_sessions", json.dumps([entry, *[c for c in self._closed() if c["key"] != s.key]][:30]))
        asyncio.get_running_loop().call_later(0.5, self.monitor.check_now)
        return {"closed": entry}

    async def rpc_reopen(self, p, q):
        """Resume a closed (or any finished) Claude session interactively in a new tmux session."""
        _, s = await asyncio.to_thread(self._find, _need(p, "key"))
        if s.alive:
            raise RpcError("the session is already running")
        args = {"claude": ["--resume", s.id], "codex": ["resume", s.id]}.get(s.tool)
        if not args:
            raise RpcError(f"{s.tool} sessions cannot be reopened from the phone")
        cwd = s.cwd if s.cwd and os.path.isdir(s.cwd) else os.path.expanduser("~")
        try:
            r = await asyncio.to_thread(launch, self.cfg, s.alias, cwd, None, None, args)
        except LaunchError as e:
            raise RpcError(str(e)) from None
        self.state.set("closed_sessions", json.dumps([c for c in self._closed() if c["key"] != s.key]))
        loop = asyncio.get_running_loop()
        for delay in (1.5, 5):
            loop.call_later(delay, self.monitor.check_now)
        return {"tmux_session": r.tmux_session, "target": r.target}

    # -- files from the phone (share to a session) and the app's error log ------------------------------------------
    async def rpc_upload_begin(self, p, q):
        """Start a file upload from the phone (Android share sheet -> a session): returns upload_id; then upload_chunk
        (base64, in order) and upload_end, which returns the file's path on the Desktop to hand to the session."""
        from . import uploads
        try:
            return await asyncio.to_thread(uploads.begin, str(_need(p, "name")), int(p.get("size") or 0))
        except ValueError as e:
            raise RpcError(str(e)) from None

    async def rpc_upload_chunk(self, p, q):
        from . import uploads
        try:
            return await asyncio.to_thread(uploads.chunk, str(_need(p, "upload_id")), int(_need(p, "seq")), str(_need(p, "data_b64")))
        except ValueError as e:
            raise RpcError(str(e)) from None

    async def rpc_upload_end(self, p, q):
        from . import uploads
        try:
            return await asyncio.to_thread(uploads.end, str(_need(p, "upload_id")))
        except ValueError as e:
            raise RpcError(str(e)) from None

    async def rpc_app_log(self, p, q):
        """Errors and crashes from the app (it has no adb here): `notifyd phone log` shows them."""
        rows = p.get("entries") if isinstance(p.get("entries"), list) else [p]
        with (self.state.path.parent / "phone-log.jsonl").open("a") as f:
            for r in rows[:200]:
                if isinstance(r, dict):
                    f.write(json.dumps({"received": round(time.time(), 1), **{k: str(v)[:8000] for k, v in r.items()
                                                                               if k in ("ts", "level", "tag", "message", "stack", "app_version")}}) + "\n")
        return {"stored": min(len(rows), 200)}

    async def rpc_app_published(self, p, q):
        """From `notifyd publish-apk`: tell connected phones about the new build (not logged; hello_ok carries it)."""
        info = appupdate.info()
        for c in list(self.clients):
            c.put_nowait({"type": "app_update", "app": info})
        return {"clients": len(self.clients)}

    async def rpc_stt_warm(self, p, q):
        """Load Whisper in the background (the phone calls this when it starts recording for PC transcription)."""
        from . import transcribe
        self._spawn(asyncio.to_thread(transcribe.warm))
        return {"loaded": transcribe.loaded()}

    async def rpc_transcribe(self, p, q):
        from .transcribe import transcribe
        try:
            return {"text": await asyncio.to_thread(transcribe, _need(p, "audio_b64"))}
        except RpcError:
            raise
        except Exception as e:
            raise RpcError(f"transcription failed: {e}") from None

    async def rpc_confirm(self, p, q):
        aid = str(p.get("action_id", ""))
        action = self.pending.pop(aid, None)
        if not action:
            raise RpcError("unknown or expired action")
        if not p.get("ok"):
            result = "denied"
        else:
            try:
                result = await action["run"]()
            except Exception as e:
                result = f"failed: {e}"
        await self.publish("confirmation_resolved", {"action_id": aid, "result": result})
        return {"result": result}

    async def rpc_get_config(self, p, q):
        return self.cfg.public()

    async def rpc_set_config(self, p, q):
        ov, changed = self.cfg.overview, False
        alias, model, effort = p.get("overview_alias"), p.get("overview_model"), p.get("overview_effort")
        if alias is not None:
            acct = self.cfg.account(alias)
            if acct is None or acct.tool != "claude":
                raise RpcError(f"overview_alias must be a configured claude account: {alias!r}")
            changed |= alias != ov.alias
            ov.alias = alias
        if model is not None:
            if not _MODEL.fullmatch(str(model)):
                raise RpcError(f"invalid model name {model!r}")
            changed |= model != ov.model
            ov.model = str(model)
        if effort is not None:
            if effort not in EFFORTS:
                raise RpcError(f"overview_effort must be one of {', '.join(e or '(default)' for e in EFFORTS)}")
            changed |= effort != ov.effort
            ov.effort = effort
        if changed:
            await asyncio.to_thread(self.cfg.save, self.config_path)
            acct = self.cfg.account(ov.alias)
            await self.router_agent.reconfigure(ov.model, acct.home if acct and not acct.default_home else None, ov.effort)
        return self.cfg.public()

    # -- overview agent (MCP) ---------------------------------------------
    # -- phone bridge (PROTOCOL.md "Phone bridge") -----------------------------
    async def rpc_inbox_put(self, p, q):
        try:
            return self.bridge.store(_need(p, "kind"), p.get("items") or [])  # sqlite: stay on the loop thread
        except BridgeError as e:
            raise RpcError(str(e)) from None

    async def rpc_inbox_recent(self, p, q):
        try:
            return {"items": self.bridge.recent(p.get("kind") or "notification", float(p.get("hours") or 24),
                                                p.get("app") or "", p.get("query") or "", int(p.get("limit") or 50))}
        except BridgeError as e:
            raise RpcError(str(e)) from None

    async def rpc_notice(self, p, q):
        """A plain message for the user's phone (jobs and agents: "backup job needs a new login"). Nothing is sent to anyone
        else, so no approval. Same `tag` = the phone replaces the earlier notice instead of stacking them."""
        title, text = str(p.get("title") or "").strip()[:120], str(p.get("text") or "").strip()[:2000]
        if not (title or text):
            raise RpcError("notice needs a title or a text")
        ev = await self.publish("notice", {"title": title, "text": text, "source": str(p.get("source") or "")[:80],
                                           **({"tag": str(p["tag"])[:80]} if p.get("tag") else {})})
        return {"seq": ev["seq"], "delivered": bool(self.clients)}

    async def rpc_phone_request(self, p, q):
        """From agents: queue a send approval or a location request; wait up to wait_s for the phone's answer."""
        try:
            etype, ev = self.bridge.create(p)
        except BridgeError as e:
            raise RpcError(str(e)) from None
        await self.publish(etype, ev)
        return await self.bridge.wait(ev["request_id"], min(float(p.get("wait_s") or 0), 3600))

    async def rpc_phone_request_status(self, p, q):
        try:
            return self.bridge.status(_need(p, "request_id"))
        except BridgeError as e:
            raise RpcError(str(e)) from None

    async def rpc_send_result(self, p, q):
        rid = _need(p, "request_id")
        try:
            st = self.bridge.resolve(rid, bool(p.get("ok")), p.get("error") or None)
        except BridgeError as e:
            raise RpcError(str(e)) from None
        await self.publish("send_resolved", {"request_id": rid, "ok": bool(st.get("ok")),
                                             **({"error": st["error"]} if st.get("error") else {})})
        return st

    async def rpc_location_result(self, p, q):
        try:
            return self.bridge.resolve(_need(p, "request_id"), bool(p.get("ok")), p.get("error") or None,
                                       {k: p[k] for k in ("lat", "lon", "accuracy_m", "ts") if k in p})
        except BridgeError as e:
            raise RpcError(str(e)) from None

    async def rpc_usage(self, p, q):
        from . import usage
        return {"accounts": await asyncio.to_thread(usage.all_usage, self.cfg, bool(p.get("refresh")), p.get("renew"))}

    async def rpc_app_update(self, p, q):
        return {"app": appupdate.info()}

    async def rpc_dispatch(self, p, q):
        key, text = _need(p, "session_key"), _need(p, "text")
        wait = min(max(float(p.get("wait_s") or 0), 0.0), DISPATCH_MAX_WAIT_S)
        since, before = time.time(), (await self._last_message(key)).get("id") if wait else None
        try:
            r = await self.rpc_send({"key": key, "text": text}, q)
        except RpcError as e:
            return {"ok": False, "error": str(e)}
        out = {"ok": True, "result": r["how"]}
        if wait:
            reply = await self._await_reply(key, since, before, wait)
            out.update({"reply": reply} if reply else {"note": "still working; the phone is notified when it is done"})
        return out

    async def rpc_dispatch_title(self, p, q):
        """dispatch to the newest session with this title (live or dormant): the overview agent's fixed routes."""
        title, text = _need(p, "title").strip(), _need(p, "text")
        ss = await asyncio.to_thread(all_sessions, self.adapters, True)
        hits = sorted((s for s in ss if s.title.strip().lower() == title.lower()), key=lambda s: (not s.alive, -s.last_activity))
        if not hits:
            return {"ok": False, "error": f"no session titled {title!r}"}
        r = await self.rpc_dispatch({"session_key": hits[0].key, "text": text, "wait_s": p.get("wait_s")}, q)
        return {**r, "session": hits[0].title, "state_before": hits[0].state}

    async def _last_message(self, key: str) -> dict:
        try:
            adapter, s = await asyncio.to_thread(self._find, key)
            msgs = (await asyncio.to_thread(adapter.transcript, s, None, 6)).get("messages") or []
        except Exception:  # session briefly unlisted while a resume starts
            return {}
        return {**msgs[-1], "_busy": self.monitor.prev.get(s.key, s.state) == "busy"} if msgs else {}

    async def _await_reply(self, key: str, since: float, before: str | None, wait: float) -> str | None:
        """The session's answer to a dispatched message: a new assistant text without tool calls (not the message that
        was newest before sending), once the session is no longer busy and the transcript stopped changing."""
        deadline, last = time.monotonic() + wait, None
        while time.monotonic() < deadline:
            await asyncio.sleep(DISPATCH_POLL_S)
            m = await self._last_message(key)
            final = (m.get("role") == "assistant" and (m.get("text") or "").strip() and not m.get("tools")
                     and m.get("id") != before and float(m.get("ts") or since) >= since - 5)
            sig = (m.get("id"), len(m.get("text") or ""))
            if final and not m.get("_busy") and sig == last:
                return m["text"].strip()[:2000]
            last = sig
        return None

    async def _auto_alias(self, alias: str) -> str:
        """"auto": a spare Claude account (c2-c4) with 5-hour quota left (the pool helper's pick), else the main one."""
        if alias != "auto":
            return alias
        from . import usage
        try:
            hit = await asyncio.to_thread(lambda: usage._claude_pool().pick(quiet=True))
        except Exception:
            hit = None
        if hit:
            return hit[0]
        return next((a.alias for a in self.cfg.accounts if a.tool == "claude"), "c1")

    async def rpc_new_session(self, p, q):
        alias = await self._auto_alias(_need(p, "alias"))
        cwd, prompt = os.path.expanduser(_need(p, "cwd")), p.get("prompt") or ""
        acct = self.cfg.account(alias)
        if acct is None:
            return {"ok": False, "error": f"unknown alias {alias!r}"}
        if not os.path.isdir(cwd):
            return {"ok": False, "error": f"not a directory: {cwd}"}

        try:  # no phone approval: Jarvis starts sessions on its own (bypass + Remote Control on)
            r = await asyncio.to_thread(launch, self.cfg, alias, cwd, prompt or None, None)
        except LaunchError as e:
            return {"ok": False, "error": str(e)}
        self.monitor.check_now()
        return {"ok": True, "alias": alias, "target": r.target, "tmux_session": r.tmux_session,
                "note": f"started on {acct.label} ({alias}); it shows in list_sessions within seconds and in the Claude app"}

    async def rpc_session_digest(self, p, q):
        adapter, s = await asyncio.to_thread(self._find, _need(p, "session_key"))
        turns = max(1, min(int(p.get("turns") or 6), 30))
        msgs = (await asyncio.to_thread(adapter.transcript, s, None, turns * 3))["messages"]
        lines = [f"{'User' if m['role'] == 'user' else 'Agent'}: {' '.join(m['text'].split())[:600]}"
                 for m in msgs if m["role"] in ("user", "assistant") and m["text"].strip()]
        return {"session": s.to_dict(), "digest": "\n".join(lines[-turns * 2:])}

    async def rpc_search_transcripts(self, p, q):
        query, days = str(_need(p, "query")), float(p.get("days") or 7)
        since = time.time() - days * 86400

        def run():
            hits = []
            for a in self.adapters:
                try:
                    hits += a.search(query, since)
                except Exception:
                    continue
            return sorted(hits, key=lambda h: -h["ts"])[:20]

        return {"hits": await asyncio.to_thread(run)}

    # -- transcript watches -----------------------------------------------
    def _watch_new(self, w: dict) -> list[dict]:
        out = []
        for m in (w["adapter"].messages(w["session"]) or [])[-60:]:
            d = _digest(m)
            if w["seen"].get(m["id"]) != d:
                w["seen"][m["id"]] = d
                out.append(m)
        return out

    async def _watch_loop(self) -> None:
        while True:
            if not any(self.watches.values()):
                await self._watch_wake.wait()
                self._watch_wake.clear()
            await asyncio.sleep(WATCH_TICK_S)
            for q, per in list(self.watches.items()):
                for key, w in list(per.items()):
                    try:
                        new = await asyncio.to_thread(self._watch_new, w)
                    except Exception:
                        continue
                    if new:
                        q.put_nowait({"type": "transcript_append", "session_key": key, "messages": new})

    async def run(self) -> None:
        SOCKET_PATH.parent.mkdir(parents=True, exist_ok=True)
        if SOCKET_PATH.exists():
            SOCKET_PATH.unlink()
        await self.monitor.check()  # baseline before anything can trigger events
        server = await asyncio.start_unix_server(self.on_client, path=str(SOCKET_PATH), limit=32 * 1024 * 1024)
        os.chmod(SOCKET_PATH, 0o600)
        bg = [asyncio.create_task(self.monitor.run()), asyncio.create_task(self._watch_loop())]
        try:
            async with server:
                await server.serve_forever()
        finally:
            for t in bg:
                t.cancel()
            await self.router_agent.close()
