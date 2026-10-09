"""Overview agent: one persistent Claude session (stream-json) on the chosen account that answers the phone's questions."""
from __future__ import annotations

import asyncio
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path

from .config import SOCKET_PATH, STATE_DIR, augmented_path, resolve_binary
from .state import State

ROUTER_DIR = Path(__file__).resolve().parents[3] / "router"
WORKSPACE = Path(os.environ.get("NOTIFYD_WORKSPACE", "~/workspace")).expanduser()
MEMORY_FILE = os.environ.get("NOTIFYD_MEMORY", "~/.config/notifyd/memory.md")
# Files read once per process start into the system prompt (saves tool calls): the shared memory, plus any files in
# NOTIFYD_PRELOAD (paths separated by ":", absolute or relative to NOTIFYD_WORKSPACE).
PRELOAD = (("Shared memory (memory.md)", str(Path(MEMORY_FILE).expanduser())),
           *((Path(p).name, p) for p in os.environ.get("NOTIFYD_PRELOAD", "").split(os.pathsep) if p))
SESSION_MAX_BYTES = 600_000  # a bigger transcript makes every turn slower: start a fresh session
DAY_STARTS_H = 4  # a conversation after midnight still belongs to the evening before
FAST = {
    re.compile(r"^\s*(status|sessions|what'?s running\??)\s*$", re.I): "status",
}


def format_status(sessions: list[dict]) -> str:
    if not sessions:
        return "No live sessions."
    rows = sorted(sessions, key=lambda s: -s["last_activity"])
    return "\n".join(f"{i}. [{s['state']}] {s['alias']} {s['title'] or s['id'][:8]} ({s['cwd']})"
                     for i, s in enumerate(rows, 1))


def prompt_file(root: Path) -> Path:
    """router/CLAUDE.md (your own, git-ignored) if present, else the shipped example prompt."""
    own = root / "CLAUDE.md"
    return own if own.exists() else root / "CLAUDE.example.md"


def vocab_file(root: Path | None = None) -> Path:
    """router/voice-vocab.txt (your own, git-ignored) if present, else the shipped example."""
    root = root or ROUTER_DIR
    own = root / "voice-vocab.txt"
    return own if own.exists() else root / "voice-vocab.example.txt"


class Router:
    def __init__(self, state: State, model: str, binary: str, status_fn, root: Path = ROUTER_DIR,
                 home: str | None = None, effort: str | None = "low"):
        """home: CLAUDE_CONFIG_DIR of the overview account (None = the default ~/.claude). effort: spoken answers
        need speed, not deep reasoning (Sonnet 5.5 at its default effort thought ~15 s before a simple list_sessions)."""
        self.state, self.model, self.binary, self.status_fn, self.root, self.home = state, model, binary, status_fn, root, home
        self.effort = effort
        self.proc: asyncio.subprocess.Process | None = None
        self.sid: str | None = None
        self.lock = asyncio.Lock()

    async def reconfigure(self, model: str, home: str | None, effort: str | None = None) -> None:
        """Switch account/model/effort: the running process is replaced on the next ask."""
        async with self.lock:
            self.model, self.home = model, home
            if effort is not None:
                self.effort = effort or None
            await self.close()

    def _mcp_config(self) -> str:
        """MCP config pointing at this very interpreter, so it works under systemd (no uv/PATH needed)."""
        path = STATE_DIR / "router-mcp.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"mcpServers": {"notifyd": {
            "command": sys.executable, "args": ["-m", "notifyd", "mcp"],
            "env": {"NOTIFYD_SOCKET": str(SOCKET_PATH), "NOTIFYD_STATE": str(STATE_DIR),
                    "PATH": os.environ.get("PATH", "")}}}}))
        return str(path)

    def _env(self) -> dict:
        env = {**os.environ, "PATH": augmented_path()}
        env.pop("CLAUDE_CONFIG_DIR", None)
        if self.home:
            env["CLAUDE_CONFIG_DIR"] = self.home
        return env

    @staticmethod
    def today() -> str:
        return (datetime.now() - timedelta(hours=DAY_STARTS_H)).strftime("%Y-%m-%d")

    def _transcript(self, sid: str) -> Path | None:
        home = Path(self.home) if self.home else Path.home() / ".claude"
        return next(iter((home / "projects").glob(f"*/{sid}.jsonl")), None)

    def _session(self) -> tuple[str, bool]:
        """-> (session id, new): one session per day (and per account: a session id only exists inside its
        account), restarted early when its transcript grows past SESSION_MAX_BYTES. Durable facts live in the memory file."""
        key = f"router_session:{self.home or 'default'}"
        try:
            cur = json.loads(self.state.get(key) or "{}")
        except ValueError:
            cur = {}
        if cur.get("day") == self.today() and cur.get("id"):
            f = self._transcript(cur["id"])
            if f is None and not cur.get("used"):  # created, but no turn has finished yet
                return cur["id"], True
            if f is not None and f.stat().st_size < SESSION_MAX_BYTES:
                return cur["id"], False
        sid = str(uuid.uuid4())
        self.state.set(key, json.dumps({"id": sid, "day": self.today()}))
        return sid, True

    def _stale(self) -> bool:
        """The running process belongs to an older session (new day or transcript too big)."""
        return self.sid is not None and self._session()[0] != self.sid

    def _prompt(self) -> str:
        parts = [prompt_file(self.root).read_text()]
        for title, rel in PRELOAD:
            try:
                parts.append(f"# {title}, as of {datetime.now():%Y-%m-%d %H:%M}\n{(WORKSPACE / rel).read_text()}")
            except OSError:
                pass
        return "\n\n".join(parts)

    def _cmd(self) -> list[str]:
        sid, new = self._session()
        self.sid = sid
        return [
            resolve_binary(self.binary) or self.binary, "-p", "--model", self.model,
            *(["--effort", self.effort] if self.effort else []),
            "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages",
            "--session-id" if new else "--resume", sid,
            "--tools", "", "--strict-mcp-config", "--mcp-config", self._mcp_config(),
            "--allowedTools", "mcp__notifyd", "--setting-sources", "project",
            "--append-system-prompt", self._prompt(),
        ]

    async def prestart(self) -> None:
        """Start the process before the first question (when a phone connects): the CLI and its MCP server take a
        second or two to come up. Sends nothing, so it costs no tokens; also switches to the new day's session."""
        async with self.lock:
            if self.proc is not None and self._stale():
                await self.close()
            if self.proc is None or self.proc.returncode is not None:
                await self._start()

    async def _start(self) -> None:
        self.proc = await asyncio.create_subprocess_exec(
            *self._cmd(), cwd=self.root, env=self._env(), stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL, limit=16 * 1024 * 1024)

    async def ask(self, text: str, timeout: float = 165, on_delta=None, timing: dict | None = None) -> str:
        """The reply's final text. on_delta(str) gets the text as it streams (also what the agent says before a
        tool call, e.g. "Moment ..."). timing (filled in): ms to the first text, tool calls, total."""
        timing = {} if timing is None else timing
        t0 = time.monotonic()
        for pattern, name in FAST.items():
            if pattern.match(text) and name == "status":
                timing.update(fast=True, total_ms=0)
                return format_status(await self.status_fn())
        async with self.lock:
            timing["queued_ms"] = round((time.monotonic() - t0) * 1000)
            if self.proc is not None and self._stale():
                await self.close()
            for attempt in (1, 2):  # restart once if the process died
                if self.proc is None or self.proc.returncode is not None:
                    await self._start()
                    timing["started"] = True
                msg = {"type": "user", "message": {"role": "user", "content": text}}
                self.proc.stdin.write((json.dumps(msg) + "\n").encode())
                await self.proc.stdin.drain()
                try:
                    return await asyncio.wait_for(self._read_result(on_delta, timing, t0), timeout)
                except (EOFError, asyncio.TimeoutError) as e:
                    await self.close()
                    if attempt == 2 or isinstance(e, asyncio.TimeoutError):
                        return f"Overview agent failed ({type(e).__name__}); try again."
                finally:
                    timing["total_ms"] = round((time.monotonic() - t0) * 1000)
        return "Overview agent failed."

    async def _read_result(self, on_delta, timing: dict, t0: float) -> str:
        ms = lambda: round((time.monotonic() - t0) * 1000)  # noqa: E731
        streamed = False
        timing.setdefault("tools", [])
        while True:
            line = await self.proc.stdout.readline()
            if not line:
                raise EOFError
            try:
                d = json.loads(line)
            except ValueError:
                continue
            if d.get("type") == "stream_event":
                ev = d.get("event") or {}
                block = ev.get("content_block") or {}
                if ev.get("type") == "content_block_start" and block.get("type") == "tool_use":
                    timing["tools"].append([block.get("name", "").removeprefix("mcp__notifyd__"), ms()])
                elif ev.get("type") == "content_block_start" and block.get("type") == "text" and streamed and on_delta:
                    on_delta("\n")  # a new text block (e.g. after a tool call) starts a new sentence
                elif ev.get("type") == "content_block_delta" and (ev.get("delta") or {}).get("type") == "text_delta":
                    timing.setdefault("first_text_ms", ms())
                    streamed = True
                    if on_delta:
                        on_delta(ev["delta"].get("text") or "")
            elif d.get("type") == "result":
                timing.update(turns=d.get("num_turns"), api_ms=d.get("duration_api_ms"))
                if self.sid:
                    self._mark_used()
                return d.get("result") or "(no reply)"

    def _mark_used(self) -> None:
        key = f"router_session:{self.home or 'default'}"
        try:
            cur = json.loads(self.state.get(key) or "{}")
        except ValueError:
            return
        if cur.get("id") == self.sid and not cur.get("used"):
            self.state.set(key, json.dumps({**cur, "used": True}))

    async def close(self) -> None:
        if self.proc and self.proc.returncode is None:
            self.proc.kill()
            await self.proc.wait()
        self.proc = None
        self.sid = None
