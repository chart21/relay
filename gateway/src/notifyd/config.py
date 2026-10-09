"""Gateway configuration (~/.config/notifyd/config.yaml)."""
from __future__ import annotations

import os
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

CONFIG_PATH = Path(os.environ.get("NOTIFYD_CONFIG", "~/.config/notifyd/config.yaml")).expanduser()
STATE_DIR = Path(os.environ.get("NOTIFYD_STATE", "~/.local/state/notifyd")).expanduser()
SOCKET_PATH = Path(os.environ.get("NOTIFYD_SOCKET", str(STATE_DIR / "notifyd.sock")))

TOOLS = ("claude", "codex", "agy")
DEFAULT_HOMES = {"claude": "~/.claude", "agy": "~/.gemini/antigravity-cli", "codex": "~/.codex"}
DEFAULT_BINARIES = {"claude": "claude", "agy": "agy", "codex": "codex"}
DEFAULT_ALIASES = {"claude": "c1", "codex": "x1", "agy": "g1"}  # old configs named these "default"


def _expand(p: str) -> str:
    return str(Path(p).expanduser())


def _collapse(p: str) -> str:
    home = str(Path.home())
    return "~" + p[len(home):] if p == home or p.startswith(home + "/") else p


EXTRA_BIN_DIRS = ("~/.local/bin", "~/.config/local/bin")  # not on PATH for ssh/systemd on the Desktop


def resolve_binary(binary: str) -> str | None:
    """Absolute path of a tool binary: configured absolute path, PATH, then user-local bin dirs."""
    if os.sep in binary:
        p = Path(binary).expanduser()
        return str(p) if p.exists() and os.access(p, os.X_OK) else None
    found = shutil.which(binary)
    if found:
        return found
    for d in EXTRA_BIN_DIRS:
        p = Path(d).expanduser() / binary
        if p.exists() and os.access(p, os.X_OK):
            return str(p)
    return None


def bin_path(name: str) -> str:
    """Absolute path of one of our console scripts (next to the interpreter, else PATH)."""
    sib = Path(sys.executable).parent / name
    if sib.exists():
        return str(sib)
    return shutil.which(name) or str(Path(__file__).resolve().parents[2] / ".venv" / "bin" / name)


def augmented_path(path: str | None = None) -> str:
    """PATH with the user-local bin dirs appended (so tools resolve under systemd / ssh exec)."""
    parts = (path if path is not None else os.environ.get("PATH", "")).split(os.pathsep)
    for d in EXTRA_BIN_DIRS:
        d = str(Path(d).expanduser())
        if d not in parts:
            parts.append(d)
    return os.pathsep.join(p for p in parts if p)


@dataclass
class Account:
    alias: str
    tool: str  # claude | agy | codex
    label: str = ""
    home: str = ""  # config dir: CLAUDE_CONFIG_DIR / agy data dir / CODEX_HOME
    binary: str = ""
    skip_permissions: bool = True
    env: dict = field(default_factory=dict)
    permission_mode: str = "default"  # only used when skip_permissions is off (headless resume)

    def __post_init__(self) -> None:
        if self.tool not in TOOLS:
            raise ValueError(f"unknown tool {self.tool!r} for account {self.alias!r}")
        self.home = _expand(self.home or DEFAULT_HOMES[self.tool])
        self.binary = self.binary or DEFAULT_BINARIES[self.tool]
        self.label = self.label or self.alias
        self.env = {str(k): str(v) for k, v in (self.env or {}).items()}

    @property
    def default_home(self) -> bool:
        return self.home == _expand(DEFAULT_HOMES[self.tool])

    def to_public(self) -> dict:
        return {"alias": self.alias, "tool": self.tool, "label": self.label, "home": self.home,
                "logged_in": self.logged_in()}

    def logged_in(self) -> bool:
        """Existence check only; credential files are never read."""
        h = Path(self.home)
        if self.tool == "claude":
            return (h / ".credentials.json").exists() or (h / ".claude.json").exists()
        if self.tool == "codex":
            return (h / "auth.json").exists()
        return h.exists()

    def dump(self) -> dict:
        d: dict = {"alias": self.alias, "tool": self.tool, "label": self.label, "home": _collapse(self.home)}
        if self.binary != DEFAULT_BINARIES[self.tool]:
            d["binary"] = self.binary
        if not self.skip_permissions:
            d["skip_permissions"] = False
        if self.env:
            d["env"] = dict(self.env)
        if self.permission_mode != "default":
            d["permission_mode"] = self.permission_mode
        return d


def default_accounts() -> list[Account]:
    return [
        Account("c1", "claude", "Claude #1", "~/.claude"),
        Account("c2", "claude", "Claude #2", "~/.claude-c2"),
        Account("c3", "claude", "Claude #3", "~/.claude-c3"),
        Account("x1", "codex", "Codex", "~/.codex"),
        Account("g1", "agy", "Antigravity"),
    ]


@dataclass
class Overview:
    alias: str = "c1"
    model: str = "haiku"  # alias: always the newest Haiku (claude-haiku-5-5)
    effort: str = "low"  # spoken answers: speed over deep reasoning (claude --effort; "" = the CLI default)


@dataclass
class Config:
    accounts: list[Account] = field(default_factory=default_accounts)
    overview: Overview = field(default_factory=Overview)
    poll_fallback_s: int = 15
    router_binary: str = "claude"
    auto_trust: bool = True  # pre-trust launch folders and auto-accept trust dialogs (see trust.py)

    def account(self, alias: str) -> Account | None:
        return next((a for a in self.accounts if a.alias == alias), None)

    def public(self) -> dict:
        return {"overview": {"alias": self.overview.alias, "model": self.overview.model, "effort": self.overview.effort},
                "poll_fallback_s": self.poll_fallback_s}

    @classmethod
    def from_dict(cls, raw: dict) -> "Config":
        raw = dict(raw or {})
        accounts = []
        for a in raw.get("accounts") or []:
            a = dict(a)
            if "alias" not in a:  # old format: `name` (default "default") instead of alias
                name = a.pop("name", "default")
                a["alias"] = DEFAULT_ALIASES.get(a.get("tool"), name) if name == "default" else name
            a.pop("name", None)
            accounts.append(Account(**{k: v for k, v in a.items() if k in Account.__dataclass_fields__}))
        ov = raw.get("overview") or {}
        if "router_model" in raw:  # old format
            ov = {"model": raw["router_model"], **ov}
        cfg = cls(accounts=accounts or default_accounts(),
                  overview=Overview(**{k: v for k, v in ov.items() if k in ("alias", "model", "effort")}))
        if raw.get("poll_fallback_s"):
            cfg.poll_fallback_s = max(2, int(raw["poll_fallback_s"]))
        cfg.router_binary = raw.get("router_binary", cfg.router_binary)
        cfg.auto_trust = bool(raw.get("auto_trust", True))
        return cfg  # unknown/legacy keys (monitor_interval_s, confirm_sends...) are ignored

    @classmethod
    def load(cls, path: Path | None = None) -> "Config":
        path = path or CONFIG_PATH
        if not path.exists():
            return cls()
        return cls.from_dict(yaml.safe_load(path.read_text()) or {})

    def save(self, path: Path | None = None) -> None:
        path = path or CONFIG_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "overview": {"alias": self.overview.alias, "model": self.overview.model, "effort": self.overview.effort},
            "poll_fallback_s": self.poll_fallback_s,
            "auto_trust": self.auto_trust,
            "accounts": [a.dump() for a in self.accounts],
        }
        if self.router_binary != "claude":
            data["router_binary"] = self.router_binary
        path.write_text(yaml.safe_dump(data, sort_keys=False))
