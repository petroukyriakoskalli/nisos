#!/data/data/com.termux/files/usr/bin/bash
#
# Make the home-screen icon behave like an app.
#
#   app-mode.sh on       leave the server up; icon works instantly, always
#   app-mode.sh off      back to launching it from Termux each time
#   app-mode.sh status
#
# The problem it solves
# ---------------------
# "Add to Home Screen" makes a bookmark, not an app. A bookmark cannot start
# anything, so tapping it when nothing is listening gives you Chrome's "site
# can't be reached" -- and by default nisos shuts its server down forty-five
# seconds after you close the page. The icon is therefore dead almost all of
# the time, which reads as broken rather than as designed.
#
# App mode splits the two things that used to be shut down together:
#
#   the model    2.5 GB, still stopped the moment you close the page
#   the server   a few MB of idle Python, now left running
#
# So the icon always opens instantly, and the expensive thing is still only
# resident while you are actually talking to it. That split already existed in
# config as ui.stop_model_on_exit / ui.quit_on_exit; this turns it on, makes
# it survive a reboot, and starts it now.
#
# ⚠️ Android will still kill a background Termux eventually unless battery
# optimisation is off for it. That is the one setting this cannot do for you.
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
CONFIG="${NISOS_CONFIG:-$HOME/.nisos/config.toml}"
BOOT_DIR="$HOME/.termux/boot"
BOOT_SCRIPT="$BOOT_DIR/10-nisos-ui.sh"
PORT="${NISOS_UI_PORT:-8765}"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
dim()  { printf '\033[2m%s\033[0m\n' "$1"; }

running() { curl -sf --max-time 1 "http://127.0.0.1:$PORT/icon.svg" >/dev/null 2>&1; }

# --------------------------------------------------------------------------
# Config editing. Deliberately dumb: rewrite the two keys under [ui] if they
# are there, append a [ui] block if they are not. A TOML parser would be
# nicer, but this has to work with only the standard library present and the
# file is one people hand-edit.
# --------------------------------------------------------------------------

set_key() {
  local key="$1" value="$2"
  mkdir -p "$(dirname "$CONFIG")"
  touch "$CONFIG"

  if grep -qE "^[[:space:]]*$key[[:space:]]*=" "$CONFIG"; then
    sed -i -E "s|^[[:space:]]*$key[[:space:]]*=.*|$key = $value|" "$CONFIG"
  elif grep -qE '^\[ui\]' "$CONFIG"; then
    sed -i -E "0,/^\[ui\]/s|^\[ui\]|[ui]\n$key = $value|" "$CONFIG"
  else
    printf '\n[ui]\n%s = %s\n' "$key" "$value" >> "$CONFIG"
  fi
}

# --------------------------------------------------------------------------

cmd_on() {
  set_key quit_on_exit false
  set_key stop_model_on_exit true
  bold "app mode on"
  dim  "  the server stays up; the model still stops when you close the page"

  mkdir -p "$BOOT_DIR" 2>/dev/null || true
  cat > "$BOOT_SCRIPT" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
# Written by scripts/app-mode.sh -- gets overwritten, don't edit.
# Serve only: no browser, nothing on screen. The home-screen icon is what
# opens the page; this just makes sure something is listening when it does.
cd "$NISOS_HOME" || exit 0
exec python -m nisos.web --port $PORT >> "\$HOME/.nisos/ui.log" 2>&1
EOF
  chmod 700 "$BOOT_SCRIPT" 2>/dev/null || true
  dim "  reboot hook: $BOOT_SCRIPT  (needs the Termux:Boot app, once)"

  if running; then
    printf '\n  already serving on %s\n' "$PORT"
  else
    printf '\n  starting it now...\n'
    cd "$NISOS_HOME" || exit 1
    nohup python -m nisos.web --port "$PORT" >> "$HOME/.nisos/ui.log" 2>&1 &
    for _ in $(seq 1 20); do running && break; sleep 0.25; done
    running && printf '  up.\n' || printf '  did not come up -- see ~/.nisos/ui.log\n'
  fi

  cat <<EOF

  One more thing, and it is the one that decides whether this lasts:

    Settings -> Apps -> Termux -> Battery -> Unrestricted

  Without it Android eventually kills the background Termux and the icon goes
  dead again. Then, once:

    open the UI, browser menu -> Add to Home Screen

EOF
}

cmd_off() {
  set_key quit_on_exit true
  rm -f "$BOOT_SCRIPT"
  bold "app mode off"
  dim  "  closing the page shuts the server down again; the icon only works"
  dim  "  while it is running. Launch it with scripts/nisos-ui.sh."
}

cmd_status() {
  local mode="off"
  grep -qE '^[[:space:]]*quit_on_exit[[:space:]]*=[[:space:]]*false' "$CONFIG" 2>/dev/null \
    && mode="on"
  printf '%s' "$mode"
  running && printf ' (serving)' || printf ' (not serving)'
  printf '\n'
}

case "${1:-status}" in
  on)     cmd_on     ;;
  off)    cmd_off    ;;
  status) cmd_status ;;
  *) echo "usage: app-mode.sh {on|off|status}"; exit 2 ;;
esac
