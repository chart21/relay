from __future__ import annotations

import argparse
from pathlib import Path
import json
import time

from .adapters import all_sessions, build_adapters, fill_last_message, find_session
from .config import Config, bin_path


def _ago(ts: float) -> str:
    d = max(0, time.time() - ts)
    return f"{int(d)}s" if d < 90 else f"{int(d // 60)}m" if d < 5400 else f"{int(d // 3600)}h"


def cmd_status(args) -> None:
    adapters = build_adapters(Config.load())
    sessions = all_sessions(adapters, dormant=args.all)
    if not args.all:
        sessions = [s for s in sessions if s.alive]
    sessions.sort(key=lambda s: -s.last_activity)
    fill_last_message(adapters, sessions[:100])
    if args.json:
        print(json.dumps([s.to_dict() for s in sessions], indent=1))
        return
    for s in sessions:
        where = s.tmux_session or (f"pane {s.pane}" if s.pane else "-")
        print(f"{s.state:11} {s.key:60.60} {where:22.22} {_ago(s.last_activity):>4}  {s.title[:50]}  [{s.cwd}]")
    from .state import State
    phone = json.loads(State().get("phone_app") or "{}")
    if phone:
        print(f"\nphone app: {phone['app_version']} (last connect {_ago(phone['ts'])} ago)")


def cmd_send(args) -> None:
    adapter, session = find_session(build_adapters(Config.load()), args.session)
    if session.state == "busy" and not args.force:
        raise SystemExit(f"session is busy; use --force to type anyway ({session.key})")
    print(adapter.send(session, args.text))


def cmd_daemon(args) -> None:
    import asyncio
    from .daemon import Daemon
    asyncio.run(Daemon().run())


def cmd_serve(args) -> None:
    import asyncio
    from .serve import serve
    raise SystemExit(asyncio.run(serve()))


def cmd_ssh_entry(args) -> None:
    from .ssh_entry import main as entry
    raise SystemExit(entry())


def cmd_mcp(args) -> None:
    from .mcp_server import run
    run()


def cmd_setup(args) -> None:
    from pathlib import Path
    from . import install as ins
    if args.what == "hooks":
        homes = [a for a in Config.load().accounts]
        print("claude:", ins.install_hooks([a.home for a in homes if a.tool == "claude"]) or "nothing (already registered)")
        changed, skipped = ins.install_codex_notify([a.home for a in homes if a.tool == "codex"])
        print("codex:", changed or "nothing (already registered)", *(f"WARNING {f} has another notify" for f in skipped))
    elif args.what == "key":
        if not args.value:
            raise SystemExit("usage: notifyd setup key '<ssh public key line>'")
        print("installed" if ins.add_authorized_key(args.value, bin_path("notifyd"), args.authorized_keys) else "already present")
    elif args.what == "accounts":
        for line in ins.setup_accounts(Path(args.config) if args.config else None) or ["nothing to do (already set up)"]:
            print(line)


def cmd_trust_watch(args) -> None:
    from .trust import watch
    watch(args.session, args.timeout)


def _rpc(method: str, params: dict, timeout: float = 30) -> dict:
    """One RPC over the daemon socket (agents' entry point for the phone bridge)."""
    import asyncio
    from .config import SOCKET_PATH

    async def go():
        r, w = await asyncio.open_unix_connection(str(SOCKET_PATH), limit=1 << 25)
        w.write((json.dumps({"type": "rpc", "id": "cli", "method": method, "params": params}) + "\n").encode())
        await w.drain()
        while True:
            m = json.loads(await asyncio.wait_for(r.readline(), timeout))
            if m.get("type") == "rpc_result":
                w.close()
                return m
    try:
        return asyncio.run(go())
    except (OSError, ConnectionError) as e:
        raise SystemExit(f"notifyd is not running ({e})")


def cmd_phone(args) -> None:
    import os
    who = os.environ.get("NOTIFYD_REQUESTER") or f"session in {os.getcwd()}"
    if args.action == "log":  # the app's errors and crashes (rpc app_log), newest last; read locally
        from .config import STATE_DIR
        path = STATE_DIR / "phone-log.jsonl"
        rows = [json.loads(x) for x in path.read_text().splitlines()[-args.n:]] if path.exists() else []
        for r in rows:
            when = time.strftime("%m-%d %H:%M:%S", time.localtime(float(r.get("ts") or r.get("received") or 0)))
            print(f"{when} {r.get('level', '?'):5} {r.get('tag', '')}: {r.get('message', '')}  [{r.get('app_version', '')}]")
            if r.get("stack") and args.stack:
                print("    " + r["stack"].replace("\n", "\n    ")[:4000])
        if not rows:
            print(f"no app errors logged ({path})")
        return
    if args.action == "status":
        m = _rpc("phone_request_status", {"request_id": args.request_id})
    elif args.action == "notify":
        m = _rpc("notice", {"title": args.title, "text": args.text, "source": args.source or who, "tag": args.tag or ""})
    elif args.action == "inbox":
        m = _rpc("inbox_recent", {"kind": args.kind, "hours": args.hours, "app": args.app or "", "query": args.query or "",
                                  "limit": args.limit})
    else:
        p = {"requested_by": who, "wait_s": args.wait}
        if args.action == "send-message":
            p.update(kind="message", app=args.app, to=args.to, text=args.text)
        elif args.action == "approve":
            p.update(kind=args.kind, summary=args.summary, text=args.text or "")
        else:
            p.update(kind="location", reason=args.reason or "")
        m = _rpc("phone_request", p, timeout=args.wait + 30)
    print(json.dumps(m.get("result") if m.get("ok") else {"error": m.get("error")}, ensure_ascii=False, indent=1))
    raise SystemExit(0 if m.get("ok") else 1)


def cmd_publish_apk(args) -> None:
    from pathlib import Path
    from .appupdate import publish
    i = publish(Path(args.apk))
    try:  # connected phones learn about it now; otherwise they keep the build their last hello_ok announced
        told = _rpc("app_published", {}, timeout=10).get("result", {}).get("clients", 0)
    except SystemExit:
        told = 0
    print(f"published Relay {i['version_name']} ({i['version_code']}): {i['size'] / 1e6:.1f} MB, "
          f"{i['gz_size'] / 1e6:.1f} MB to download; "
          + (f"told {told} connected phone(s)" if told else "phones see it on their next connect"))


def cmd_install(args) -> None:
    from .install import install
    install(args.write, args.pubkey)


def cmd_timing(args) -> None:
    """Latency of the voice assistant: gateway asks (first text, tools, total) and the phone's voice-turn stages."""
    from .config import STATE_DIR
    from .daemon import TIMING_LOG
    path = STATE_DIR / TIMING_LOG
    rows = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
    if args.stats:
        rows = [r for r in rows if r.get("trigger") == "stats"]
    rows = rows[-args.n:]
    if not rows:
        print(f"no {'battery reports' if args.stats else 'timings'} yet ({path})")
    for r in rows:
        when = time.strftime("%m-%d %H:%M:%S", time.localtime(r.get("ts", 0)))
        if r.get("trigger") == "stats":  # the phone's battery report (PhoneStats.kt), every 30 min while connected
            s = r.get("stages", {})
            wall = max(1, s.get("wall_ms", 0))
            models = s.get("run", 0) + s.get("skipped", 0)
            print(f"{when}  stats  {wall / 60000:.0f} min: cpu {100 * s.get('cpu_ms', 0) / wall:.1f} %"
                  f", mic {100 * s.get('listen_ms', 0) / wall:.0f} %"
                  f" (models on {100 * s.get('run', 0) / max(1, models):.0f} % of it, {s.get('detections', 0)} wakes)"
                  f", {s.get('events', 0)} events, {s.get('notifications', 0)} notifications"
                  f", {s.get('rx_kb', 0)}/{s.get('tx_kb', 0)} kB in/out"
                  f", battery {s.get('battery', '?')} %{' charging' if s.get('charging') else ''}"
                  f"{', screen on' if s.get('screen') else ''}"
                  + {0: ", wake word off", 2: ", wake word paused", 3: ", wake word FAILED", 4: ", wake word starting"}.get(s.get("wake"), ""))
        elif r.get("kind") == "voice":
            st = " ".join(f"{k}={v / 1000:.1f}s" for k, v in sorted(r.get("stages", {}).items(), key=lambda kv: kv[1]))
            print(f"{when}  voice  {r.get('trigger', '')}/{r.get('stt', '')}  {st}")
        else:
            tools = " ".join(f"{n}@{ms / 1000:.1f}s" for n, ms in r.get("tools") or [])
            first = r.get("first_text_ms")
            print(f"{when}  ask    {r.get('source') or '-'} effort={r.get('effort') or '-'}{' new' if r.get('started') else ''}"
                  f"  first_text={first / 1000:.1f}s" if first is not None else f"{when}  ask    {r.get('source') or '-'}",
                  f" total={r.get('total_ms', 0) / 1000:.1f}s  {tools}")


def main() -> None:
    p = argparse.ArgumentParser(prog="notifyd")
    sub = p.add_subparsers(dest="cmd", required=True)
    st = sub.add_parser("status", help="list sessions")
    st.add_argument("--all", action="store_true", help="include dormant sessions")
    st.add_argument("--json", action="store_true")
    st.set_defaults(fn=cmd_status)
    se = sub.add_parser("send", help="send text to a session")
    se.add_argument("session")
    se.add_argument("text")
    se.add_argument("--force", action="store_true")
    se.set_defaults(fn=cmd_send)
    sub.add_parser("daemon", help="run the daemon").set_defaults(fn=cmd_daemon)
    sub.add_parser("serve", help="JSON-lines bridge to the daemon (stdin/stdout)").set_defaults(fn=cmd_serve)
    sub.add_parser("ssh-entry", help="SSH forced command: dispatches $SSH_ORIGINAL_COMMAND (serve | attach <target>)"
                   ).set_defaults(fn=cmd_ssh_entry)
    sub.add_parser("mcp", help="MCP server for the overview agent").set_defaults(fn=cmd_mcp)
    tw = sub.add_parser("trust-watch", help="accept folder-trust dialogs in an agent tmux session (used by agent-run)")
    tw.add_argument("session")
    tw.add_argument("--timeout", type=float, default=45)
    tw.set_defaults(fn=cmd_trust_watch)
    su = sub.add_parser("setup", help="setup helpers used by setup.sh")
    su.add_argument("what", choices=["hooks", "key", "accounts"])
    su.add_argument("value", nargs="?")
    su.add_argument("--authorized-keys", default="~/.ssh/authorized_keys")
    su.add_argument("--config", help="config file (default: ~/.config/notifyd/config.yaml)")
    su.set_defaults(fn=cmd_setup)
    ph = sub.add_parser("phone", help="phone bridge for agents: send approvals, location, inbox (PROTOCOL.md)")
    phs = ph.add_subparsers(dest="action", required=True)
    sm = phs.add_parser("send-message", help="reply in a chat via the phone (the user approves the exact text)")
    sm.add_argument("--app", required=True, help="whatsapp | threema | signal | telegram | package name")
    sm.add_argument("--to", required=True, help="the chat as its notifications show it")
    sm.add_argument("--text", required=True)
    sm.add_argument("--wait", type=float, default=600)
    ap = phs.add_parser("approve", help="ask the user to approve sending done by the desktop (mail, invite, ...)")
    ap.add_argument("--kind", required=True, choices=["mail", "invite", "post", "order", "payment"])
    ap.add_argument("--summary", required=True, help="what will be sent, to whom")
    ap.add_argument("--text", default="")
    ap.add_argument("--wait", type=float, default=600)
    lo = phs.add_parser("location", help="one location fix from the phone (never continuous)")
    lo.add_argument("--reason", default="")
    lo.add_argument("--wait", type=float, default=120)
    nt = phs.add_parser("notify", help="show a message on the phone (no approval: it goes only to the user)")
    nt.add_argument("--title", required=True)
    nt.add_argument("--text", default="")
    nt.add_argument("--source", default="", help="who sends it, e.g. 'nightly backup'")
    nt.add_argument("--tag", default="", help="same tag replaces the earlier notice on the phone")
    stt = phs.add_parser("status", help="state of an earlier request")
    stt.add_argument("request_id")
    lg = phs.add_parser("log", help="the app's errors and crashes reported to the Desktop")
    lg.add_argument("-n", type=int, default=30)
    lg.add_argument("--stack", action="store_true", help="include stack traces")
    ib = phs.add_parser("inbox", help="recent items the phone forwarded")
    ib.add_argument("--kind", default="notification", choices=["notification", "sms", "health", "location"])
    ib.add_argument("--hours", type=float, default=24)
    ib.add_argument("--app", default="")
    ib.add_argument("--query", default="")
    ib.add_argument("--limit", type=int, default=50)
    ph.set_defaults(fn=cmd_phone)
    pa = sub.add_parser("publish-apk", help="offer an app build to the phones (self-update over SSH)")
    pa.add_argument("apk", nargs="?", default=str(Path(__file__).resolve().parents[3] /
                                                  "android/app/build/outputs/apk/debug/app-debug.apk"))
    pa.set_defaults(fn=cmd_publish_apk)
    ti = sub.add_parser("timing", help="voice assistant latency per ask and voice turn (newest last)")
    ti.add_argument("-n", type=int, default=20)
    ti.add_argument("--stats", action="store_true", help="only the phone's battery reports")
    ti.set_defaults(fn=cmd_timing)
    ins = sub.add_parser("install", help="print/write systemd unit and authorized_keys line")
    ins.add_argument("--write", action="store_true")
    ins.add_argument("--pubkey", help="phone's public key line")
    ins.set_defaults(fn=cmd_install)
    args = p.parse_args()
    args.fn(args)
