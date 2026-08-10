#!/data/data/com.termux/files/usr/bin/bash
#
# nisos control panel -- the thing you actually tap.
#
# Paired with Termux:Widget this behaves like an app: one icon on the home
# screen opens a live status board, and everything from there is a single
# keypress. Nobody should have to type `python -m nisos --listen` on a phone
# keyboard.
#
#   bash scripts/menu.sh
#
# Extending
# ---------
# Add an entry by writing an `act_<something>` function and adding one line to
# the menu block in `main`. Keep destructive options below the separator and
# make them confirm.
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
STATE_DIR="$HOME/.nisos"
MODELS="$NISOS_HOME/models"
PORT="${NISOS_PORT:-8080}"
LOG="$STATE_DIR/nisos.log"

mkdir -p "$STATE_DIR"
cd "$NISOS_HOME" 2>/dev/null || { echo "nisos not found at $NISOS_HOME"; exit 1; }

# --------------------------------------------------------------------------
# Presentation
# --------------------------------------------------------------------------
if [ -t 1 ]; then
  B=$'\033[1m'; D=$'\033[2m'; R=$'\033[0m'
  GRN=$'\033[1;32m'; YEL=$'\033[1;33m'; RED=$'\033[1;31m'; CYN=$'\033[1;36m'
else
  B=""; D=""; R=""; GRN=""; YEL=""; RED=""; CYN=""
fi

DOT_OK="${GRN}●${R}"; DOT_NO="${RED}●${R}"; DOT_MEH="${YEL}●${R}"

# --------------------------------------------------------------------------
# Status probes. Each is cheap enough to run on every redraw.
# --------------------------------------------------------------------------

model_running() {
  # True if llama-server answers on the configured port.
  curl -sf --max-time 1 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1
}

model_file() {
  # Path of the first real GGUF in models/, or empty.
  find "$MODELS" -name '*.gguf' -size +100M 2>/dev/null | head -1
}

key_present() {
  # True if there is an Anthropic key to use. Checks the default location only
  # -- if you moved brain.cloud.key_file, this line of the board will be wrong
  # while the assistant itself is right. `python -m nisos --check` is the
  # authority.
  [ -n "${ANTHROPIC_API_KEY:-}" ] || [ -s "$STATE_DIR/anthropic-key" ]
}

configured_backend() {
  # The literal brain.backend from config.toml, or empty for "not set".
  # `backend` appears in exactly one table, so a plain sed is enough -- same
  # trade-off as app_mode_state below, and for the same reason.
  sed -n 's/^[[:space:]]*backend[[:space:]]*=[[:space:]]*"\([a-z]*\)".*/\1/p' \
    "${NISOS_CONFIG:-$HOME/.nisos/config.toml}" 2>/dev/null | head -1
}

brain_state() {
  # "claude" or "llama" -- the same rule brain.backend_for() applies, done in
  # shell because this runs on every redraw and starting Python to draw a
  # status line is felt on a phone.
  local pinned; pinned="$(configured_backend)"
  case "$pinned" in
    claude|llama) printf '%s' "$pinned"; return ;;
  esac
  key_present && printf 'claude' || printf 'llama'
}

app_mode_state() {
  # "on" when the UI server is configured to outlive the page. Grep rather
  # than shelling out to app-mode.sh: this runs on every redraw.
  grep -qE '^[[:space:]]*quit_on_exit[[:space:]]*=[[:space:]]*false' \
    "${NISOS_CONFIG:-$HOME/.nisos/config.toml}" 2>/dev/null && echo "on" || echo "off"
}

notification_state() {
  # "on" / "off" for the menu line. Checks the reboot hook directly rather
  # than shelling out to notification.sh -- this runs on every redraw, and
  # spawning a shell per keypress is felt on a phone. Keep the path in step
  # with BOOT_SCRIPT there.
  [ -f "$HOME/.termux/boot/20-nisos-notification.sh" ] && echo "on" || echo "off"
}

human_size() {
  # Render a byte count as GB/MB, for the status board.
  local bytes="${1:-0}"
  if [ "$bytes" -gt 1073741824 ]; then
    awk -v b="$bytes" 'BEGIN{printf "%.1f GB", b/1073741824}'
  else
    awk -v b="$bytes" 'BEGIN{printf "%.0f MB", b/1048576}'
  fi
}

have() { command -v "$1" >/dev/null 2>&1; }

# --------------------------------------------------------------------------
# The status board
# --------------------------------------------------------------------------

draw_header() {
  # Print the banner and a live readout of every moving part.
  clear
  local mode; [ "$(brain_state)" = "claude" ] && mode="online" || mode="offline"
  printf '\n  %s%s nisos %s  %s%s · ελληνικά + english%s\n' \
    "$CYN" "$B" "$R" "$D" "$mode" "$R"
  printf '  %s─────────────────────────────────────────%s\n\n' "$D" "$R"

  # Brain -- who answers a phrase the router misses. The line above is a mode,
  # this is the reason for it.
  if [ "$mode" = "online" ]; then
    if key_present; then
      printf '   %s  brain      %sonline%s        %sClaude API%s\n' \
        "$DOT_OK" "$GRN" "$R" "$D" "$R"
    else
      printf '   %s  brain      %sonline, no key%s %s— press k%s\n' \
        "$DOT_NO" "$RED" "$R" "$D" "$R"
    fi
  else
    printf '   %s  brain      %son the phone%s   %sllama-server%s\n' \
      "$DOT_OK" "$GRN" "$R" "$D" "$R"
  fi

  # Model
  local mf; mf="$(model_file)"
  if model_running; then
    printf '   %s  model      %sready%s   %s%s%s\n' "$DOT_OK" "$GRN" "$R" "$D" "$(basename "${mf:-unknown}")" "$R"
  elif [ -n "$mf" ]; then
    printf '   %s  model      %sstopped%s %s%s%s\n' "$DOT_MEH" "$YEL" "$R" "$D" "$(basename "$mf")" "$R"
  else
    printf '   %s  model      %snot downloaded%s\n' "$DOT_NO" "$RED" "$R"
  fi

  # Recognisers
  local ears=""
  have termux-speech-to-text && ears="android"
  [ -x "$NISOS_HOME/bin/whisper-cli" ] && ears="${ears:+$ears + }whisper"
  if [ -n "$ears" ]; then
    printf '   %s  ears       %s\n' "$DOT_OK" "$ears"
  else
    printf '   %s  ears       %snothing installed%s\n' "$DOT_NO" "$RED" "$R"
  fi

  # Voice
  if have termux-tts-speak; then
    printf '   %s  voice      el-GR + en-GB\n' "$DOT_OK"
  else
    printf '   %s  voice      %stermux-api missing%s\n' "$DOT_NO" "$RED" "$R"
  fi

  # Disk
  local used; used=$(du -sk "$NISOS_HOME" 2>/dev/null | cut -f1)
  local free; free=$(df -P "$HOME" 2>/dev/null | awk 'NR==2{printf "%.1f", $4/1048576}')
  printf '   %s  disk       %s used  %s· %s GB free%s\n' \
    "$DOT_OK" "$(human_size $(( ${used:-0} * 1024 )))" "$D" "${free:-?}" "$R"

  # Update banner. Reads a cached answer only -- never hits the network here,
  # so opening the panel is instant and works with the radios off.
  local avail; avail="$(bash "$NISOS_HOME/scripts/update.sh" status 2>/dev/null)"
  if [ -n "$avail" ]; then
    printf '\n   %s▲  nisos %s is available%s  %s— press u to install%s\n' \
      "$YEL" "$avail" "$R" "$D" "$R"
  fi

  printf '\n'
}

# --------------------------------------------------------------------------
# Actions
# --------------------------------------------------------------------------

pause() { printf '\n  %sEnter to go back%s ' "$D" "$R"; read -r _ </dev/tty; }

act_speak() {
  # Record one command, act on it, speak the reply.
  printf '\n  %sSpeak now...%s\n\n' "$B" "$R"
  python -m nisos
  pause
}

act_listen() {
  # Stay resident, taking a command each time you press Enter.
  printf '\n  %sEnter to speak. Ctrl-C to stop.%s\n\n' "$B" "$R"
  python -m nisos --listen
  pause
}

act_type() {
  # Type a command instead of speaking it. Good for testing in a quiet room.
  printf '\n  %sType a command (Greek or English):%s\n  > ' "$B" "$R"
  read -r phrase </dev/tty
  [ -z "$phrase" ] && return
  printf '\n'
  python -m nisos --text "$phrase"
  pause
}

act_start() {
  # Start or restart llama-server, waiting until it actually answers.
  local mf; mf="$(model_file)"
  if [ -z "$mf" ]; then
    printf '\n  %sNo model found.%s Use "Install or repair" to fetch one.\n' "$RED" "$R"
    pause; return
  fi
  pkill -f 'llama-server' 2>/dev/null && sleep 1
  termux-wake-lock 2>/dev/null
  nohup "$NISOS_HOME/bin/llama-server" -m "$mf" \
    --port "$PORT" -t 6 -c 4096 >> "$STATE_DIR/llama.log" 2>&1 &
  printf '\n  loading %s' "$(basename "$mf")"
  for _ in $(seq 1 40); do
    model_running && break
    printf '.'; sleep 1
  done
  printf '\n'
  model_running && printf '  %sready%s\n' "$GRN" "$R" \
                || printf '  %sslow to start -- see %s/llama.log%s\n' "$YEL" "$STATE_DIR" "$R"
  pause
}

act_stop() {
  # Stop the server and release the wake lock, to save battery.
  pkill -f 'llama-server' 2>/dev/null \
    && printf '\n  stopped\n' || printf '\n  was not running\n'
  termux-wake-unlock 2>/dev/null
  pause
}

act_check() {
  # Run the built-in diagnostics -- what is present, what is missing.
  printf '\n'
  python -m nisos --check
  pause
}

act_app_mode() {
  # Whether the server survives closing the page. On = the home-screen icon
  # works instantly; off = you launch it from here each time.
  printf '\n'
  if [ "$(app_mode_state)" = "on" ]; then
    bash "$NISOS_HOME/scripts/app-mode.sh" off
  else
    bash "$NISOS_HOME/scripts/app-mode.sh" on
  fi
  pause
}

act_ui() {
  # Open the web UI. Runs in the foreground on purpose: nisos-ui.sh stays
  # attached so closing this session takes the UI -- and the model -- with it.
  printf '\n'
  bash "$NISOS_HOME/scripts/nisos-ui.sh"
  pause
}

act_notification() {
  # Toggle the permanent notification -- the one trigger that works from the
  # lock screen, because the shade is system UI and a volume key is not.
  printf '\n'
  if [ "$(bash "$NISOS_HOME/scripts/notification.sh" status)" = "on" ]; then
    bash "$NISOS_HOME/scripts/notification.sh" off
  else
    bash "$NISOS_HOME/scripts/notification.sh" on
  fi
  pause
}

act_log() {
  # Show the tail of the log. Where you look when something misbehaves.
  printf '\n'
  [ -f "$LOG" ] && tail -n 40 "$LOG" || printf '  %sno log yet%s\n' "$D" "$R"
  pause
}

act_actions() {
  # List every command it understands.
  printf '\n'
  python -m nisos --actions
  pause
}

brain_label() {
  # How the menu line describes the current brain.
  [ "$(brain_state)" = "claude" ] && printf 'online' || printf 'on the phone'
}

_store_key() {
  # Read a key with the echo off and hand it to Python on **stdin**. Never as
  # an argument: arguments are visible in `ps` and land in the history file,
  # and Python is where the 0600 chmod and the check live.
  printf '  Paste the key (hidden), then Enter:\n  > '
  local key; read -rs key </dev/tty; printf '\n\n'
  if [ -z "$key" ]; then
    printf '  nothing pasted -- left alone\n'
    return
  fi
  printf '%s\n' "$key" | python -m nisos --set-key
}

act_key() {
  # Set, replace or delete the Anthropic API key -- the one thing standing
  # between a fresh install and the online brain.
  printf '\n'
  local pinned; pinned="$(configured_backend)"

  if key_present; then
    if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
      printf '  A key is set in ANTHROPIC_API_KEY (it wins over the file).\n\n'
    else
      printf '  A key is stored in %s.\n\n' "$STATE_DIR/anthropic-key"
    fi
    printf '  %sr%s replace   %sd%s delete   Enter to leave it alone: ' \
      "$B" "$R" "$B" "$R"
    read -r what </dev/tty
    printf '\n'
    case "$what" in
      d|D) python -m nisos --forget-key ;;
      r|R) _store_key ;;
      *)   printf '  left alone\n' ;;
    esac
  else
    printf '  %sNo key stored.%s With one, phrases the router misses go to the\n' \
      "$B" "$R"
    printf '  Claude API instead of the local model -- better answers, no 2.5 GB\n'
    printf '  download, and the transcript leaves the phone. Get a key at\n'
    printf '  %shttps://console.anthropic.com/settings/keys%s\n\n' "$D" "$R"
    _store_key
  fi

  if [ "$pinned" = "llama" ]; then
    printf '\n  %sNote:%s config.toml pins backend = "llama", so a key is not used\n' \
      "$YEL" "$R"
    printf '  until you change that to "auto" or "claude".\n'
  fi
  pause
}

act_install() {
  # Run the installer. Resumable, so this doubles as 'repair'.
  printf '\n'
  bash "$NISOS_HOME/scripts/bootstrap.sh"
  pause
}

act_update() {
  # Check for a new release and offer to install it. Leaves config and models
  # alone -- they are gitignored and live outside the tracked tree.
  printf '\n  checking...\n'
  if bash "$NISOS_HOME/scripts/update.sh" check; then
    local v; v="$(bash "$NISOS_HOME/scripts/update.sh" status)"
    printf '\n  %snisos %s is available.%s Install now? [Y/n] ' "$B" "$v" "$R"
    read -r yn </dev/tty
    case "$yn" in
      [nN]*) printf '  left alone\n' ;;
      *)     printf '\n'; bash "$NISOS_HOME/scripts/update.sh" install ;;
    esac
  else
    printf '\n  Already on the latest version (%s).\n' \
      "$(sed -n 's/^__version__ = "\(.*\)"/\1/p' "$NISOS_HOME/nisos/__init__.py")"
  fi
  pause
}

act_rollback() {
  # Undo the last update. The safety net for a bad release on a phone you
  # cannot conveniently reach.
  printf '\n'
  bash "$NISOS_HOME/scripts/update.sh" rollback
  pause
}

act_cleanup() {
  # Delete build scrap. Confirms first, because it removes the compiler.
  printf '\n  %sThis deletes the build trees and the compiler%s (~4 GB back).\n' "$B" "$R"
  printf '  You can always reinstall them later. Continue? [y/N] '
  read -r yn </dev/tty
  case "$yn" in
    [yY]*) printf '\n'; bash "$NISOS_HOME/scripts/postbuild.sh" ;;
    *)     printf '  cancelled\n' ;;
  esac
  pause
}

# --------------------------------------------------------------------------
# Main loop
# --------------------------------------------------------------------------

main() {
  # Draw the board, read one key, dispatch, repeat.
  while true; do
    draw_header
    cat <<EOF
   ${B}a${R}  Open the app ${D}(web UI)${R}
   ${B}p${R}  Keep it running for the icon  ${D}$(app_mode_state)${R}
   ${B}1${R}  Speak a command
   ${B}2${R}  Listen continuously
   ${B}3${R}  Type a command
   ${B}4${R}  What can it do?

   ${B}k${R}  Online brain ${D}(API key)${R}          ${D}$(brain_label)${R}
   ${B}5${R}  Start / restart the model
   ${B}6${R}  Stop the model
   ${B}7${R}  Diagnostics
   ${B}8${R}  View log
   ${B}n${R}  Speak button in the shade    ${D}$(notification_state)${R}

   ${B}9${R}  Install or repair
   ${B}u${R}  Check for updates
   ${B}r${R}  Roll back last update
   ${B}c${R}  Free up space
   ${B}q${R}  Quit
EOF
    printf '\n  %s>%s ' "$CYN" "$R"
    read -r choice </dev/tty || exit 0

    case "$choice" in
      a|A) act_ui ;;
      p|P) act_app_mode ;;
      1) act_speak ;;
      2) act_listen ;;
      3) act_type ;;
      4) act_actions ;;
      k|K) act_key ;;
      5) act_start ;;
      6) act_stop ;;
      7) act_check ;;
      8) act_log ;;
      n|N) act_notification ;;
      9) act_install ;;
      u|U|0) act_update ;;
      r|R) act_rollback ;;
      c|C) act_cleanup ;;
      q|Q|"") clear; exit 0 ;;
      *) ;;
    esac
  done
}

main "$@"
