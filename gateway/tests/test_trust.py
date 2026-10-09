import json
import os
import subprocess
import sys
import textwrap
import time

from conftest import needs_tmux
from notifyd import tmux, trust
from notifyd.config import Account, Config
from notifyd.install import set_claude_bypass, set_codex_bypass

CLAUDE_DIALOG = """
 Accessing workspace:
 /home/dev/relay-smoke
 Quick safety check: Is this a project you created or one you trust? (Like your
 own code, a well-known open source project, or work from your team). If not,
 take a moment to review what's in this folder first.
 Claude Code'll be able to read, edit, and execute files here.
 Security guide
 ❯ No, exit
   Yes, I trust this folder
 Enter to confirm · Esc to cancel"""

CODEX_DIALOG = """
  Folder access
  /home/dev/relay-smoke
  Trust this folder? Codex can read, edit, and run files here, subject to your
  permission settings.
› 1. Trust and continue
  2. Quit
  enter continue · esc quit"""

CLAUDE_READY = """
────────────────────────────────────────────────────────────────────────────────
❯ Try "refactor <filepath>"
────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ bypass permissions on (shift+tab to cycle)"""


def test_trust_action_on_real_screens():
    assert trust.trust_action(CLAUDE_DIALOG) == "Down"
    assert trust.trust_action(CLAUDE_DIALOG.replace(" ❯ No, exit", "   No, exit").replace("   Yes, I trust", " ❯ Yes, I trust")) == "Enter"
    assert trust.trust_action(CODEX_DIALOG) == "Enter"
    assert trust.trust_action(CODEX_DIALOG.replace("› 1. Trust", "  1. Trust").replace("  2. Quit", "› 2. Quit")) == "Up"
    assert trust.trust_action(CLAUDE_READY) is None
    assert trust.trust_action("") is None


AUTO_OFFER = """
 Make auto mode your default permission mode?
   Auto mode lets Claude handle permission prompts automatically. Claude
   checks each tool call for risky actions and prompt injection before
   executing, runs the ones it assesses as lower-risk, and blocks the rest.
   ❯ Yes, set auto mode as my default permission mode
     No, keep bypass permissions"""


def test_auto_mode_offer_is_declined():
    assert trust.trust_action(AUTO_OFFER) == "Down"
    assert trust.trust_action(AUTO_OFFER.replace("   ❯ Yes, set", "     Yes, set").replace("     No, keep", "   ❯ No, keep")) == "Enter"


def test_trust_key_uses_git_root(tmp_path):
    plain = tmp_path / "plain"
    (plain / "sub").mkdir(parents=True)
    assert trust.trust_key(str(plain / "sub")) == str((plain / "sub").resolve())
    repo = tmp_path / "repo"
    (repo / "pkg").mkdir(parents=True)
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    assert trust.trust_key(str(repo / "pkg")) == str(repo.resolve())


def test_trust_claude_edits_existing_config_only(tmp_path):
    f = tmp_path / ".claude.json"
    assert trust.trust_claude(f, "/w/p") is False and not f.exists()  # fresh account: left to the watcher
    f.write_text(json.dumps({"numStartups": 3, "projects": {"/w/p": {"allowedTools": ["Bash"]}}}))
    os.chmod(f, 0o600)
    assert trust.trust_claude(f, "/w/p") is True
    d = json.loads(f.read_text())
    assert d["numStartups"] == 3 and d["projects"]["/w/p"]["allowedTools"] == ["Bash"]
    assert d["projects"]["/w/p"]["hasTrustDialogAccepted"] is True and "mcpServers" in d["projects"]["/w/p"]
    assert oct(f.stat().st_mode & 0o777) == "0o600"
    assert trust.trust_claude(f, "/w/p") is False  # idempotent
    f.write_text("{broken")
    assert trust.trust_claude(f, "/w/p") is False and f.read_text() == "{broken"


def test_trust_codex_appends_and_updates(tmp_path):
    f = tmp_path / "config.toml"
    f.write_text('model = "m"\n\n[projects."/home/u"]\ntrust_level = "trusted"\n')
    assert trust.trust_codex(f, "/home/u/new") is True
    assert trust.trust_codex(f, "/home/u/new") is False
    assert '[projects."/home/u/new"]\ntrust_level = "trusted"' in f.read_text()
    f.write_text('[projects."/x"]\ntrust_level = "untrusted"\n[tui]\na = 1\n')
    assert trust.trust_codex(f, "/x") is True
    assert f.read_text().startswith('[projects."/x"]\ntrust_level = "trusted"\n[tui]')
    assert trust.trust_codex(tmp_path / "new.toml", 'we"ird') is True
    assert '[projects."we\\"ird"]' in (tmp_path / "new.toml").read_text()


def test_pretrust_by_tool(tmp_path):
    codex = Account("x1", "codex", "Codex", str(tmp_path / "codex"))
    assert trust.pretrust(codex, str(tmp_path)) is True
    assert f'[projects."{tmp_path.resolve()}"]' in (tmp_path / "codex" / "config.toml").read_text()
    claude = Account("c2", "claude", "C2", str(tmp_path / "c2"))
    (tmp_path / "c2").mkdir()
    (tmp_path / "c2" / ".claude.json").write_text("{}")
    assert trust.pretrust(claude, str(tmp_path)) is True
    assert json.loads((tmp_path / "c2" / ".claude.json").read_text())["projects"][str(tmp_path.resolve())]["hasTrustDialogAccepted"]
    assert trust.pretrust(Account("g1", "agy", "A"), str(tmp_path)) is False


FAKE_TUI = textwrap.dedent('''
    import os, sys, termios, tty
    fd = sys.stdin.fileno(); tty.setraw(fd)
    cur = 0
    def draw():
        sys.stdout.write("\\x1b[2J\\x1b[H Quick safety check: Is this a project you trust?\\r\\n")
        for i, o in enumerate(["No, exit", "Yes, I trust this folder"]):
            sys.stdout.write((" \\u276f " if i == cur else "   ") + o + "\\r\\n")
        sys.stdout.flush()
    draw()
    while True:
        b = os.read(fd, 16)
        if b.startswith(b"\\x1b[B"): cur = 1; draw()
        elif b.startswith(b"\\x1b[A"): cur = 0; draw()
        elif b in (b"\\r", b"\\n"):
            if cur == 0: sys.exit(1)
            open(sys.argv[1], "w").write("trusted")
            sys.stdout.write("\\x1b[2J\\x1b[H\\u276f ready\\r\\n"); sys.stdout.flush()
            os.read(fd, 1); sys.exit(0)
''')


@needs_tmux
def test_watch_accepts_dialog_in_tmux(tmux_server, tmp_path):
    script, marker = tmp_path / "tui.py", tmp_path / "trusted"
    script.write_text(FAKE_TUI)
    tmux.new_session("c1-x-0001", str(tmp_path), [sys.executable, str(script), str(marker)],
                     options={"@agent_alias": "c1", "@agent_tool": "claude"})
    time.sleep(0.5)
    assert trust.watch("c1-x-0001", timeout=6, step=0.2) == 1
    assert marker.read_text() == "trusted" and tmux.session_exists("c1-x-0001")


def test_bypass_defaults_are_idempotent(tmp_path):
    home = tmp_path / "c"
    home.mkdir()
    (home / "settings.json").write_text(json.dumps({"permissions": {"defaultMode": "auto", "allow": ["X"]}, "theme": "dark"}))
    assert set_claude_bypass([str(home)]) == [str(home / "settings.json")]
    d = json.loads((home / "settings.json").read_text())
    assert d["permissions"] == {"defaultMode": "bypassPermissions", "allow": ["X"]} and d["skipDangerousModePermissionPrompt"]
    assert set_claude_bypass([str(home)]) == []
    cx = tmp_path / "x"
    cx.mkdir()
    (cx / "config.toml").write_text('model = "m"\napproval_policy = "on-request"\n\n[projects."/h"]\ntrust_level = "trusted"\n')
    assert set_codex_bypass([str(cx)]) == [str(cx / "config.toml")]
    text = (cx / "config.toml").read_text()
    top = text.split("[projects")[0]
    assert 'approval_policy = "never"' in top and 'sandbox_mode = "danger-full-access"' in top
    assert text.count("approval_policy") == 1
    assert set_codex_bypass([str(cx)]) == []
