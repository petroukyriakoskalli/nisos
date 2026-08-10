#!/data/data/com.termux/files/usr/bin/bash
#
# nisos one-command installer.
#
#   bash scripts/bootstrap.sh
#
# Does everything that can be automated on Android, and defers the handful of
# things that cannot be -- installing APKs, granting permissions, Google's
# offline voice packs -- to a single block of taps at the very END.
#
# That ordering matters more than it looks. An installer that stops to ask a
# question twenty minutes in is one you have to babysit for an hour. This one
# does all the slow work unattended, then asks for four taps once it is done,
# opening the exact Settings screen for each.
#
# The 20-40 minute compile and the 2.5 GB model download also run CONCURRENTLY,
# since they have nothing to do with each other. Roughly 15 minutes saved.
#
# RESUMABLE. Every step records itself in ~/.nisos/.bootstrap-state, so if the
# build gets killed -- Android OOM, screen off, dropped wi-fi -- just run it
# again and it picks up where it stopped. Nothing is redone.
#
# Options:
#   NISOS_ONLINE=1          online brain (Claude API): no 2.5 GB model, no
#                           40-minute wait for one. ~15 minutes instead of ~50.
#                           Asks for an API key near the end.
#   NISOS_MODEL_URL=<url>   skip model auto-discovery and use this
#   NISOS_SKIP_MODEL=1      don't fetch a model at all
#   NISOS_FORCE=<step>      re-run one step, e.g. NISOS_FORCE=build
#
set -uo pipefail

# Online mode is "no local model" plus a key prompt. The download and the three
# steps that depend on it already handle a missing model, so this reuses that
# path rather than adding a second one.
if [ "${NISOS_ONLINE:-0}" = "1" ]; then
  NISOS_SKIP_MODEL=1
fi

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
STATE_DIR="$HOME/.nisos"
STATE="$STATE_DIR/.bootstrap-state"
MODELS="$NISOS_HOME/models"
REPO_URL="${NISOS_REPO:-https://github.com/petroukyriakoskalli/nisos.git}"
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
say()   { printf '     %s…%s %s\n' "$DIM" "$R" "$*"; }
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
if [ "${NISOS_ONLINE:-0}" = "1" ]; then
  printf '\n%s  nisos%s -- bilingual voice assistant, online brain\n' "$B" "$R"
  printf '  %s~15 minutes. No local model: reasoning goes to the Claude API.%s\n' "$DIM" "$R"
else
  printf '\n%s  nisos%s -- offline bilingual voice assistant\n' "$B" "$R"
  printf '  %s~50 minutes. Walk away -- nothing asks you anything until the end.%s\n' "$DIM" "$R"
fi
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
  # Three ways to arrive here, in order of likelihood.
  HERE=""
  case "${0:-}" in
    */*) HERE="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)" ;;
  esac

  if [ -n "$HERE" ] && [ -f "$HERE/nisos/__main__.py" ] && [ "$HERE" != "$NISOS_HOME" ]; then
    # Run from a ZIP unpacked in Downloads. Move it into place.
    warn "Moving the checkout to $NISOS_HOME"
    mv "$HERE" "$NISOS_HOME" && ok "moved" || { fail "couldn't move it there"; exit 1; }
  else
    # Piped straight from curl -- no source anywhere yet. Fetch it.
    say "No source yet (normal when run via curl). Fetching it."
    # Do NOT silence these. On a fresh Termux, installing git takes a minute or
    # two, and a suppressed install looks exactly like a hang -- which is
    # alarming at the very first step, before anything has visibly worked.
    if ! command -v git >/dev/null 2>&1; then
      say "Installing git first (this takes a minute on a fresh Termux)..."
      apt install -y git || { fail "couldn't install git"; exit 1; }
    fi
    say "Cloning $REPO_URL ..."
    if git clone --progress "$REPO_URL" "$NISOS_HOME"; then
      ok "cloned to $NISOS_HOME"
    else
      fail "Clone failed. Check your connection, or download the ZIP from:"
      fail "  https://github.com/petroukyriakoskalli/nisos"
      exit 1
    fi
  fi
fi
cd "$NISOS_HOME" || exit 1
ok "Source at $NISOS_HOME ($(sed -n 's/^__version__ = "\(.*\)"/v\1/p' nisos/__init__.py 2>/dev/null))"

# ==========================================================================
step "Packages"
if done_already packages; then
  skip "toolchain installed"
else
  # A FULL UPGRADE FIRST IS NOT OPTIONAL. Termux's repos are rolling, so a
  # base image that shipped months ago plus freshly-installed packages gives
  # you mismatched ABIs. The symptom is brutal and unobvious:
  #
  #   CANNOT LINK EXECUTABLE "curl": cannot locate symbol
  #   "SSL_set_quic_tls_transport_params" ... libngtcp2_crypto_ossl.so
  #
  # curl then fails, so prebuilt binaries can't be fetched AND the fallback
  # compile can't install its toolchain. `pkg update` alone does not fix this
  # -- it only refreshes the package lists.
  # Use apt directly, NOT pkg. The `pkg` wrapper shells out to curl for mirror
  # selection, so on exactly the broken system this step exists to repair it
  # fails with the same link error it is trying to fix:
  #
  #   $ pkg upgrade
  #   No mirror or mirror group selected...
  #   CANNOT LINK EXECUTABLE "curl": cannot locate symbol ...
  #   Failed to run the 'curl' command.
  #
  # apt carries its own HTTP transport and has no such dependency. Termux's own
  # error message recommends exactly this.
  say "Upgrading Termux packages first (required -- skipping this breaks curl)"
  apt update -y || warn "apt update reported problems; continuing"
  DEBIAN_FRONTEND=noninteractive apt full-upgrade -y \
    -o Dpkg::Options::="--force-confnew" \
    || warn "upgrade reported problems; continuing"

  # Deliberately minimal. The 600 MB clang/cmake toolchain is installed only if
  # install.sh can't get prebuilt binaries and has to compile.
  say "Installing packages"
  if apt install -y git python termux-api curl unzip; then
    ok "git python termux-api curl unzip"
  else
    fail "package install failed -- check your connection and re-run"
    exit 1
  fi

  # Prove curl actually links before anything depends on it.
  if ! curl --version >/dev/null 2>&1; then
    fail "curl is installed but won't link -- your Termux packages are mismatched."
    fail "Fix it by hand, with apt rather than pkg (pkg needs curl):"
    fail "    apt update && apt full-upgrade -y"
    fail "then re-run this script."
    exit 1
  fi
  ok "curl works"
  mark packages
fi

# ==========================================================================
step "Wake lock"
# Held ONLY for the duration of the install, so a 40-minute compile doesn't get
# suspended half-way. It is released again at the end -- see the last step.
#
# Holding it permanently is what drains the battery: it stops the CPU entering
# deep sleep for the entire time the phone is idle, which is most of the day.
# The only thing it buys you is avoiding a ~10 second model reload, and that is
# a bad trade.
termux-wake-lock 2>/dev/null && ok "held for the install" \
  || warn "couldn't acquire (harmless)"

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
# The two slow jobs -- a 20-40 minute compile and a 2.5 GB download -- have
# nothing to do with each other, so they run at the same time. That is roughly
# 15 minutes off the total for free.
# ==========================================================================
step "Downloading the model in the background"
mkdir -p "$MODELS"
EXISTING="$(find "$MODELS" -name '*.gguf' -size +100M 2>/dev/null | head -1)"

fetch_model() {
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
    echo "NOMODEL" > "$STATE_DIR/.model-result"
    return 1
  fi

  # Quiet (-sS): this runs behind the build, and two progress bars fighting
  # over one terminal is unreadable. Resumable with -C -.
  if curl -sS -L -C - --retry 5 --retry-delay 5 \
       -o "$MODELS/$(basename "$URL")" "$URL" 2>"$STATE_DIR/model-download.log"; then
    echo "OK $(basename "$URL")" > "$STATE_DIR/.model-result"
    mark model
  else
    echo "FAILED" > "$STATE_DIR/.model-result"
  fi
}

MODEL_PID=""
rm -f "$STATE_DIR/.model-result"
if [ -n "$EXISTING" ]; then
  skip "found $(basename "$EXISTING")"
  mark model
elif [ "${NISOS_SKIP_MODEL:-0}" = "1" ]; then
  warn "skipped by request -- routed commands will work, reasoned ones won't"
elif done_already model; then
  skip "done"
else
  fetch_model &
  MODEL_PID=$!
  ok "started (~2.5 GB, runs alongside the build)"
fi

# ==========================================================================
step "Speech and language engines"
if done_already build; then
  skip "binaries present"
else
  warn "Downloading prebuilt binaries; compiling only if that isn't possible."
  if bash "$NISOS_HOME/scripts/install.sh"; then
    ok "built and stripped"
    mark build
  else
    fail "build failed -- scroll up for the error, then re-run this script"
    [ -n "$MODEL_PID" ] && wait "$MODEL_PID" 2>/dev/null
    exit 1
  fi
fi

# ==========================================================================
step "Waiting for the model download"
if [ -n "$MODEL_PID" ]; then
  wait "$MODEL_PID" 2>/dev/null
  RESULT="$(cat "$STATE_DIR/.model-result" 2>/dev/null || echo FAILED)"
  case "$RESULT" in
    OK\ *)   ok "${RESULT#OK }" ;;
    NOMODEL) warn "Couldn't find a model automatically. Finish the install, then:"
             printf '\n       %sNISOS_MODEL_URL="<link>" bash ~/nisos/scripts/bootstrap.sh%s\n' "$ACC" "$R"
             printf '     %sGet the link from huggingface.co -- search "Qwen3 4B Instruct GGUF", Q4_K_M.%s\n' "$DIM" "$R" ;;
    *)       warn "download failed -- re-run this script to resume where it stopped"
             printf '     %ssee %s/model-download.log%s\n' "$DIM" "$STATE_DIR" "$R" ;;
  esac
else
  skip "nothing to wait for"
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

# The main one: opens the web UI, which is the actual front end. Open it once
# in the browser, use Add to Home Screen, and it launches fullscreen with its
# own icon and no browser chrome.
cat > "$HOME/.shortcuts/nisos" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
exec bash "$NISOS_HOME/scripts/nisos-ui.sh"
EOF

# The text console, kept as the fallback for when the UI itself is the thing
# that is broken.
cat > "$HOME/.shortcuts/nisos-console" <<EOF
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
ok "nisos (web UI), nisos-console, nisos-speak, nisos-listen"

# Deliberately NOT installing a boot script. A model server that restarts
# itself after every reboot, holding a wake lock, is a battery complaint
# waiting to happen -- and scripts/nisos.sh starts it on demand anyway, which
# costs one slow first command instead of all-day drain.
rm -f "$HOME/.termux/boot/nisos-server" 2>/dev/null
ok "no boot auto-start (starts on demand instead)"

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
# The one paste. Deliberately here rather than twenty minutes in: the whole
# ordering of this script is "do the slow work unattended, ask at the end".
# ==========================================================================
step "Online brain"
if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
  ok "using the key in ANTHROPIC_API_KEY"
elif [ -s "$STATE_DIR/anthropic-key" ]; then
  skip "key already stored"
elif [ "${NISOS_ONLINE:-0}" = "1" ] || [ -z "$(find "$MODELS" -name '*.gguf' -size +100M 2>/dev/null | head -1)" ]; then
  # Either they asked for online, or there is no local model -- in both cases
  # the reasoned path has nothing to think with until a key exists.
  printf '     %sPhrases the router misses need a brain. With no local model,%s\n' "$DIM" "$R"
  printf '     %sthat means the Claude API -- get a key at%s\n' "$DIM" "$R"
  printf '     %shttps://console.anthropic.com/settings/keys%s\n\n' "$ACC" "$R"
  printf '     Paste it here (hidden), or press Enter to skip: '
  read -rs BOOTSTRAP_KEY </dev/tty
  printf '\n'
  if [ -n "$BOOTSTRAP_KEY" ]; then
    printf '%s\n' "$BOOTSTRAP_KEY" | python -m nisos --set-key
  else
    warn "no key -- routed commands will work, reasoned ones won't until you add one (menu key 'k')"
  fi
  unset BOOTSTRAP_KEY
else
  skip "local model present -- add a key later with menu key 'k' to go online"
fi

# ==========================================================================
step "Self-test"
python -m nisos --check
printf '\n'
printf '     %strying a typed command...%s\n' "$DIM" "$R"
python -m nisos --text "άναψε τον φακό" --dry-run 2>/dev/null | tail -1

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
step "Update notifications"
# Off by default and asked for explicitly. This is the only thing in nisos
# that touches the network, so it should be a decision, not a default.
if done_already updates; then
  skip "already chosen"
else
  printf '     %snisos can check once a day for a new release and put a\n' "$DIM"
  printf '     notification on your phone with an Install button.%s\n' "$R"
  printf '     %sIt is the only feature that uses the network.%s\n' "$DIM" "$R"
  printf '\n     Enable daily update checks? [y/N] '
  read -r yn </dev/tty || yn=n
  case "$yn" in
    [yY]*)
      if command -v termux-job-scheduler >/dev/null 2>&1; then
        termux-job-scheduler --job-id 1001 \
          --period-ms 86400000 --network any --persisted true \
          --script "$NISOS_HOME/scripts/update.sh" >/dev/null 2>&1 \
          && ok "daily check scheduled" \
          || warn "couldn't schedule -- use 'u' in the control panel instead"
      else
        warn "termux-job-scheduler not available -- use 'u' in the control panel"
      fi
      ;;
    *) ok "left off -- check manually with 'u' in the control panel" ;;
  esac
  mark updates
fi

# ==========================================================================
step "Releasing the wake lock"
# The install is over, so give the CPU permission to sleep again. Holding this
# is the biggest battery cost in the project and buys only a faster first
# command; scripts/nisos.sh re-acquires it around each turn and releases it
# again, which is where it actually belongs.
termux-wake-unlock 2>/dev/null && ok "released -- the phone can sleep again"   || warn "couldn't release; run termux-wake-unlock by hand"

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
