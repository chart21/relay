#!/usr/bin/env python3
"""Fake phone: speaks the Relay protocol (PROTOCOL.md) for smoke tests. Stdlib only.

  fake_phone.py --socket ~/.local/state/notifyd/notifyd.sock list
  fake_phone.py --ssh user@desktop.example -p 22 -i ~/key rpc launch '{"alias":"c2","cwd":"/tmp"}'
  fake_phone.py --ssh desktop events --seconds 60

Commands: list | events | rpc <method> [json-params] | hello   (all print the hello_ok summary first)
"""
import argparse
import json
import os
import socket
import subprocess
import sys
import time


class Link:
    def __init__(self, a):
        self.proc = None
        if a.ssh:
            cmd = ["ssh", "-o", "BatchMode=yes", "-p", str(a.port)] + (["-i", a.identity] if a.identity else []) + [a.ssh, "serve"]
            self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
            self.r, self.w = self.proc.stdout, self.proc.stdin
        else:
            s = socket.socket(socket.AF_UNIX)
            s.connect(os.path.expanduser(a.socket))
            self.r, self.w = s.makefile("rb"), s.makefile("wb")

    def send(self, msg):
        self.w.write((json.dumps(msg) + "\n").encode())
        self.w.flush()

    def recv(self):
        line = self.r.readline()
        if not line:
            raise SystemExit("connection closed")
        return json.loads(line)

    def close(self):
        if self.proc:
            self.proc.kill()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--socket", default=os.environ.get("NOTIFYD_SOCKET", "~/.local/state/notifyd/notifyd.sock"))
    p.add_argument("--ssh", help="user@host: use `ssh <host> serve` instead of the local socket")
    p.add_argument("-p", "--port", type=int, default=22)
    p.add_argument("-i", "--identity")
    p.add_argument("--since", type=int, default=0, help="since_seq for hello")
    p.add_argument("--seconds", type=float, default=30, help="how long `events` listens")
    p.add_argument("cmd", choices=["hello", "list", "events", "rpc"])
    p.add_argument("args", nargs="*")
    a = p.parse_args()
    link = Link(a)
    link.send({"type": "hello", "since_seq": a.since, "client": "fake-phone", "version": 2})
    replayed = 0
    while True:
        m = link.recv()
        if m["type"] == "hello_ok":
            break
        replayed += 1
        print("replay:", json.dumps(m))
    print(f"hello_ok seq={m['seq']} server_version={m['server_version']} replayed={replayed} "
          f"sessions={len(m['sessions'])} accounts={[x['alias'] for x in m['accounts']]} config={m['config']}")
    if a.cmd == "list":
        for s in m["sessions"]:
            print(f"{s['state']:11} {s['key']:50.50} {s['tmux_session'] or '-':24} {s['title'][:40]} | {s['last_message'][:60]}")
    elif a.cmd == "rpc":
        method = a.args[0]
        params = json.loads(a.args[1]) if len(a.args) > 1 else {}
        link.send({"type": "rpc", "id": "1", "method": method, "params": params})
        while True:
            r = link.recv()
            print(json.dumps(r, indent=1) if r.get("type") == "rpc_result" else f"event: {json.dumps(r)}")
            if r.get("type") == "rpc_result":
                break
    elif a.cmd == "events":
        end = time.time() + a.seconds
        while time.time() < end:
            print(json.dumps(link.recv()))
            sys.stdout.flush()
    link.close()


if __name__ == "__main__":
    main()
