#!/data/data/com.termux/files/usr/bin/bash
#
# Launcher for one turn. This is what Tasker calls on a back-tap.
#
# Tasker setup:
#   Profile:  Event -> Plugin -> RegiStar back-tap (double)
#   Task:     Run Shell / Termux plugin ->  bash ~/nisos/scripts/nisos.sh
#
# Keeps llama-server alive between turns, because loading a 2.5 GB model takes
# about nine seconds and paying that on every command is unusable.
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
MODEL="${NISOS_MODEL:-$NISOS_HOME/models/Qwen3-4B-Q4_K_M.gguf}"
PORT="${NISOS_PORT:-8080}"
THREADS="${NISOS_THREADS:-6}"
CONTEXT="${NISOS_CONTEXT:-4096}"

cd "$NISOS_HOME" || exit 1

# --------------------------------------------------------------------------
# Make sure the model is resident. --prompt-cache is deliberately absent: it
# writes KV state to disk and grows without limit.
if ! curl -sf --max-time 1 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  echo "$(date '+%H:%M:%S')  starting llama-server" >> "$HOME/.nisos/nisos.log"
  termux-wake-lock
  nohup "$NISOS_HOME/bin/llama-server" \
        -m "$MODEL" --port "$PORT" -t "$THREADS" -c "$CONTEXT" \
        >> "$HOME/.nisos/llama.log" 2>&1 &

  # Wait for it, but not forever -- the router path works without the model,
  # so a slow start should not block "torch on".
  for _ in $(seq 1 30); do
    curl -sf --max-time 1 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
    sleep 0.5
  done
fi

# --------------------------------------------------------------------------
exec python -m nisos "$@"
