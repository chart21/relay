#!/usr/bin/env bash
# Relay setup. Safe to re-run (every step is idempotent).
#
#   ./setup.sh --pc [--install-uv] [--voice] [--dry-run]    run ON the Desktop: gateway, service, accounts, hooks, tests
#   ./setup.sh --phone [--dry-run]                          run on the laptop: build APK, install, pair, register key
#
#   env: PC_HOST=desktop (ssh alias used by --phone)  RELAY_HOST=desktop.example  RELAY_PORT=22  RELAY_USER=$USER
#        UV=/path/to/uv  PHONE_SERIAL=<adb serial>
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GW="$REPO/gateway"
NOTIFYD="$GW/.venv/bin/notifyd"
ANDROID_DIR="$REPO/android"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
MODE="" DRY=0 INSTALL_UV=0 VOICE=0
for a in "$@"; do
  case "$a" in
    --pc) MODE=pc ;; --phone) MODE=phone ;; --dry-run) DRY=1 ;; --install-uv) INSTALL_UV=1 ;; --voice) VOICE=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done
[ -n "$MODE" ] || { sed -n '2,9p' "$0"; exit 2; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m+\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
run()  { if [ $DRY = 1 ]; then printf '  [dry-run] %s\n' "$*"; else "$@"; fi; }
TODO=()
todo() { TODO+=("$*"); }

find_uv() {
  [ -n "${UV:-}" ] && [ -x "$UV" ] && { echo "$UV"; return; }
  command -v uv 2>/dev/null && return
  for p in "$HOME/.config/local/bin/uv" "$HOME/.local/bin/uv"; do [ -x "$p" ] && { echo "$p"; return; }; done
  return 1
}

# ================================================================== --pc
if [ "$MODE" = pc ]; then
  step "1. uv"
  UVBIN="$(find_uv || true)"
  if [ -z "$UVBIN" ] && [ $INSTALL_UV = 1 ]; then
    run sh -c 'curl -LsSf https://astral.sh/uv/install.sh | sh'
    UVBIN="$(find_uv || true)"
  fi
  if [ -z "$UVBIN" ]; then
    if [ $DRY = 1 ]; then UVBIN=uv; warn "uv not found (would need --install-uv)"; else
      warn "uv not found: rerun with --install-uv (user-local, no sudo) or set UV=/path/to/uv"; exit 1; fi
  else ok "uv: $UVBIN"; fi
  export PATH="$(dirname "$UVBIN" 2>/dev/null):$HOME/.local/bin:$HOME/.config/local/bin:$PATH"

  step "2. Gateway (python deps)"
  if [ $VOICE = 1 ]; then run "$UVBIN" sync --project "$GW" --extra voice; else run "$UVBIN" sync --project "$GW"; fi
  if [ $DRY = 0 ]; then [ -x "$NOTIFYD" ] && ok "notifyd at $NOTIFYD" || { warn "uv sync failed"; exit 1; }; fi
  run chmod +x "$REPO/hooks/claude-notify-hook" "$REPO/hooks/codex-notify-hook" "$REPO/tools/fake_phone.py"

  step "3. systemd user service"
  UNIT="$HOME/.config/systemd/user/notifyd.service"
  SVC_PATH="$HOME/.local/bin:$HOME/.config/local/bin:/usr/local/bin:/usr/bin:/bin"
  if [ $DRY = 0 ]; then
    mkdir -p "$(dirname "$UNIT")"
    cat > "$UNIT" <<UNITEOF
[Unit]
Description=notifyd (phone relay for AI coding sessions)

[Service]
ExecStart=$NOTIFYD daemon
Environment=PATH=$SVC_PATH
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
UNITEOF
    systemctl --user daemon-reload && systemctl --user enable notifyd && systemctl --user restart notifyd && sleep 2
    systemctl --user is-active --quiet notifyd && ok "notifyd is running" || warn "notifyd failed: journalctl --user -u notifyd"
    if loginctl enable-linger "$USER" 2>/dev/null; then ok "linger enabled (service runs without login)"; else
      warn "could not enable linger"; todo "Run once: sudo loginctl enable-linger $USER"; fi
  else
    echo "  [dry-run] write $UNIT (PATH=$SVC_PATH); systemctl --user enable + restart notifyd; loginctl enable-linger"
  fi

  step "4. Accounts, aliases, hooks, tmux.conf"
  run "$NOTIFYD" setup accounts
  todo "Log in once per new Claude account: run its alias (e.g. c2, c3) in a terminal, then /login"
  todo "New shells pick up the aliases automatically; for this one: source ~/.config/notifyd/aliases.zsh"
  todo "Reload tmux settings: tmux source-file ~/.tmux.conf (or restart the tmux server)"

  step "5. Tests"
  if [ $DRY = 0 ]; then (cd "$GW" && "$UVBIN" run --quiet pytest -q 2>&1 | tail -2); else echo "  [dry-run] uv run pytest -q"; fi

# ================================================================== --phone
else
  PC_HOST="${PC_HOST:-desktop}"
  RELAY_HOST="${RELAY_HOST:-desktop.example}" RELAY_PORT="${RELAY_PORT:-22}" RELAY_USER="${RELAY_USER:-$USER}"
  step "1. Build the app"
  JDK="${JAVA_HOME:-}"
  for j in "$HOME"/Android/jdk-17* /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-21-openjdk; do
    [ -n "$JDK" ] && break; [ -x "$j/bin/javac" ] && JDK="$j"
  done
  [ -n "$JDK" ] || { warn "no JDK 17/21 (~/Android/jdk-17*, /usr/lib/jvm/java-{17,21}-openjdk)"; [ $DRY = 1 ] || exit 1; }
  [ $DRY = 1 ] || echo "sdk.dir=$SDK" > "$ANDROID_DIR/local.properties"
  APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
  if [ $DRY = 0 ]; then
    (cd "$ANDROID_DIR" && JAVA_HOME="$JDK" ./gradlew --no-daemon -q assembleDebug 2>&1 | grep -E '^e:|FAILED|wrong' | head)
    [ -f "$APK" ] && ok "built $APK" || { warn "APK build failed"; exit 1; }
  else
    echo "  [dry-run] JAVA_HOME=$JDK ./gradlew assembleDebug"
  fi

  step "2. Install on the phone and provision"
  ADB="${ADB:-$(command -v adb || echo "$SDK/platform-tools/adb")}"
  SERIAL="${PHONE_SERIAL:-$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')}"
  if [ $DRY = 1 ]; then
    echo "  [dry-run] adb install -r $APK; grant RECORD_AUDIO/POST_NOTIFICATIONS; am start --es host $RELAY_HOST --ei port $RELAY_PORT --es user $RELAY_USER"
    echo "  [dry-run] read pubkey via run-as; ssh $PC_HOST <abs notifyd> setup key \"<pub>\""
  elif [ -z "$SERIAL" ]; then
    warn "no phone connected over adb"; todo "Connect the phone (USB debugging), then re-run ./setup.sh --phone"
  else
    A() { "$ADB" -s "$SERIAL" "$@"; }
    installed=0
    for try in 1 2 3; do
      out="$(A install -r "$APK" 2>&1)"
      if echo "$out" | grep -q Success; then installed=1; break; fi
      warn "install failed: $(echo "$out" | tail -1)"
      echo "     Accept the install prompt / enable 'Install via USB' on the phone, then press Enter to retry."
      read -r _ || break
    done
    if [ $installed = 1 ]; then
      ok "app installed"
      for p in RECORD_AUDIO POST_NOTIFICATIONS; do A shell pm grant dev.relay.app android.permission.$p 2>/dev/null; done
      A shell am start -n dev.relay.app/.MainActivity --es host "$RELAY_HOST" --ei port "$RELAY_PORT" --es user "$RELAY_USER" >/dev/null
      PUB=""
      for i in 1 2 3 4 5 6 7 8 9 10; do
        PUB="$(A shell run-as dev.relay.app cat files/id_ed25519.pub 2>/dev/null | tr -d '\r')"
        [ -n "$PUB" ] && break; sleep 1
      done
      if ip -4 addr 2>/dev/null | grep -q "inet $RELAY_HOST/"; then  # running on the Desktop itself
        "$NOTIFYD" setup key "$PUB" && ok "phone key registered (restricted to notifyd ssh-entry)"; PUB_DONE=1
      fi
      REMOTE_NOTIFYD="${REMOTE_NOTIFYD:-$(ssh -o BatchMode=yes "$PC_HOST" 'echo "$HOME/relay/gateway/.venv/bin/notifyd"' 2>/dev/null)}"
      if [ "${PUB_DONE:-0}" = 1 ]; then :
      elif [ -z "$PUB" ]; then
        warn "could not read the phone's key"
        todo "In the app copy the public key, then: ssh $PC_HOST <abs notifyd> setup key \"<key>\""
      elif [ -z "$REMOTE_NOTIFYD" ]; then
        warn "cannot reach $PC_HOST over ssh"; todo "On the Desktop: <abs>/notifyd setup key \"$PUB\""
      else
        ssh -o BatchMode=yes "$PC_HOST" "$REMOTE_NOTIFYD setup key '$PUB'" && ok "phone key registered on $PC_HOST (restricted to notifyd ssh-entry)"
      fi
    else
      todo "Install the APK yourself: $APK (adb install -r), then re-run ./setup.sh --phone"
    fi
  fi
fi

step "Summary"
if [ ${#TODO[@]} -eq 0 ]; then ok "nothing left for you to do"; else
  echo "  Remaining manual steps:"; for t in "${TODO[@]}"; do echo "   - $t"; done
fi
