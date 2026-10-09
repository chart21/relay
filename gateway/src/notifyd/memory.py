"""RAM per agent session and for the rest of the Desktop (the phone's RAM monitor), from /proc.

A session's memory is the PSS (shared pages split fairly) plus swap of its whole process tree: the tmux pane's shell,
the agent, and its MCP servers and tools. Everything else is grouped by program name (firefox, chromium, java ...).
"""
from __future__ import annotations

import os
from collections import defaultdict
from pathlib import Path

PROC = Path("/proc")


def meminfo(proc: Path = PROC) -> dict:
    vals = {}
    for line in (proc / "meminfo").read_text().splitlines():
        k, _, v = line.partition(":")
        vals[k] = int(v.split()[0]) // 1024 if v.split() else 0  # MB
    return {"total_mb": vals.get("MemTotal", 0), "available_mb": vals.get("MemAvailable", 0),
            "swap_total_mb": vals.get("SwapTotal", 0), "swap_used_mb": vals.get("SwapTotal", 0) - vals.get("SwapFree", 0)}


def processes(proc: Path = PROC) -> dict[int, tuple[int, str]]:
    """pid -> (ppid, program name)."""
    out = {}
    for d in proc.iterdir():
        if not d.name.isdigit():
            continue
        try:
            stat = (d / "stat").read_text()
        except OSError:
            continue
        name = stat[stat.find("(") + 1:stat.rfind(")")]
        fields = stat[stat.rfind(")") + 2:].split()
        out[int(d.name)] = (int(fields[1]), name)
    return out


def tree(root: int, procs: dict[int, tuple[int, str]]) -> list[int]:
    kids = defaultdict(list)
    for pid, (ppid, _) in procs.items():
        kids[ppid].append(pid)
    out, todo = [], [root]
    while todo:
        p = todo.pop()
        if p in procs and p not in out:
            out.append(p)
            todo += kids[p]
    return out


def usage_kb(pid: int, proc: Path = PROC) -> tuple[int, int]:
    """(PSS, swap) in kB; (RSS, swap) from status where smaps_rollup is not readable."""
    try:
        pss = swap = 0
        for line in (proc / str(pid) / "smaps_rollup").read_text().splitlines():
            if line.startswith("Pss:"):
                pss = int(line.split()[1])
            elif line.startswith("SwapPss:"):
                swap = int(line.split()[1])
        return pss, swap
    except (OSError, ValueError, IndexError):
        pass
    try:
        st = dict(line.split(":", 1) for line in (proc / str(pid) / "status").read_text().splitlines() if ":" in line)
        return int(st.get("VmRSS", "0 kB").split()[0]), int(st.get("VmSwap", "0 kB").split()[0])
    except (OSError, ValueError):
        return 0, 0


def snapshot(roots: dict[str, int], proc: Path = PROC, top: int = 8) -> dict:
    """roots: session key -> root pid (pane shell or agent). -> totals, per session {pids, mb, swap_mb}, top others."""
    procs = processes(proc)
    seen: set[int] = set()
    sessions = {}
    for key, root in roots.items():
        pids = [p for p in tree(root, procs) if p not in seen]
        seen.update(pids)
        pss = swap = 0
        for p in pids:
            a, b = usage_kb(p, proc)
            pss, swap = pss + a, swap + b
        sessions[key] = {"pids": len(pids), "mb": pss // 1024, "swap_mb": swap // 1024}
    groups: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])
    for pid, (_, name) in procs.items():
        if pid in seen or pid <= 2:
            continue
        a, b = usage_kb(pid, proc)
        g = groups[name]
        g[0], g[1], g[2] = g[0] + a, g[1] + b, g[2] + 1
    others = sorted(({"name": n, "mb": v[0] // 1024, "swap_mb": v[1] // 1024, "count": v[2]} for n, v in groups.items()),
                    key=lambda o: -(o["mb"] + o["swap_mb"]))
    return {**meminfo(proc), "sessions": sessions, "others": [o for o in others[:top] if o["mb"] + o["swap_mb"] > 0]}


def root_pid(session, panes: dict[str, dict]) -> int | None:
    """The tmux pane's shell when the session runs in tmux (its whole tree), else the agent's own pid."""
    p = panes.get(session.pane) if session.pane else None
    return int(p["pid"]) if p and p.get("pid") else session.pid


def alive(pid: int) -> bool:
    return os.path.exists(f"/proc/{pid}")
