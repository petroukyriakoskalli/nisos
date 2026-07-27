#!/data/data/com.termux/files/usr/bin/bash
#
# Get the two native binaries nisos needs, plus the Whisper weights.
#
# Prefers prebuilt binaries from the latest GitHub release -- that is a ~1
# minute download instead of a 20-30 minute compile, and it means the 600 MB
# clang/cmake toolchain never has to be installed at all.
#
# Falls back to compiling from source whenever the download can't be trusted:
# no network, no release, checksum mismatch, or a binary that won't exec on this
# device. The fallback path is exactly what it always was, so this can only be
# faster, never more fragile.
#
# Run once, on wi-fi:  bash scripts/install.sh
#
set -euo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
BIN="$NISOS_HOME/bin"
MODELS="$NISOS_HOME/models"
BUILD="$NISOS_HOME/.build"
JOBS="$(nproc 2>/dev/null || echo 4)"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"

# Stable, well-known location for the GGML Whisper weights. Fetched directly so
# that skipping the whisper.cpp checkout doesn't also lose the download script.
WHISPER_MODEL="ggml-small-q5_1.bin"
WHISPER_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$WHISPER_MODEL"

say() { printf '\n\033[1;33m==>\033[0m %s\n' "$*"; }

mkdir -p "$BIN" "$MODELS" "$HOME/.nisos"

# --------------------------------------------------------------------------
say "Checking you have the right Termux"
# The Play Store build is a dead fork stuck on an old Android API and its
# package repositories no longer resolve. This is the single most common way
# the whole install fails on step one, so check before doing any work.
if ! command -v pkg >/dev/null 2>&1; then
  echo "ERROR: this is not Termux." >&2
  exit 1
fi
if [ ! -d /data/data/com.termux/files/usr ]; then
  echo "ERROR: unexpected Termux layout -- install Termux from F-Droid." >&2
  exit 1
fi

# --------------------------------------------------------------------------
say "Holding a wake lock"
# Without this One UI suspends the session mid-install, and later kills the
# model server so your first command of the morning waits nine seconds.
termux-wake-lock 2>/dev/null || true

# --------------------------------------------------------------------------
say "Looking for prebuilt binaries"
NEED_COMPILE=1
if bash "$SCRIPTS/fetch-binaries.sh"; then
  NEED_COMPILE=0
fi

# --------------------------------------------------------------------------
if [ "$NEED_COMPILE" = "1" ]; then
  say "Compiling from source (20-40 minutes)"
  echo "     No usable prebuilt binaries, so this phone builds its own."

  # Not silenced: when this fails it is nearly always a mismatched-ABI Termux
  # (see the pkg upgrade note in bootstrap.sh), and hiding apt's output turns a
  # one-line diagnosis into a mystery.
  if ! apt install -y git cmake clang binutils; then
    echo "" >&2
    echo "ERROR: couldn't install the toolchain." >&2
    echo "       This is almost always a partially-upgraded Termux. Run:" >&2
    echo "" >&2
    echo "         apt update && apt full-upgrade -y" >&2
    echo "" >&2
    echo "       then run this script again." >&2
    exit 1
  fi
  mkdir -p "$BUILD"

  # ---- llama.cpp ---------------------------------------------------------
  # BUILD_SHARED_LIBS=OFF matters: scripts/postbuild.sh deletes this whole tree
  # afterwards, and a binary linked against .so files inside it would break.
  # Only llama-server is ever used -- nisos talks to it over HTTP.
  say "Building llama-server"
  [ -d "$BUILD/llama.cpp" ] || \
    git clone --depth 1 https://github.com/ggml-org/llama.cpp "$BUILD/llama.cpp"
  cmake -S "$BUILD/llama.cpp" -B "$BUILD/llama.cpp/build" \
        -DGGML_NATIVE=ON \
        -DBUILD_SHARED_LIBS=OFF \
        -DLLAMA_CURL=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF
  cmake --build "$BUILD/llama.cpp/build" -j"$JOBS" --target llama-server
  find "$BUILD/llama.cpp/build" -name llama-server -type f -exec cp {} "$BIN/" \;

  # ---- whisper.cpp -------------------------------------------------------
  # Without an explicit target this builds every example whisper.cpp ships --
  # bench, stream, talk, the lot. nisos uses whisper-cli only.
  say "Building whisper-cli"
  [ -d "$BUILD/whisper.cpp" ] || \
    git clone --depth 1 https://github.com/ggml-org/whisper.cpp "$BUILD/whisper.cpp"
  cmake -S "$BUILD/whisper.cpp" -B "$BUILD/whisper.cpp/build" \
        -DBUILD_SHARED_LIBS=OFF \
        -DWHISPER_BUILD_TESTS=OFF \
        -DWHISPER_BUILD_SERVER=OFF
  cmake --build "$BUILD/whisper.cpp/build" -j"$JOBS" --target whisper-cli
  find "$BUILD/whisper.cpp/build" -name whisper-cli -type f -exec cp {} "$BIN/" \;

  say "Stripping binaries"
  strip "$BIN"/* 2>/dev/null || true
fi

# --------------------------------------------------------------------------
say "Whisper weights (multilingual)"
# small-q5_1, NOT small.en. Downloading the English-only weights is the easiest
# way to build this entire project and then discover it doesn't speak Greek.
if [ -f "$MODELS/$WHISPER_MODEL" ]; then
  echo "     already present"
else
  curl -L -C - --retry 3 --progress-bar -o "$MODELS/$WHISPER_MODEL" "$WHISPER_URL" \
    || { echo "ERROR: whisper weights download failed -- re-run to resume" >&2; exit 1; }
fi

# --------------------------------------------------------------------------
say "Done"
printf '     llama-server  %s\n' "$([ -x "$BIN/llama-server" ] && echo ok || echo MISSING)"
printf '     whisper-cli   %s\n' "$([ -x "$BIN/whisper-cli" ] && echo ok || echo MISSING)"
if [ "$NEED_COMPILE" = "0" ]; then
  printf '\n     Used prebuilt binaries -- no compiler installed, nothing to clean up.\n'
else
  printf '\n     Compiled locally. Run scripts/postbuild.sh afterwards to reclaim ~4 GB.\n'
fi
