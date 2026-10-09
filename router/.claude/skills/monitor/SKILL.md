---
name: monitor
description: Report on running sessions. Use for "what's running", "is X done", "was machen meine Sitzungen".
---
- `list_sessions` gives the state per session: busy = working, idle = ready for input, needs_input = waiting for the user
  (permission or question), exited = no process. `last_message` is what the agent said last.
- "Which are waiting for me?" -> needs_input first, then idle, newest first.
- For "what is X doing" use `session_digest`; if it is stuck or waiting, `session_screen` shows the terminal.
- The phone is notified automatically when a session finishes or needs input; there is no interval to configure.
