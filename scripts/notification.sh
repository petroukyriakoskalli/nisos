#!/data/data/com.termux/files/usr/bin/bash
#
# The always-there control: a permanent notification with a Speak button.
#
#   notification.sh on        put it up (and keep it across reboots)
#   notification.sh off       take it away
#   notification.sh status    is it up?
#
# Internal, wired to the buttons -- you never type these:
#   notification.sh turn      take one turn, then show what it said
#   notification.sh quiet     stop the model, free the RAM
#
# Why a notification, and not a hardware button
# ---------------------------------------------
# On a locked phone Android delivers input to exactly three places: the system
# UI, the media session, and gestures the system itself is assigned to. An app
# never sees a volume-key combo, so no Tasker profile can make "volume up x3"
# work from your pocket -- the press does not arrive.
#
# The notification shade *is* the system UI. A button here is tappable from the
# lock screen, and it costs no battery, no Tasker, and no extra app. It is also
# the only trigger in this project with nothing to install.
#
# It doubles as a one-line status display: the content line becomes whatever
# nisos last said, so the shade tells you it heard you correctly without
# opening anything.
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
ID="nisos"
BOOT_DIR="$HOME/.termux/boot"
BOOT_SCRIPT="$BOOT_DIR/20-nisos-notification.sh"
LOCKDIR="$HOME/.nisos/turn.lock"
IDLE="ελληνικά + english · offline"

mkdir -p "$HOME/.nisos" 2>/dev/null || true

have() { command -v "$1" >/dev/null 2>&1; }

if ! have termux-notification; then
  echo "termux-notification not found -- install the Termux:API app and"
  echo "run: pkg install termux-api"
  exit 1
fi

# --------------------------------------------------------------------------
# Posting
# --------------------------------------------------------------------------

post() {
  # Re-posting with the same --id replaces in place rather than stacking a
  # second one. --alert-once keeps it silent on every update after the first,
  # which matters because a turn re-posts this twice.
  termux-notification \
    --id "$ID" \
    --title "nisos" \
    --content "${1:-$IDLE}" \
    --icon mic \
    --priority low \
    --ongoing \
    --alert-once \
    --action        "bash $NISOS_HOME/scripts/nisos-ui.sh" \
    --button1       "Speak" \
    --button1-action "bash $NISOS_HOME/scripts/notification.sh turn" \
    --button2       "Stop model" \
    --button2-action "bash $NISOS_HOME/scripts/notification.sh quiet" \
    2>/dev/null
}

# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------

cmd_on() {
  post
  echo "up -- pull down the shade, it has a Speak button."

  # Survive a reboot. Termux:Boot is a separate F-Droid app; if it is not
  # installed the script sits there harmlessly until it is.
  mkdir -p "$BOOT_DIR" 2>/dev/null || true
  if [ -d "$BOOT_DIR" ]; then
    cat > "$BOOT_SCRIPT" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
# Written by scripts/notification.sh -- gets overwritten, don't edit.
bash "$NISOS_HOME/scripts/notification.sh" on >/dev/null 2>&1
EOF
    chmod 700 "$BOOT_SCRIPT" 2>/dev/null || true
    echo "reboot hook written to $BOOT_SCRIPT"
    echo "  (needs the Termux:Boot app from F-Droid, once)"
  fi
}

cmd_off() {
  termux-notification-remove "$ID" 2>/dev/null
  rm -f "$BOOT_SCRIPT"
  echo "gone."
}

cmd_status() {
  # There is no way to ask Android whether a notification is showing, so go by
  # whether the reboot hook is installed -- which is what `on` leaves behind.
  [ -f "$BOOT_SCRIPT" ] && echo "on" || echo "off"
}

cmd_turn() {
  # The Speak button.
  #
  # Locked first. A notification button is very easy to double-tap, and two
  # turns at once means two processes fighting over one microphone -- you get
  # nonsense from both. The web UI has a lock for the same reason; this path
  # had none. mkdir is the atomic primitive that needs nothing installed
  # (flock lives in util-linux, which is not a given in Termux).
  if ! mkdir "$LOCKDIR" 2>/dev/null; then
    # Unless it is stale -- a killed turn would otherwise wedge the button
    # forever, and there is no way to clear it from a notification.
    if [ -z "$(find "$LOCKDIR" -maxdepth 0 -mmin +2 2>/dev/null)" ]; then
      post "still listening…"
      return 0
    fi
    rmdir "$LOCKDIR" 2>/dev/null
    mkdir "$LOCKDIR" 2>/dev/null || return 0
  fi
  trap 'rmdir "$LOCKDIR" 2>/dev/null' EXIT INT TERM

  # Show that it is listening before recording starts: a tap with no feedback
  # for two seconds reads as a tap that did not register, and the retry lands
  # in the middle of the recording.
  post "listening…"
  local spoken
  spoken="$(bash "$NISOS_HOME/scripts/nisos.sh" 2>/dev/null | tail -1)"
  post "${spoken:-$IDLE}"
}

cmd_quiet() {
  # Frees ~2.5 GB. Routed commands still work without the model, so the Speak
  # button stays useful afterwards.
  pkill -f 'llama-server' 2>/dev/null
  termux-wake-unlock 2>/dev/null
  post "model stopped · $IDLE"
}

case "${1:-on}" in
  on)     cmd_on     ;;
  off)    cmd_off    ;;
  status) cmd_status ;;
  turn)   cmd_turn   ;;
  quiet)  cmd_quiet  ;;
  *) echo "usage: notification.sh {on|off|status}"; exit 2 ;;
esac
