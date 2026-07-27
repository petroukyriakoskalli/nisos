#!/data/data/com.termux/files/usr/bin/bash
#
# nisos one-command installer.
#
#   bash scripts/bootstrap.sh
#
# Does everything that can be automated on Android, in order, and stops with a
# clear instruction for the handful of things that cannot be:
#
#   * installing APKs           -- Android requires a human tap
#   * granting permissions      -- ditto
#   * Google's offline voice packs -- lives inside the Google app's own UI
#
# For those it opens the exact Settings screen for you, so each is one tap
# rather than a hunt through menus.
#
# RESUMABLE. Every step records itself in ~/.nisos/.bootstrap-state, so if the
# build gets killed -- Android OOM, screen off, dropped wi-fi -- just run it
# again and it picks up where it stopped. Nothing is redone.
#
# Options:
#   NISOS_MODEL_URL=<url>   skip model auto-discovery and use this
#   NISOS_SKIP_MODEL=1      don't fetch a model at all
#   NISOS_FORCE=<step>      re-run one step, e.g. NISOS_FORCE=build
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
STATE_DIR="$HOME/.nisos"
STATE="$STATE_DIR/.bootstrap-state"
MODELS="$NISOS_HOME/models"
NEED_GB=10

# --------------------------------------------------------------------------
# Output helpers. Colour, but degrade to plain text if the terminal is dumb.
# --------------------------------------------------------------------------
if [ -t 1 ]; then
  B=$'\033[1m'; DIM=$'\033[2m'; R=$'\033[0m'
  OK=$'\033[1;32m'; WARN=$'\033[1;33m'; ERR=$'\033[1;31m'; ACC=$'\033[1;36m'
else
  B=""; DIM=""; R=""; OK=""; WARN=""; ERR=""; ACC=""
fi

STEP_N=0
step()  { STEP_N=$((STEP_N + 1)); printf '\n%s[%02d]%s %s%s%s\n' "$ACC" "$STEP_N" "$R" "$B" "$*" "$R"; }
ok()    { printf '     %s✓%s %s\n' "$OK" "$R" "$*"; }
skip()  { printf '     %s·%s %s %s(already done)%s\n' "$DIM" "$R" "$*" "$DIM" "$R"; }
warn()  { printf '     %s!%s %s\n' "$WARN" "$R" "$*"; }
fail()  { printf '     %s✗%s %s\n' "$ERR" "$R" "$*"; }
ask()   { printf '\n     %s%s%s\n     %sPress Enter when done, or s to skip: %s' "$B" "$1" "$R" "$DIM" "$R"; read -r REPLY </dev/tty || REPLY=s; }

# --------------------------------------------------------------------------
# Resumability
# --------------------------------------------------------------------------
mkdir -p "$STATE_DIR"
touch "$STATE"

done_already() {
  [ "${NISOS_FORCE:-}" = "$1" ] && return 1
  grep -qx "$1" "$STATE"
}
mark() { grep -qx "$1" "$STATE" || echo "$1" >> "$STATE"; }

# --------------------------------------------------------------------------
# Open an Android Settings screen. Best-effort: One UI moves things around and
# not every intent exists on every build, so a failure here is a warning, not
# an error -- the instruction text still tells you where to go.
# --------------------------------------------------------------------------
open_settings() {
  am start -a "$1" >/dev/null 2>&1 || warn "couldn't open that screen automatically"
}

# ==========================================================================
printf '\n%s  nisos%s -- offline bilingual voice assistant\n' "$B" "$R"
printf '  %sThis takes about 90 minutes, mostly waiting on a compile.%s\n' "$DIM" "$R"
printf '  %sSafe to interrupt and re-run: it resumes where it stopped.%s\n' "$DIM" "$R"

# ==========================================================================
step "Preflight"

if [ ! -d /data/data/com.termux/files/usr ]; then
  fail "This is not Termux, or it's the Play Store build."
  fail "Install Termux from F-Droid: https://f-droid.org/packages/com.termux/"
  exit 1
fi
ok "Termux looks right"

FREE_GB=$(df -P "$HOME" 2>/dev/null | awk 'NR==2 {printf "%d", $4/1048576}')
if [ -n "$FREE_GB" ] && [ "$FREE_GB" -lt "$NEED_GB" ]; then
  fail "Only ${FREE_GB} GB free; the build needs about ${NEED_GB} GB temporarily."
  fail "(The finished install is ~3 GB -- the rest is compiler scrap that gets deleted.)"
  exit 1
fi
ok "${FREE_GB:-?} GB free"

if [ ! -f "$NISOS_HOME/nisos/__main__.py" ]; then
  # Being run from a download folder rather than ~/nisos. Move it into place.
  HERE="$(cd "$(dirname "$0")/.." && pwd)"
  if [ -f "$HERE/nisos/__main__.py" ] && [ "$HERE" != "$NISOS_HOME" ]; then
    warn "Moving the checkout to $NISOS_HOME"
    mv "$HERE" "$NISOS_HOME" && ok "moved" || { fail "couldn't move it there"; exit 1; }
  else
    fail "Can't find the nisos source. Run this from inside the checkout."
    exit 1
  fi
fi
cd "$NISOS_HOME" || exit 1
ok "Source at $NISOS_HOME"

# ==========================================================================
step "Packages"
if done_already packages; then
  skip "toolchain installed"
else
  pkg update -y >/dev/null 2>&1
  if pkg install -y git cmake clang binutils python termux-api curl unzip >/dev/null 2>&1; then
    ok "git cmake clang python termux-api curl unzip"
    mark packages
  else
    fail "package install failed -- check your connection and re-run"
    exit 1
  fi
fi

# ==========================================================================
step "Wake lock"
# Without this One UI suspends the session mid-build, and later kills the
# model server so your first command of the morning waits nine seconds.
termux-wake-lock 2>/dev/null && ok "held" || warn "couldn't acquire (harmless during install)"

# ==========================================================================
step "Storage permission"
if done_already storage; then
  skip "granted"
elif [ -d "$HOME/storage" ]; then
  ok "already granted"; mark storage
else
  warn "A permission dialog is about to appear -- tap Allow."
  termux-setup-storage
  sleep 2
  if [ -d "$HOME/storage" ]; then ok "granted"; mark storage
  else warn "not granted -- only needed to read a model from Downloads"; fi
fi

# ==========================================================================
step "Building llama.cpp and whisper.cpp"
if done_already build; then
  skip "binaries present"
else
  warn "This is the long one: 20-40 minutes. Keep the screen on."
  if bash "$NISOS_HOME/scripts/install.sh"; then
    ok "built and stripped"
    mark build
  else
    fail "build failed -- scroll up for the error, then re-run this script"
    exit 1
  fi
fi

# ==========================================================================
step "Language model"
mkdir -p "$MODELS"
EXISTING="$(find "$MODELS" -name '*.gguf' -size +100M 2>/dev/null | head -1)"

if [ -n "$EXISTING" ]; then
  skip "found $(basename "$EXISTING")"
  mark model
elif [ "${NISOS_SKIP_MODEL:-0}" = "1" ]; then
  warn "skipped by request -- routed commands will work, reasoned ones won't"
elif done_already model; then
  skip "done"
else
  # Try known-good Hugging Face locations, verifying each with a HEAD request
  # before committing to a 2.5 GB download. Repo names move around, so this
  # degrades to asking rather than failing.
  CANDIDATES="
https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf
https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf
https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf
"
  URL="${NISOS_MODEL_URL:-}"
  if [ -z "$URL" ]; then
    printf '     checking mirrors'
    for C in $CANDIDATES; do
      printf '.'
      CODE=$(curl -sIL -o /dev/null -w '%{http_code}' --max-time 20 "$C" 2>/dev/null)
      if [ "$CODE" = "200" ]; then URL="$C"; break; fi
    done
    printf '\n'
  fi

  if [ -z "$URL" ]; then
    warn "Couldn't find a model automatically."
    printf '     %sOpen huggingface.co on the phone, search "Qwen3 4B Instruct GGUF",%s\n' "$DIM" "$R"
    printf '     %scopy the download link for the Q4_K_M file, then re-run:%s\n' "$DIM" "$R"
    printf '\n       %sNISOS_MODEL_URL="<link>" bash scripts/bootstrap.sh%s\n' "$ACC" "$R"
  else
    ok "source: $(echo "$URL" | cut -d/ -f4-5)"
    warn "Downloading ~2.5 GB. Resumable -- re-run if it drops."
    if curl -L -C - --retry 3 --progress-bar \
         -o "$MODELS/$(basename "$URL")" "$URL"; then
      ok "$(basename "$URL")"
      mark model
    else
      fail "download failed -- re-run to resume from where it stopped"
    fi
  fi
fi

# ==========================================================================
step "Config"
if [ -f "$NISOS_HOME/config.toml" ]; then
  skip "config.toml exists"
else
  cp "$NISOS_HOME/config.example.toml" "$NISOS_HOME/config.toml"
  MODEL_FILE="$(find "$MODELS" -name '*.gguf' -size +100M 2>/dev/null | head -1)"
  ok "created config.toml"
  [ -n "$MODEL_FILE" ] && ok "model: $(basename "$MODEL_FILE")"
fi

# ==========================================================================
step "Home-screen button"
# Termux:Widget exposes anything in ~/.shortcuts as a home-screen launcher.
# This is the closest thing to a real button that Android allows.
mkdir -p "$HOME/.shortcuts" "$HOME/.shortcuts/tasks"

# The main one: opens the control panel, so nothing after this needs a
# command typed on a phone keyboard.
cat > "$HOME/.shortcuts/nisos" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
exec bash "$NISOS_HOME/scripts/menu.sh"
EOF

# Straight to a single spoken command -- what you'd bind to a back-tap.
cat > "$HOME/.shortcuts/nisos-speak" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
exec bash "$NISOS_HOME/scripts/nisos.sh"
EOF

cat > "$HOME/.shortcuts/nisos-listen" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
exec bash "$NISOS_HOME/scripts/nisos.sh" --listen
EOF

chmod +x "$HOME/.shortcuts/"* 2>/dev/null
ok "nisos (control panel), nisos-speak, nisos-listen"

# Start the server automatically when the phone boots, if Termux:Boot is there.
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/nisos-server" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
MODEL=\$(find "$MODELS" -name '*.gguf' -size +100M | head -1)
[ -n "\$MODEL" ] && nohup "$NISOS_HOME/bin/llama-server" \\
  -m "\$MODEL" --port 8080 -t 6 -c 4096 >> "$STATE_DIR/llama.log" 2>&1 &
EOF
chmod +x "$HOME/.termux/boot/nisos-server"
ok "boot script (needs the Termux:Boot app to fire)"

# ==========================================================================
step "The parts Android won't let a script do"

if done_already manual; then
  skip "walked through already -- delete ~/.nisos/.bootstrap-state to redo"
else
  printf '     %sFour taps. I will open each screen for you.%s\n' "$DIM" "$R"

  ask "1/4  Battery: set Termux to Unrestricted, or the model server dies overnight."
  [ "$REPLY" != "s" ] && open_settings android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS

  ask "2/4  Greek speech recognition: Voice -> Offline speech recognition -> add Ελληνικά."
  [ "$REPLY" != "s" ] && open_settings android.settings.VOICE_INPUT_SETTINGS

  ask "3/4  Greek speaking voice: install the Greek voice under Text-to-speech."
  [ "$REPLY" != "s" ] && open_settings com.android.settings.TTS_SETTINGS

  ask "4/4  Microphone: make sure Termux:API has it."
  [ "$REPLY" != "s" ] && am start -a android.settings.APPLICATION_DETAILS_SETTINGS \
      -d package:com.termux.api >/dev/null 2>&1

  mark manual
  printf '\n'
  ok "done"
fi

# ==========================================================================
step "Starting the model"
if curl -sf --max-time 2 http://127.0.0.1:8080/health >/dev/null 2>&1; then
  ok "already running"
else
  MODEL_FILE="$(find "$MODELS" -name '*.gguf' -size +100M 2>/dev/null | head -1)"
  if [ -n "$MODEL_FILE" ]; then
    nohup "$NISOS_HOME/bin/llama-server" -m "$MODEL_FILE" \
      --port 8080 -t 6 -c 4096 >> "$STATE_DIR/llama.log" 2>&1 &
    printf '     loading'
    for _ in $(seq 1 40); do
      curl -sf --max-time 1 http://127.0.0.1:8080/health >/dev/null 2>&1 && break
      printf '.'; sleep 1
    done
    printf '\n'
    curl -sf --max-time 1 http://127.0.0.1:8080/health >/dev/null 2>&1 \
      && ok "listening on :8080" || warn "slow to start -- check $STATE_DIR/llama.log"
  else
    warn "no model yet, skipping"
  fi
fi

# ==========================================================================
step "Self-test"
python -m nisos --check
printf '\n'
printf '     %strying a typed command...%s\n' "$DIM" "$R"
python -m nisos --text "άναψε τον φακό" --dry-run 2>/dev/null | tail -1

# ==========================================================================
cat <<EOF

${B}  Done.${R}

  ${B}Open the control panel${R}
      bash ~/nisos/scripts/menu.sh

      Everything is a single keypress from there -- speak, listen, start
      and stop the model, diagnostics, update, cleanup. You should not
      need to type another command.

  ${B}Make it a home-screen icon${R}
      Install Termux:Widget from F-Droid, then long-press the home screen
      -> Widgets -> Termux -> pick "nisos". That icon opens the panel.
      https://f-droid.org/packages/com.termux.widget/

  ${B}Make it a back-tap${R}
      Galaxy Store -> Good Lock -> RegiStar -> Back-tap (double)
      -> launch the Tasker task that runs ~/nisos/scripts/nisos.sh

  ${B}Reclaim about 4 GB of build scrap${R}
      bash ~/nisos/scripts/postbuild.sh

  ${B}If something looks broken${R}
      python -m nisos --check
      tail -40 ~/.nisos/nisos.log

EOF
