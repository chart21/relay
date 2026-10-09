"""Sandbox for the whole test run: nothing may touch the real HOME, notifyd state/socket/config or tmux server."""
import atexit
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

_SANDBOX = Path(tempfile.mkdtemp(prefix="notifyd-test-"))
(_SANDBOX / "home").mkdir()
os.environ["HOME"] = str(_SANDBOX / "home")
os.environ["NOTIFYD_STATE"] = str(_SANDBOX / "state")
os.environ["NOTIFYD_SOCKET"] = str(_SANDBOX / "state" / "notifyd.sock")
os.environ["NOTIFYD_CONFIG"] = str(_SANDBOX / "config" / "config.yaml")
os.environ["NOTIFYD_TMUX_SOCKET"] = f"notifyd-test-{os.getpid()}"
os.environ["NOTIFYD_TRUST_WATCH"] = "0"  # launcher tests must not leave trust watchers running
for var in ("TMUX", "TMUX_PANE", "CLAUDE_CONFIG_DIR", "CODEX_HOME", "SSH_ORIGINAL_COMMAND"):
    os.environ.pop(var, None)


def _cleanup():
    subprocess.run(["tmux", "-L", os.environ["NOTIFYD_TMUX_SOCKET"], "kill-server"], capture_output=True)
    shutil.rmtree(_SANDBOX, ignore_errors=True)


atexit.register(_cleanup)

import pytest  # noqa: E402

HAVE_TMUX = shutil.which("tmux") is not None
needs_tmux = pytest.mark.skipif(not HAVE_TMUX, reason="tmux not installed")


_counter = iter(range(10_000))


@pytest.fixture
def tmux_server(monkeypatch):
    """A fresh private tmux server per test (NOTIFYD_TMUX_SOCKET), killed afterwards."""
    sock = f"{os.environ['NOTIFYD_TMUX_SOCKET']}-{next(_counter)}"
    monkeypatch.setenv("NOTIFYD_TMUX_SOCKET", sock)
    yield sock
    subprocess.run(["tmux", "-L", sock, "kill-server"], capture_output=True)


@pytest.fixture
def fake_bin(tmp_path):
    """An executable that sleeps, standing in for claude/codex/agy."""
    def make(name="fake-agent"):
        p = tmp_path / name
        p.write_text("#!/bin/sh\necho started \"$@\" > \"$0.args\"\nexec sleep 60\n")
        p.chmod(0o755)
        return str(p)
    return make
