---
name: routing
description: How to choose the session for a request (account alias, tool, project).
---
Match in order: explicit nickname in memory -> project/directory named in the request -> session title keywords ->
most recently active idle session of the tool or account the user named. If still tied, ask one short question.
Mention the account alias when the user has more than one for the same tool. For new sessions pick the alias the
user names; otherwise ask or use the one from memory.
