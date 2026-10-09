"""`notifyd serve`: SSH forced-command entry. Pipes stdin/stdout to the daemon socket; nothing else is reachable."""
from __future__ import annotations

import asyncio
import sys

from .config import SOCKET_PATH


async def serve() -> int:
    try:
        reader, writer = await asyncio.open_unix_connection(str(SOCKET_PATH), limit=32 * 1024 * 1024)
    except OSError:
        sys.stderr.write("notifyd daemon is not running (systemctl --user start notifyd)\n")
        return 1
    loop = asyncio.get_running_loop()
    stdin = asyncio.StreamReader(limit=32 * 1024 * 1024)
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(stdin), sys.stdin)
    out = sys.stdout.buffer

    async def up():
        async for line in stdin:
            writer.write(line)
            await writer.drain()
        writer.close()

    async def down():
        async for line in reader:
            out.write(line)
            out.flush()

    done, pending = await asyncio.wait([asyncio.create_task(up()), asyncio.create_task(down())],
                                       return_when=asyncio.FIRST_COMPLETED)
    for p in pending:
        p.cancel()
    return 0
