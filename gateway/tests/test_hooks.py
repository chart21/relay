import json
import os
import socket
import subprocess
import sys
from pathlib import Path

HOOKS = Path(__file__).resolve().parents[2] / "hooks"


def run(hook, tmp_path, stdin=None, argv=(), sock=True):
    path = str(tmp_path / "s.sock")
    srv = None
    if sock:
        srv = socket.socket(socket.AF_UNIX)
        srv.bind(path)
        srv.listen(1)
        srv.settimeout(3)
    r = subprocess.run([sys.executable, str(HOOKS / hook), *argv], input=stdin, text=True, capture_output=True,
                       env={**os.environ, "NOTIFYD_SOCKET": path}, timeout=10)
    data = None
    if srv:
        try:
            c, _ = srv.accept()
            data = json.loads(c.makefile().readline())
        except OSError:
            pass
        srv.close()
    return r, data


def test_claude_hook_forwards_event(tmp_path):
    r, msg = run("claude-notify-hook", tmp_path, json.dumps({
        "hook_event_name": "Notification", "session_id": "s1", "cwd": "/w", "message": "needs you",
        "transcript_path": "/t.jsonl", "notification_type": "permission_prompt"}))
    assert r.returncode == 0 and r.stdout == ""
    assert msg == {"type": "hook", "tool": "claude", "event": "Notification", "session_id": "s1", "cwd": "/w",
                   "message": "needs you", "notification_type": "permission_prompt", "transcript_path": "/t.jsonl"}


def test_claude_hook_never_fails(tmp_path):
    assert run("claude-notify-hook", tmp_path, "not json", sock=False)[0].returncode == 0
    assert run("claude-notify-hook", tmp_path, json.dumps({"hook_event_name": "Stop"}), sock=False)[0].returncode == 0  # no daemon


def test_codex_hook(tmp_path):
    arg = json.dumps({"type": "agent-turn-complete", "thread-id": "t1", "cwd": "/w", "last-assistant-message": "done"})
    r, msg = run("codex-notify-hook", tmp_path, argv=[arg])
    assert r.returncode == 0 and msg == {"type": "hook", "tool": "codex", "event": "agent-turn-complete",
                                         "session_id": "t1", "cwd": "/w", "message": "done"}
    assert run("codex-notify-hook", tmp_path, argv=["garbage"], sock=False)[0].returncode == 0
    assert run("codex-notify-hook", tmp_path, argv=[], sock=False)[0].returncode == 0
