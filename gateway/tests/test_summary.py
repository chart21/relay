from notifyd import summary


def test_brief_keeps_first_sentences():
    t = "# Done\n**Merged** the branch. Tests pass. " + "More detail. " * 40
    out = summary.brief(t, 60)
    assert out.startswith("Done Merged the branch. Tests pass.") and len(out) <= 62
    assert summary.brief("```py\nx=1\n```\nAll good.") == "All good."


async def test_summarize_pool_then_main_then_brief_and_cache(tmp_path, monkeypatch):
    (tmp_path / "claude-pool").write_text("")
    monkeypatch.setattr(summary, "POOL", tmp_path / "claude-pool")
    calls = []

    async def runner(argv, env):
        calls.append(argv[0])
        assert argv[argv.index("--model") + 1] == "haiku" and argv[argv.index("--effort") + 1] == "low"
        return None if argv[0] == "python3" else "webapp auf c2 ist fertig. Bitte die Benchmarks prüfen."
    summary._cache.clear()
    r = await summary.summarize("Long output. " * 10, "webapp", "de", runner=runner)
    assert r == {"text": "webapp auf c2 ist fertig. Bitte die Benchmarks prüfen.", "how": "main"}
    assert calls[0] == "python3"  # spare account first
    assert (await summary.summarize("Long output. " * 10, "webapp", "de", runner=runner))["how"] == "cache"

    async def broken(argv, env):
        return None
    r = await summary.summarize("Refactor finished. Needs your review.", "webapp", runner=broken)
    assert r == {"text": "webapp: Refactor finished. Needs your review.", "how": "brief"}
    assert (await summary.summarize("  ", "x", runner=broken))["text"] == "x is ready."


def test_prompt_language_and_clip():
    assert "German" in summary.PROMPT.format(name="a", lang=summary.LANGS["de"], text="t")
    clipped = summary._clip("a" * 20000 + "END")
    assert len(clipped) < 13000 and clipped.endswith("END")
