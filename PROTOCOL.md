# Relay protocol (v2)

Contract between the `notifyd` gateway (Desktop) and the Relay Android app. Both sides implement exactly this.

## Transport
- SSH to the Desktop (port `22` by default; host and user are set in the app) with the phone's Ed25519 key.
- `authorized_keys` line, written by `notifyd setup key`:
  `restrict,pty,command="<abs>/notifyd ssh-entry" ssh-ed25519 AAAA… relay-phone`
- `notifyd ssh-entry` reads `$SSH_ORIGINAL_COMMAND`. Only three commands exist; anything else prints an error to stderr and exits 1.
  - `serve`: control channel, JSON lines both ways (UTF-8, one object per `\n`, max 32 MiB per line). No PTY.
  - `attach <target>`: interactive terminal; **the client must request a PTY** (`xterm-256color`, current cols/rows).
    - `<target>` is a session key, or `tmux:<tmux_session_name>`.
    - The server validates that the target is an agent tmux session, i.e. one that has the tmux option `@agent_alias`.
    - It then creates a grouped session `phone-<6hex>` (`destroy-unattached on`, `mouse on`) with the agent's window selected, and `exec`s `tmux attach -t phone-<6hex>`. The app turns swipes into SGR mouse-wheel reports, so tmux scrolls the pane's history.
    - Resizing uses the normal SSH window-change. The channel closes when the client detaches or the agent's tmux session ends.
  - `apk`: the published app build (`notifyd publish-apk`), gzip-compressed, on stdout (self-update; no PTY).
- One SSH connection may carry the control channel plus any number of `attach` channels.

## Energy and delivery rules
- Only the phone opens connections. The Desktop never dials out, retries or pushes anything when no client is connected.
- Events are appended to a persistent log with a monotonically increasing `seq`. A reconnecting client sends `hello` with its last `seq` and receives everything it missed.
- While no `serve` client is connected, the gateway's fallback poll slows to 120 s and transcript watches are dropped. On `hello` it runs one immediate state check.
- The client must not retry while the phone has no network (wait for a ConnectivityManager callback). On replay it shows **one digest notification**, not one per event.

## Session key and session object
- Key: `<tool>:<alias>:<id>`, e.g. `claude:c2:4f1c…`.
  - `tool` is `claude`, `codex` or `agy`.
  - `alias` is the account alias from the config (`c1`, `x1`, `g1`, …).
  - `id` is the tool's session or conversation id.
- Session object (field order irrelevant):
```json
{
  "key": "claude:c2:4f1c…", "tool": "claude", "alias": "c2", "id": "4f1c…",
  "label": "Claude #2",              // account label from config
  "cwd": "/home/dev/workspace/webapp", "title": "fix bench script",
  "state": "busy",                   // busy | idle | needs_input | exited
  "alive": true,                     // a process currently holds the session
  "attachable": true,                // runs inside an agent tmux session -> `attach` works
  "tmux_session": "c2-webapp-3fa1",   // null when not in an agent tmux session
  "last_activity": 1759676400.5,     // unix seconds
  "last_message": "Done. The bench now …", // last assistant text, markdown/code stripped, <= 300 chars
  "duplicates": 1,
  "remote_url": "https://claude.ai/code/…" // optional: live Claude session with Remote Control on
}
```

**Placeholder sessions.**
- An agent tmux session (one carrying `@agent_alias`) that no tool has registered yet is still listed. This happens for an agent sitting at its folder-trust prompt, codex before its first message, and agy versions without a readable store.
- Placeholders have:
  - `id` = `tmux-<tmux_session>`;
  - a `title` ending in `(starting)`;
  - `state` `idle`, `attachable` true;
  - the tmux screen as their transcript.
- Once the tool registers the session, the placeholder ends with `session_ended` and `by_user=true` (so there is no alert), and the real session arrives with `session_started`.

## Control channel (`serve`)
### Handshake
- Client → `{"type":"hello","since_seq":123,"client":"relay-android","version":2}`
- Server → every event with `seq > since_seq` (each carries `"replay": true`), then
  `{"type":"hello_ok","seq":<latest seq>,"server_version":2,"sessions":[<live sessions>],"accounts":[<account>],"config":{…},"app":<build>|null}`.
  `app` is the published app build: `{"version_code","version_name","size","gz_size","sha256","published_at"}`; the app
  offers an update when `version_code` is higher than its own, downloads it with `apk` and checks `size` and `sha256`.
- After `hello_ok` the server streams new events as they happen (`"replay"` absent).

### Events (server → client)
- Persisted events have `seq` and `ts` (unix seconds, float). Ephemeral ones have neither.
- Shape: `{"type":"<name>","seq":N,"ts":T, …payload}`.

| type | payload | persisted |
|---|---|---|
| `session_started` | `session` | yes |
| `session_state` | `session`, `prev_state`, `reason` (`hook:Stop`, `hook:Notification`, `notify:codex`, `poll`, …), `message` (needs_input text, optional) | yes |
| `session_ended` | `session_key`, `session` (last known), `by_user` (bool, killed via RPC) | yes |
| `needs_confirmation` | `action_id`, `description` | yes |
| `confirmation_resolved` | `action_id`, `result` | yes |
| `monitor_error` | `error` | yes |
| `transcript_append` | `session_key`, `messages` (see `transcript`) | **no** (only to clients that `watch` the session) |

**Notification semantics (client):**
- `session_state` busy→idle means "ready"; →`needs_input` means "needs input".
- `session_ended` with `by_user=false` and a previously alive session means "ended".
- Replayed events produce one digest.

### RPC
- Request: `{"type":"rpc","id":"<client id>","method":"<name>","params":{…}}`
- Reply: `{"type":"rpc_result","id":"<same id>","ok":true,"result":{…}}` or `{"type":"rpc_result","id":"…","ok":false,"error":"text"}`.
- Replies may arrive out of order; long calls (`ask`, `transcribe`) do not block other calls.
- Progress (only `ask` with `stream:true`): `{"type":"rpc_progress","id":"<same id>","delta":"text"}`
  lines before the `rpc_result`, carrying the reply text as it is written (also what the agent says before a tool
  call, e.g. "Moment, ich trage das ein."; a new text block starts with `"\n"`). Not logged, not replayed.

| method | params | result |
|---|---|---|
| `ping` | – | `{}` |
| `list_sessions` | `include_dormant=false`, `limit=100`, `include_background=false` (also headless `claude -p` runs, scratch-folder runs and sessions without a message; each such session carries `"background": true`) | `{"sessions":[…]}`, newest first |
| `accounts` | – | `{"accounts":[{"alias","tool","label","home","logged_in"}]}` |
| `list_dirs` | `path="~"`, `show_hidden=false` | `{"path":abs,"parent":abs\|null,"dirs":[{"name","path","is_git"}]}`, sorted by name |
| `recent_dirs` | `limit=30` | `{"dirs":[{"path","last_used","tools":[…]}]}`, from transcripts/history |
| `mkdir` | `path` | `{"path":abs}` |
| `launch` | `alias`, `cwd`, `prompt` (optional), `name` (optional) | `{"tmux_session":"c2-webapp-3fa1","target":"tmux:c2-webapp-3fa1"}`; the session object follows via `session_started` once the tool registers it |
| `transcript` | `key`, `before` (opaque cursor, optional), `limit=50`, `from` (a message index from `transcript_search`: everything from there up to `before`, max 2000, instead of `limit`) | `{"messages":[{"id","role","text","tools":[{"name","summary"}],"ts"}],"before":cursor\|null}` |
| `transcript_search` | `key`, `query` (>= 2 chars), `roles` (optional, e.g. `user,assistant`), `limit=100` | `{"matches":[{"id","index","role","ts","snippet"}],"total_messages":n}`, newest first, case-insensitive over the whole transcript; `index` works with `transcript`'s `from` |
| `screen` | `key` or `target`, `lines=2000`, `escapes=true` | `{"text":"…"}` (tmux capture-pane incl. history, with ANSI escapes when `escapes`) |
| `send` | `key` or `target` (`tmux:<name>`), `text` (optional), `keys` (optional list of tmux key names: `Escape`, `C-c`, `Enter`, `BTab`, `Up`, …), `enter=true`, `force=false` | `{"how":"tmux %12"}` |
| `kill` | `key` | `{}` (kills the agent's tmux session; only for attachable sessions) |
| `watch` / `unwatch` | `key` | `{}` |
| `ask` | `text`, `stream=false`, `source` (`text`\|`voice`, for the timing log) | `{"text":"<overview agent reply>","timing":{"first_text_ms","tools":[[name,ms]],"total_ms","turns",…}}` |
| `transcribe` | `audio_b64` (16 kHz mono PCM16 WAV) | `{"text":"…"}` |
| `speak_summary` | `key`, `name` (spoken session name), `lang` (`auto`\|`de`\|`en`) | `{"text":"1-3 spoken sentences","how":"pool"\|"main"\|"brief"\|"cache"}`; on demand only (the phone asks right before it reads a finished session aloud on a headset or in conversation mode): spare account first, c1 if none has quota, else the first sentences (Haiku 5.5, low effort) |
| `memory` | – | `{"total_mb","available_mb","swap_total_mb","swap_used_mb","sessions":[{"key","title","alias","tool","cwd","state","closable","mb","swap_mb","pids"}],"others":[{"name","mb","swap_mb","count"}],"closed":[{"key","title","alias","tool","id","cwd","closed_at","mb"}]}`: the RAM monitor; per session the PSS + swap of its whole process tree, biggest first (~1.5 s, poll at most every 10 s) |
| `close` | `key`, `mb` (optional, shown later) | `{"closed":{…}}`: stops a live session to free its memory (its tmux session incl. older copies, or SIGTERM); no "ended" alert; listed under `closed` |
| `reopen` | `key` | `{"tmux_session","target"}`: resumes a finished Claude (or Codex) session interactively in a new tmux session (`--resume <id>` in its folder) |
| `upload_begin` / `upload_chunk` / `upload_end` | begin: `name`, `size`; chunk: `upload_id`, `seq` (0, 1, … in order), `data_b64` (≤ 512 KiB raw each); end: `upload_id` | begin `{"upload_id"}`, chunk `{"received"}`, end `{"path","size"}`: a file shared from the phone, stored under `~/.local/share/notifyd/uploads/<date>/` (max 50 MB, kept 30 days); the app then sends the path into the session |
| `app_log` | `entries`: [{`ts`, `level`, `tag`, `message`, `stack`, `app_version`}] (≤ 200) | `{"stored"}`; the app's errors and crashes, `notifyd phone log [--stack]` |
| `voice_vocab` | – | `{"terms":[…],"corrections":[[heard, meant],…]}`: speech-recognition vocabulary from `router/voice-vocab.txt` plus live session titles and account aliases (the phone biases recognition with the terms and fixes known mishearings) |
| `voice_vocab_add` | `meant`, `heard` (optional) | `{"added": line}`: appends a term, or the correction "heard => meant", to `router/voice-vocab.txt` |
| `stt_warm` | – | `{"loaded":bool}`; starts loading Whisper in the background (the phone calls it when it starts recording for PC transcription) |
| `voice_timing` | `trigger` (`wake`\|`mic`\|`button`\|`follow_up`), `stt` (`device`\|`pc`), `stages` {name: ms since the trigger} | `{}`; appended to the state dir's `voice-timing.jsonl` (`notifyd timing`) |
| `confirm` | `action_id`, `ok` | `{"result":"…"}` |
| `usage` | `refresh=false`, `renew` (alias, optional) | `{"accounts":[{"alias","tool","label","plan","windows":[{"name","pct","resets_at"}],"error","checked_at"}]}`; Claude via the optional pool helper, Codex from its logs, cached 120 s; `renew` refreshes an expired Claude login with a tiny Haiku call |
| `app_update` | – | `{"app":<build>\|null}` (same as in `hello_ok`); the app re-reads it right before downloading |
| `app_published` | – | `{"clients":n}`; from `notifyd publish-apk`: sends `{"type":"app_update","app":<build>}` (not logged, not replayed) to every connected phone |
| `get_config` | – | `{"overview":{"alias","model","effort"},"poll_fallback_s":15}` |
| `set_config` | `overview_alias`, `overview_model`, `overview_effort` (`low` default, `medium`, `high`, `xhigh`, `max`, `""` = CLI default; each optional) | same as `get_config` |

**Message roles in `transcript`:**
- `role` is `user`, `assistant`, `tool` or `system`.
- `text` is plain markdown, truncated to 20 000 characters.
- `tools` summarises the tool calls made in that assistant message, e.g. `{"name":"Bash","summary":"pytest -q"}`.
- `messages` are oldest-first within the page; `before` pages further back.

**`send` rules:**
- It refuses a busy session (unless `force`) and an ambiguous one (`duplicates>1`, unless `force`).
- Text goes in via tmux bracketed paste. `enter` adds an Enter afterwards. A pane the phone scrolled back (tmux copy
  mode) leaves copy mode first, so the input reaches the agent. With `target`, busy/duplicate checks don't apply.
- A dormant claude session is reopened interactively: `claude --resume <id> <text>` in a new agent tmux session (bypass
  permissions, Remote Control from the account's settings), in the recorded folder or, when that doesn't exist here,
  the folder of the transcript's project. The result `how` is `resumed in tmux <name>`. Dormant codex and agy
  sessions still resume headless.

## Accounts / aliases (Desktop config `~/.config/notifyd/config.yaml`)
```yaml
overview: {alias: c1, model: haiku}
poll_fallback_s: 15
accounts:
  - {alias: c1, tool: claude, label: "Claude #1", home: ~/.claude}
  - {alias: c2, tool: claude, label: "Claude #2", home: ~/.claude-c2}
  - {alias: c3, tool: claude, label: "Claude #3", home: ~/.claude-c3}
  - {alias: x1, tool: codex,  label: "Codex",     home: ~/.codex}
  - {alias: g1, tool: agy,    label: "Antigravity"}
```
`agent-run <alias> [args…]` (the shell aliases call it):
- launches the tool in a dedicated tmux session `<alias>-<basename(cwd)>-<4hex>`, tagged `@agent_alias` and `@agent_tool`;
- sets the env: `CLAUDE_CONFIG_DIR=<home>` when the home is not `~/.claude`, `CODEX_HOME=<home>`;
- adds the skip-permission flags: claude `--dangerously-skip-permissions`, codex `--dangerously-bypass-approvals-and-sandbox`, agy `--dangerously-skip-permissions`.

## Phone bridge
The phone forwards what it already receives (notifications, SMS, Health Connect, a location fix on request) and
executes approved replies. Everything is opt-in per Android permission. The phone still opens every connection:
items wait in a queue on the phone, requests wait in the event log on the Desktop. Design decisions: approval only for sending; no bank logins; no unofficial WhatsApp bridge.

### Phone → Desktop: `inbox_put`
`rpc inbox_put {"kind": K, "items": [...]}` (at most 500 items) → `{"stored": n, "duplicates": m}`. The gateway
appends each new item as one JSON line to `$NOTIFYD_INBOX/<K>/<YYYY-MM-DD>.jsonl` (default `~/.local/share/notifyd/inbox`) (date of the
item's `ts` or `start` in the Desktop's local time) and adds `"received_at"`. Items are deduplicated by `id` (30 days),
so the phone may resend after a lost reply. All times are unix seconds (float).

| K | item fields |
|---|---|
| `notification` | `id` (`<package>\|<key>\|<postTime ms>`), `ts`, `app` (package), `app_label`, `title`, `text`, `big_text`?, `sub_text`?, `conversation`? (chat or group name), `messages`? (`[{"sender","text","ts"}]` from MessagingStyle, oldest first), `category`?, `has_reply` (bool) |
| `sms` | `id` (`sms-<provider _id>`), `ts`, `address`, `name`? (contact), `body`, `box` (`inbox`\|`sent`) |
| `health` | `id` (`<type>-<start ms>-<end ms>`; nutrition: `nutrition-<YYYY-MM-DD>-<hash of the totals>`), `type` (`steps`\|`sleep`\|`heart_rate`\|`weight`\|`nutrition`), `start`, `end`, `value`, `unit` (`count`\|`min`\|`bpm`\|`kg`\|`kcal`), `min`?/`max`?/`samples`? (heart rate per record), `stages`? (sleep: `[{"stage","start","end"}]`), `day`/`entries`/`nutrients`? (nutrition: one item per local day, `nutrients` = summed amounts keyed like `protein_g`, `zinc_mg`, `vitamin_d_mcg`; only what the writing app recorded), `source`? (writing app(s), comma-separated for nutrition) |
| `location` | `id` (`loc-<request_id>`), `ts`, `lat`, `lon`, `accuracy_m`, `provider`, `request_id` |

Notifications: only apps on the phone's allowlist (default: WhatsApp, Threema, Signal, Telegram, PayPal, Revolut, Amazon, DHL,
Google Calendar, Gmail and apps with "bank" in their name); full text kept (bank pushes are the
login-free source of live transactions); group summaries and ongoing/silent system notifications are skipped. SMS
and Health Connect are read incrementally (cursor on the phone) after each `hello_ok` and at most every 30 min while
connected. Nutrition is the exception: the last 7 days are re-totalled on every read (late entries and
corrections), empty days included, so one day can arrive several times as it changes; consumers take the newest
`received_at` per (`type`, `day`).

### Desktop → phone: send requests (approval)
Persisted event `send_request`: `{"request_id", "kind", "executor": "phone"|"desktop", "app"?, "conversation"?,
"text", "summary", "requested_by", "expires_at"}`.
- `kind` `message` (`executor: phone`): the phone shows the exact text and recipient; on approval it answers the
  newest notification of (`app`, `conversation`) through its own reply action (RemoteInput). No reply action (e.g.
  the notification is gone after a phone restart) → failure "no reply action; open the chat on the phone".
- other kinds (`mail`, `invite`, `post`, `order`, `payment`; `executor: desktop`): the phone only approves; the
  requesting agent sends after `approved`.
- The phone answers `rpc send_result {"request_id", "ok", "error"?}` (`ok` = sent for `phone`, approved for
  `desktop`; denial is `ok: false, error: "denied"`). The gateway then publishes persisted `send_resolved`
  `{"request_id", "ok", "error"?}`, so a second phone or the app UI drops the request. Expired requests are ignored.

### Desktop → phone: location
Persisted event `location_request {"request_id", "reason", "expires_at"}` (10 min). The phone takes one fix
(at most 60 s, never continuous), stores it with `inbox_put` (kind `location`) and answers `rpc location_result`
`{"request_id", "ok", "lat"?, "lon"?, "accuracy_m"?, "ts"?, "error"?}`.

### Desktop → phone: notices
Persisted event `notice {"title", "text", "source", "tag"?}`: a plain message for the user (e.g. "backup job needs a new
login"); no approval, since nothing goes to anyone else. The phone shows it as a notification (channel "Messages from
the Desktop"); notices with the same `tag` replace each other. Replayed notices older than 24 h are not shown.
Agents and jobs: `notifyd phone notify --title "..." [--text "..."] [--source "..."] [--tag "..."]` (RPC `notice`).

### Desktop API for agents
`notifyd phone send-message --app <whatsapp|threema|signal|telegram|package> --to "<conversation>" --text "..."`,
`notifyd phone approve --kind <mail|invite|post|order|payment> --summary "..." [--text "..."]`,
`notifyd phone location [--reason "..."]`, `notifyd phone status <request_id>`: JSON on stdout, wait for the
outcome (`--wait` seconds, default 600; 120 for location) and report `pending` if the phone is offline; the request
stays queued until it expires (24 h for sending). The same requests exist as RPC `phone_request`
`{"kind": "message"|"mail"|...|"location", ..., "wait_s"}` and as tools of the voice assistant.
