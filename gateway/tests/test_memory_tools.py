import json

from notifyd import mcp_server as m


def test_remember_sections_forget_and_knowledge(tmp_path, monkeypatch):
    monkeypatch.setattr(m, "WORKSPACE", tmp_path)
    monkeypatch.setattr(m, "MEMORY", tmp_path / "memory.md")
    assert "## People" in m.read_memory()  # empty memory shows the structure
    assert m.remember("Sister Anna lives in Hamburg", "People") == "saved under People"
    assert m.remember("Prefers short answers") == "saved under Other"
    m.remember("Calls the desktop 'die Kiste'", "Nicknames and words")
    text = m.read_memory()
    lines = text.splitlines()
    assert lines.index("## People") < next(i for i, l in enumerate(lines) if "Anna" in l) < lines.index("## Preferences")
    assert any(l.startswith("- 20") and "short answers" in l for l in lines)  # dated
    assert m.remember("New heading fact", "Health").startswith("saved") and "## Health" in m.read_memory()
    assert "2 lines match" in m.forget("a") or "lines match" in m.forget("a")
    assert m.forget("Hamburg").startswith("removed") and "Hamburg" not in m.read_memory()
    (tmp_path / "garden").mkdir()
    (tmp_path / "garden" / "CLAUDE.md").write_text("# Garden\nWaters 4x a week")
    (tmp_path / "USER.md").write_text("# About the user")
    listing = json.loads(m.read_knowledge())
    assert "garden" in listing["areas"] and "user" in listing["files"]
    assert "4x a week" in m.read_knowledge("garden") and m.read_knowledge("user") == "# About the user"
    assert m.read_knowledge("../etc").startswith("unknown")


async def test_journal_tools_call_the_cli(tmp_path, monkeypatch):
    fake = tmp_path / "journal"
    fake.write_text('#!/bin/sh\necho "{\\"args\\": \\"$*\\"}" >> "$0.log"\necho "{\\"ok\\": true, \\"args\\": \\"$*\\"}"\n')
    fake.chmod(0o755)
    monkeypatch.setattr(m, "JOURNAL", fake)
    r = json.loads(await m.journal_add("gratitude", "the sun", "today"))
    assert r["args"] == "add gratitude the sun --date today"
    assert json.loads(await m.journal_prompt())["args"] == "prompt --lang auto"
    assert json.loads(await m.journal_search("sun", 7))["args"] == "search sun --days 7"
    monkeypatch.setattr(m, "JOURNAL", tmp_path / "missing")
    assert "not found" in await m.journal_show()



async def test_mail_tools_call_the_cli(tmp_path, monkeypatch):
    fake = tmp_path / "mail"
    fake.write_text('#!/usr/bin/env python3\nimport json, sys\nprint(json.dumps({"args": sys.argv[1:]}))\n')
    fake.chmod(0o755)
    monkeypatch.setattr(m, "MAIL", fake)
    r = json.loads(await m.mail_search('"Hotel booking" Berlin', sender="booking", since="3m", limit=99))
    assert r["args"] == ["search", '"Hotel booking" Berlin', "--limit", "25", "--from", "booking", "--since", "3m"]
    assert json.loads(await m.mail_search(account="webde"))["args"] == ["search", "--limit", "10", "--account", "webde"]
    assert json.loads(await m.mail_read("52245"))["args"] == ["show", "52245", "--max-chars", "4000"]


async def test_web_lookup_pool_first_then_main(monkeypatch, tmp_path):
    calls = []

    async def run(argv, env):
        calls.append(argv)
        return None if argv[0] == "python3" else "Heute 9 bis 17 Uhr. Quelle: muenchen.de"
    monkeypatch.setattr(m, "_lookup_run", run)
    monkeypatch.setattr(m, "POOL", tmp_path / "claude-pool")
    (tmp_path / "claude-pool").write_text("")
    out = await m.web_lookup("Öffnungszeiten Deutsches Museum heute", near="Isartor")
    assert out.endswith("muenchen.de") and [c[0] for c in calls] == ["python3", "claude"]
    for c in calls:
        assert c[c.index("--model") + 1] == "haiku" and c[c.index("--effort") + 1] == "low"
        assert c[c.index("--tools") + 1] == "WebSearch,WebFetch"
    assert "Near: Isartor" in calls[0][calls[0].index("-p") + 1]
