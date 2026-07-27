#!/data/data/com.termux/files/usr/bin/bash
#
# Reclaim the build scrap, and stop the install growing forever.
#
# A naive build of this project leaves about 7 GB on the phone. The finished
# thing needs roughly 3 GB, and 2.5 GB of that is the model. Everything else is
# compiler output that can go.
#
# Safe to run more than once. Run it after scripts/install.sh, and again after
# any rebuild.
#
set -euo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
BUILD="$NISOS_HOME/.build"
LOG="$HOME/.nisos/nisos.log"
BUDGET_KB=$((4 * 1024 * 1024))   # 4 GB -- warn above this

say() { printf '\n\033[1;33m==>\033[0m %s\n' "$*"; }
kb()  { du -sk "$1" 2>/dev/null | cut -f1 || echo 0; }

before=$(kb "$HOME")

# --------------------------------------------------------------------------
say "Verifying the binaries are self-contained before deleting anything"
# install.sh builds with -DBUILD_SHARED_LIBS=OFF. If someone rebuilt without
# it, the binaries still link against .so files inside the build tree and
# deleting it would break them silently. Check first.
for binary in "$NISOS_HOME/bin/llama-server" "$NISOS_HOME/bin/whisper-cli"; do
  [ -x "$binary" ] || { echo "ERROR: $binary missing -- run install.sh first" >&2; exit 1; }
  if ldd "$binary" 2>/dev/null | grep -q "$BUILD"; then
    echo "ERROR: $binary links to libraries inside $BUILD." >&2
    echo "       Rebuild with -DBUILD_SHARED_LIBS=OFF before cleaning." >&2
    exit 1
  fi
done

# --------------------------------------------------------------------------
say "Deleting build trees"
rm -rf "$BUILD"

say "Emptying the package cache"
apt clean || true

say "Removing the compiler"
# ~645 MB. Reinstall with `pkg install clang cmake git` if you ever rebuild.
pkg uninstall -y clang cmake git 2>/dev/null || true

# --------------------------------------------------------------------------
say "Installing the nightly log trim"
# One of the three things that quietly fills a phone. The other two are
# timestamped recordings (nisos always overwrites one file) and
# llama-server --prompt-cache (never use it).
mkdir -p "$HOME/.termux/boot" "$(dirname "$LOG")"
cat > "$HOME/.nisos/trim-log.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
# Keep the last 2000 lines of the nisos log and nothing more.
[ -f "$LOG" ] || exit 0
tail -n 2000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
EOF
chmod +x "$HOME/.nisos/trim-log.sh"
echo "    installed ~/.nisos/trim-log.sh -- call it from a daily Tasker task"

# --------------------------------------------------------------------------
say "Result"
after=$(kb "$HOME")
printf '    before  %6s MB\n' "$((before / 1024))"
printf '    after   %6s MB\n' "$((after / 1024))"
printf '    freed   %6s MB\n' "$(((before - after) / 1024))"

if [ "$after" -gt "$BUDGET_KB" ]; then
  printf '\n    \033[1;31mOver budget.\033[0m Largest directories:\n'
  du -sh "$HOME"/* 2>/dev/null | sort -h | tail -8 | sed 's/^/      /'
fi

cat <<'EOF'

    True total, including the Termux app itself:
      Settings -> Apps -> Termux -> Storage

    Everything lives in Termux's private sandbox, so uninstalling the app
    reclaims every byte -- nothing is left in Downloads or your Files app.

EOF
