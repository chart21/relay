# notifyd

The gateway of [Relay](../README.md): a Python daemon that watches AI coding agents (Claude Code, Codex, Antigravity)
running in tmux on an always-on Linux desktop and serves them to the Relay Android app over an SSH forced command.

    uv sync            # install
    uv run pytest -q   # tests
    uv run notifyd --help

See the top-level README for setup, configuration and the security model, and `PROTOCOL.md` for the wire contract.
