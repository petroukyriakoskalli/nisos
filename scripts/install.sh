#!/data/data/com.termux/files/usr/bin/bash
#
# nisos installer for Termux on Android.
#
# Builds llama.cpp and whisper.cpp from source, statically, so that
# scripts/postbuild.sh can delete the entire build tree afterwards without
# breaking the binaries. That one flag (-DBUILD_SHARED_LIBS=OFF) is the
# difference between a 3 GB install and a 7 GB one.
#
# Run once, on wi-fi:  bash scripts/install.sh
#
set -euo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
BIN="$NISOS_HOME/bin"
MODELS="$NISOS_HOME/models"
BUILD="$NISOS_HOME/.build"
JOBS="$(nproc 2>/dev/null || echo 4)"

say() { printf '\n\033[1;33m==>\033[0m %s\n' "$*"; }

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
say "Installing the toolchain"
pkg update -y
pkg install -y git cmake clang binutils python termux-api

mkdir -p "$BIN" "$MODELS" "$BUILD" "$HOME/.nisos"

# --------------------------------------------------------------------------
say "Holding a wake lock"
# Without this, One UI suspends the whole session within minutes and your first
# command of the morning waits nine seconds for the model to reload.
termux-wake-lock || true

# --------------------------------------------------------------------------
say "Building llama.cpp (static)"
if [ ! -d "$BUILD/llama.cpp" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp "$BUILD/llama.cpp"
fi
cmake -S "$BUILD/llama.cpp" -B "$BUILD/llama.cpp/build" \
      -DGGML_NATIVE=ON \
      -DBUILD_SHARED_LIBS=OFF \
      -DLLAMA_CURL=OFF
cmake --build "$BUILD/llama.cpp/build" -j"$JOBS" --target llama-server llama-cli
cp "$BUILD/llama.cpp/build/bin/llama-server" "$BIN/"
cp "$BUILD/llama.cpp/build/bin/llama-cli"    "$BIN/"

# --------------------------------------------------------------------------
say "Building whisper.cpp (static)"
if [ ! -d "$BUILD/whisper.cpp" ]; then
  git clone --depth 1 https://github.com/ggml-org/whisper.cpp "$BUILD/whisper.cpp"
fi
cmake -S "$BUILD/whisper.cpp" -B "$BUILD/whisper.cpp/build" \
      -DBUILD_SHARED_LIBS=OFF
cmake --build "$BUILD/whisper.cpp/build" -j"$JOBS"
cp "$BUILD/whisper.cpp/build/bin/whisper-cli" "$BIN/"

# --------------------------------------------------------------------------
say "Fetching Whisper weights (multilingual)"
# small-q5_1, NOT small.en. Downloading the English-only weights is the easiest
# way to build this entire project and then discover it doesn't speak Greek.
( cd "$BUILD/whisper.cpp" && bash ./models/download-ggml-model.sh small-q5_1 )
cp "$BUILD/whisper.cpp/models/ggml-small-q5_1.bin" "$MODELS/"

# --------------------------------------------------------------------------
say "Stripping binaries"
strip "$BIN"/* 2>/dev/null || true

# --------------------------------------------------------------------------
say "Done"
cat <<'EOF'

Still to do, by hand:

  1. Download a model on wi-fi and put it in ~/nisos/models/
     Recommended: Qwen3-4B-Instruct-Q4_K_M.gguf   (2.5 GB)

  2. Install the Greek offline packs on the phone:
       Settings -> Google -> All services -> Voice
         -> Offline speech recognition -> add Ελληνικά
       Settings -> General management -> Text-to-speech
         -> install the Greek voice
     Then test with the phone in AIRPLANE MODE. Without the packs it fails
     silently by going online, which defeats the entire point.

  3. Exempt Termux from battery optimisation:
       Settings -> Apps -> Termux -> Battery -> Unrestricted

  4. Start the model:
       llama-server -m ~/nisos/models/Qwen3-4B-Q4_K_M.gguf \
                    --port 8080 -t 6 -c 4096 &

  5. Check everything:
       python -m nisos --check

  6. Reclaim about 4 GB of build scrap:
       bash scripts/postbuild.sh

EOF
