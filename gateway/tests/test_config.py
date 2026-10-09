from notifyd.config import Account, Config, default_accounts, resolve_binary


def test_defaults_match_protocol():
    cfg = Config()
    assert [(a.alias, a.tool, a.label) for a in cfg.accounts] == [
        ("c1", "claude", "Claude #1"), ("c2", "claude", "Claude #2"), ("c3", "claude", "Claude #3"),
        ("x1", "codex", "Codex"), ("g1", "agy", "Antigravity")]
    assert cfg.accounts[1].home.endswith("/.claude-c2") and cfg.accounts[0].default_home
    assert cfg.accounts[0].skip_permissions is True and cfg.accounts[0].env == {}
    assert cfg.overview.alias == "c1" and cfg.overview.model == "haiku" and cfg.poll_fallback_s == 15
    assert cfg.public() == {"overview": {"alias": "c1", "model": "haiku", "effort": "low"}, "poll_fallback_s": 15}


def test_new_format_roundtrip(tmp_path):
    p = tmp_path / "c.yaml"
    p.write_text("overview: {alias: c2, model: sonnet}\npoll_fallback_s: 7\naccounts:\n"
                 "  - {alias: w1, tool: claude, label: Work, home: ~/.claude-work, skip_permissions: false, env: {A: 1}}\n"
                 "  - {alias: x1, tool: codex}\n")
    cfg = Config.load(p)
    assert cfg.overview.alias == "c2" and cfg.overview.model == "sonnet" and cfg.poll_fallback_s == 7
    w = cfg.account("w1")
    assert w.skip_permissions is False and w.env == {"A": "1"} and w.home.endswith("/.claude-work")
    assert cfg.account("x1").label == "x1" and cfg.account("x1").home.endswith("/.codex")
    out = tmp_path / "out.yaml"
    cfg.save(out)
    again = Config.load(out)
    assert [a.dump() for a in again.accounts] == [a.dump() for a in cfg.accounts]
    assert again.overview == cfg.overview and "~/.claude-work" in out.read_text()


def test_old_format_maps_name_to_alias(tmp_path):
    p = tmp_path / "old.yaml"
    p.write_text("monitor_interval_s: 300\nrouter_model: sonnet\nconfirm_sends: true\naccounts:\n"
                 "  - {tool: claude, name: default, home: ~/.claude, permission_mode: default}\n"
                 "  - {tool: claude, name: work, home: ~/.claude-work}\n"
                 "  - {tool: agy, name: default}\n  - {tool: codex, name: default}\n")
    cfg = Config.load(p)
    assert [a.alias for a in cfg.accounts] == ["c1", "work", "g1", "x1"]
    assert cfg.overview.model == "sonnet" and cfg.poll_fallback_s == 15


def test_missing_file_gives_defaults(tmp_path):
    assert [a.alias for a in Config.load(tmp_path / "nope.yaml").accounts] == [a.alias for a in default_accounts()]


def test_unknown_tool_rejected():
    import pytest
    with pytest.raises(ValueError):
        Account("z", "vim")


def test_resolve_binary(tmp_path, monkeypatch):
    b = tmp_path / "tool"
    b.write_text("#!/bin/sh\n")
    b.chmod(0o755)
    assert resolve_binary(str(b)) == str(b)
    assert resolve_binary("definitely-not-a-binary-xyz") is None
    monkeypatch.setenv("HOME", str(tmp_path))
    (tmp_path / ".local" / "bin").mkdir(parents=True)
    (tmp_path / ".local" / "bin" / "only-here").write_text("#!/bin/sh\n")
    (tmp_path / ".local" / "bin" / "only-here").chmod(0o755)
    assert resolve_binary("only-here") == str(tmp_path / ".local" / "bin" / "only-here")
