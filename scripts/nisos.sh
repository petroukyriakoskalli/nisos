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
# Hold the wake lock only around the turn itself, and always give it back --
# see the trap below. A permanently-held lock is the single biggest battery
# cost in this project, and it only saves a ~10 second model reload.
release_lock() { termux-wake-unlock >/dev/null 2>&1 || true; }
trap release_lock EXIT INT TERM
termux-wake-lock >/dev/null 2>&1 || true

if ! curl -sf --max-time 1 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  # Ask which brain this install uses before loading anything. It costs one
  # Python start, and only on this branch -- where the alternative is spending
  # nine seconds and 2.5 GB of RAM on a model the online backend never calls.
  # When llama-server is already up the curl above short-circuits and none of
  # this runs, so the common path is unchanged.
  BRAIN="$(python -m nisos --print-backend 2>/dev/null || echo llama)"

  if [ "$BRAIN" = "claude" ]; then
    echo "$(date '+%H:%M:%S')  brain=claude -- not starting llama-server" \
      >> "$HOME/.nisos/nisos.log"
  else
    echo "$(date '+%H:%M:%S')  starting llama-server" >> "$HOME/.nisos/nisos.log"
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
fi

# --------------------------------------------------------------------------
# Keep the logs from quietly eating the phone. llama-server is chatty and
# nothing else trims llama.log, so do it here -- this is the one thing that
# runs on every turn. Costs one stat per file.
for logfile in "$HOME/.nisos/nisos.log" "$HOME/.nisos/llama.log"; do
  [ -f "$logfile" ] || continue
  size=$(wc -c < "$logfile" 2>/dev/null || echo 0)
  if [ "${size:-0}" -gt 5242880 ]; then          # 5 MB
    tail -c 1048576 "$logfile" > "$logfile.tmp" 2>/dev/null \
      && mv "$logfile.tmp" "$logfile"
  fi
done

# --------------------------------------------------------------------------
# NOT `exec`. Replacing the shell discards the EXIT trap above, so the wake
# lock is never given back -- which is exactly the bug the trap was added to
# fix, and it was silently present until a QA pass caught it. One idle shell
# for the length of a turn is the price of the lock actually being released.
python -m nisos "$@"
