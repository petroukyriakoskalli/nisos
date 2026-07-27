#!/data/data/com.termux/files/usr/bin/bash
#
# Try to download prebuilt native binaries instead of compiling them.
#
#   exit 0 -- llama-server and whisper-cli are in ~/nisos/bin and they RUN
#   exit 1 -- caller should fall back to compiling from source
#
# This is what takes the install from ~50 minutes to ~10. The binaries are
# cross-compiled once by .github/workflows/android-binaries.yml and attached to
# each release; they are identical to what your phone would spend half an hour
# producing.
#
# Three things this is careful about:
#
#   * CHECKSUMS. Every download is verified against the SHA256SUMS in the same
#     release before it is allowed anywhere near your PATH.
#
#   * IT ACTUALLY RUNS. A binary built for the wrong ABI, or against a newer
#     Android API than your phone has, fails at exec time rather than download
#     time. Each one is executed once here, and a failure falls back to the
#     compile rather than leaving you with an install that looks fine and is not.
#
#   * NEVER WORSE THAN BEFORE. Any failure at all -- no network, no release, bad
#     checksum, won't exec -- returns 1 and the caller compiles as it always did.
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
BIN="$NISOS_HOME/bin"
TMP="$HOME/.nisos/prebuilt"
REPO="${NISOS_REPO_SLUG:-petroukyriakoskalli/nisos}"
API="https://api.github.com/repos/$REPO/releases/latest"

WANT="llama-server-android-arm64 whisper-cli-android-arm64"

say()  { printf '     %s\n' "$*"; }
nope() { printf '     prebuilt unavailable: %s\n' "$*"; rm -rf "$TMP"; exit 1; }

[ "${NISOS_NO_PREBUILT:-0}" = "1" ] && nope "disabled by NISOS_NO_PREBUILT"

# --------------------------------------------------------------------------
# Only arm64 phones are published. Everything else compiles.
# --------------------------------------------------------------------------
ARCH="$(uname -m 2>/dev/null || echo unknown)"
case "$ARCH" in
  aarch64|arm64) ;;
  *) nope "no prebuilt for $ARCH" ;;
esac

mkdir -p "$TMP" "$BIN"

# --------------------------------------------------------------------------
# Find the assets in the newest release.
# --------------------------------------------------------------------------
META="$TMP/release.json"
curl -sf --max-time 20 -H 'Accept: application/vnd.github+json' "$API" -o "$META" \
  || nope "couldn't reach GitHub"

get_url() {
  # Print the download URL for an asset by exact name, or nothing.
  python - "$1" <<'PY' 2>/dev/null
import json, sys, os
name = sys.argv[1]
with open(os.environ["META"]) as fh:
    data = json.load(fh)
for asset in data.get("assets", []):
    if asset.get("name") == name:
        print(asset.get("browser_download_url", ""))
        break
PY
}
export META

TAG="$(python -c "import json,os;print(json.load(open(os.environ['META'])).get('tag_name',''))" 2>/dev/null)"
[ -z "$TAG" ] && nope "no published release"

SUMS_URL="$(get_url SHA256SUMS)"
[ -z "$SUMS_URL" ] && nope "release $TAG has no prebuilt binaries"

say "found prebuilt binaries in $TAG"

# --------------------------------------------------------------------------
# Download, then verify against the checksums from the same release.
# --------------------------------------------------------------------------
curl -sfL --max-time 60 "$SUMS_URL" -o "$TMP/SHA256SUMS" || nope "checksum file failed"

for NAME in $WANT; do
  URL="$(get_url "$NAME")"
  [ -z "$URL" ] && nope "$NAME missing from the release"
  say "downloading $NAME"
  curl -sfL --retry 3 --max-time 300 "$URL" -o "$TMP/$NAME" || nope "$NAME download failed"
done

if command -v sha256sum >/dev/null 2>&1; then
  ( cd "$TMP" && grep -E "$(echo "$WANT" | tr ' ' '|')" SHA256SUMS | sha256sum -c --quiet - ) \
    || nope "CHECKSUM MISMATCH -- refusing to install"
  say "checksums verified"
else
  nope "no sha256sum available to verify with"
fi

# --------------------------------------------------------------------------
# Prove they execute on THIS device before trusting them.
# --------------------------------------------------------------------------
for NAME in $WANT; do
  chmod +x "$TMP/$NAME"
  # Capture the failure instead of swallowing it. "won't run on this device" on
  # its own is useless -- the loader's message is the whole diagnosis, and it
  # distinguishes a wrong ABI from a page-size mismatch from a missing library.
  if ! ERRTEXT="$("$TMP/$NAME" --version 2>&1)"; then
    printf '     %s said:\n' "$NAME"
    printf '       %s\n' "$(printf '%s' "$ERRTEXT" | head -3)"
    printf '       page size on this device: %s\n' "$(getconf PAGESIZE 2>/dev/null || echo unknown)"
    nope "$NAME downloaded but won't run here (see above)"
  fi
done
say "both binaries run here"

# --------------------------------------------------------------------------
install -m 0755 "$TMP/llama-server-android-arm64" "$BIN/llama-server"
install -m 0755 "$TMP/whisper-cli-android-arm64"  "$BIN/whisper-cli"
rm -rf "$TMP"

say "installed to $BIN -- skipping the 30-minute compile"
exit 0
