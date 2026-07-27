#!/data/data/com.termux/files/usr/bin/bash
#
# Launch the nisos web UI and open it.
#
#   bash scripts/nisos-ui.sh
#
# Starts the local server, prints its URL, and hands that URL to Android so it
# opens in the browser. The first time, use the browser's "Add to Home Screen"
# -- after that the icon launches it fullscreen with no browser chrome and you
# never see a terminal again.
#
# Deliberately does NOT start llama-server. Routed commands ("torch on",
# «άναψε τον φακό») don't need it, and starting a 2.5 GB model just because you
# opened the UI is exactly the kind of thing that drains a battery. It starts on
# demand the first time you say something the router can't handle.
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
PORT="${NISOS_UI_PORT:-8765}"
STATE_DIR="$HOME/.nisos"

mkdir -p "$STATE_DIR"
cd "$NISOS_HOME" || { echo "nisos not found at $NISOS_HOME"; exit 1; }

# --------------------------------------------------------------------------
# Don't start a second one. If a UI is already listening, just reopen it --
# but we need its token, which only the running process knows, so it is
# written to a file at startup.
if curl -sf --max-time 1 "http://127.0.0.1:$PORT/icon.svg" >/dev/null 2>&1; then
  if [ -f "$STATE_DIR/ui-url" ]; then
    echo "already running; reopening"
    termux-open-url "$(cat "$STATE_DIR/ui-url")" 2>/dev/null
    exit 0
  fi
  echo "Something is already on port $PORT. Set NISOS_UI_PORT to use another."
  exit 1
fi

# --------------------------------------------------------------------------
# Start the server. It prints its URL (with the one-time token) on the first
# line of stdout; capture that, save it, and open it.
echo "starting nisos UI..."
python -m nisos.web --port "$PORT" > "$STATE_DIR/ui.out" 2>>"$STATE_DIR/ui.log" &
UI_PID=$!

URL=""
for _ in $(seq 1 40); do
  URL="$(head -1 "$STATE_DIR/ui.out" 2>/dev/null || true)"
  case "$URL" in http*) break ;; esac
  kill -0 "$UI_PID" 2>/dev/null || { echo "UI failed to start:"; tail -5 "$STATE_DIR/ui.log"; exit 1; }
  sleep 0.25
done

case "$URL" in
  http*) ;;
  *) echo "UI didn't report a URL; see $STATE_DIR/ui.log"; exit 1 ;;
esac

printf '%s\n' "$URL" > "$STATE_DIR/ui-url"
echo "$URL"

# --------------------------------------------------------------------------
if command -v termux-open-url >/dev/null 2>&1; then
  termux-open-url "$URL" 2>/dev/null || echo "(open that URL yourself)"
else
  echo "Open that URL in your browser."
fi

# Stay attached so closing this Termux session also takes the UI with it --
# and the UI, in turn, stops the model.
wait "$UI_PID"
