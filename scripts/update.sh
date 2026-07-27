#!/data/data/com.termux/files/usr/bin/bash
#
# Update checking and installation.
#
#   update.sh check      quiet -- exit 0 if an update exists, 1 if not
#   update.sh notify     check, and raise an Android notification if so
#   update.sh install    install the latest release
#   update.sh rollback   undo the last install
#   update.sh status     one line for the control panel banner
#
# Design notes, because updating an offline assistant is a contradiction worth
# handling carefully:
#
#   * OPT-IN. The check is the only thing in nisos that touches the network.
#     It is off unless [update] check = true, it never blocks the assistant,
#     and it fails silently. You can ignore it forever and update by hand.
#
#   * RELEASES, NOT main. Updating to whatever happens to be on main means
#     users get half-finished work. This tracks tagged releases only.
#
#   * GIT, NOT A DOWNLOADED SCRIPT. Fetching a shell script and running it is
#     how supply-chain attacks work. Everything here goes through git over
#     HTTPS against a known remote, so nothing executes that is not already a
#     reviewable commit in a public repo.
#
#   * NEVER SILENT. Nothing installs without you tapping Install or pressing
#     a key. A phone that quietly changed its own behaviour overnight is worse
#     than one that is slightly out of date.
#
#   * REVERSIBLE. The previous checkout is kept so a bad update on a phone you
#     cannot easily reach is a one-key fix.
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
STATE_DIR="$HOME/.nisos"
CACHE="$STATE_DIR/update.json"
PREV="$HOME/.nisos/previous"
REPO="petroukyriakoskalli/nisos"
API="https://api.github.com/repos/$REPO/releases/latest"

mkdir -p "$STATE_DIR"

# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

local_version() {
  # Read __version__ out of the package without importing it.
  sed -n 's/^__version__ = "\(.*\)"/\1/p' "$NISOS_HOME/nisos/__init__.py" 2>/dev/null | head -1
}

latest_release() {
  # Fetch the newest tagged release. Prints "tag<TAB>name<TAB>notes-first-line".
  # Short timeout: this must never make the assistant feel slow.
  curl -sf --max-time 6 -H 'Accept: application/vnd.github+json' "$API" 2>/dev/null \
  | python -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
tag = (d.get("tag_name") or "").lstrip("v")
if not tag:
    sys.exit(1)
notes = (d.get("body") or "").strip().splitlines()
print("\t".join([tag, d.get("name") or tag, notes[0] if notes else ""]))
' 2>/dev/null
}

newer() {
  # True if $1 is a higher version than $2, comparing numerically per part.
  # Avoids the classic trap where "0.10.0" sorts below "0.9.0" as a string.
  [ "$1" = "$2" ] && return 1
  [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]
}

is_git() { [ -d "$NISOS_HOME/.git" ]; }

# --------------------------------------------------------------------------
# check -- exit 0 when an update is available
# --------------------------------------------------------------------------

cmd_check() {
  local here there line
  here="$(local_version)"
  line="$(latest_release)" || return 1
  [ -z "$line" ] && return 1

  there="$(printf '%s' "$line" | cut -f1)"
  local name notes
  name="$(printf '%s' "$line" | cut -f2)"
  notes="$(printf '%s' "$line" | cut -f3)"

  # Cache regardless, so `status` is instant and works offline.
  printf '{"local":"%s","latest":"%s","name":"%s","notes":"%s"}\n' \
    "$here" "$there" "$name" "$notes" > "$CACHE"

  newer "$there" "$here"
}

# --------------------------------------------------------------------------
# notify -- the Android pop-up, with a working Install button
# --------------------------------------------------------------------------

cmd_notify() {
  cmd_check || return 1
  command -v termux-notification >/dev/null 2>&1 || return 1

  local there notes
  there="$(sed -n 's/.*"latest":"\([^"]*\)".*/\1/p' "$CACHE")"
  notes="$(sed -n 's/.*"notes":"\([^"]*\)".*/\1/p' "$CACHE")"

  termux-notification \
    --id nisos-update \
    --title "nisos $there available" \
    --content "${notes:-Tap Install to update.}" \
    --priority low \
    --button1 "Install" \
    --button1-action "bash $NISOS_HOME/scripts/update.sh install" \
    --button2 "Later" \
    --button2-action "termux-notification-remove nisos-update" \
    2>/dev/null
}

# --------------------------------------------------------------------------
# install
# --------------------------------------------------------------------------

cmd_install() {
  if ! is_git; then
    echo "Not a git checkout -- re-run scripts/bootstrap.sh to update."
    return 1
  fi

  local here there
  here="$(local_version)"
  there="$(latest_release | cut -f1)"
  if [ -z "$there" ]; then echo "Couldn't reach GitHub."; return 1; fi
  if ! newer "$there" "$here"; then echo "Already on $here."; return 0; fi

  echo "Updating $here -> $there"

  # Refuse to clobber local edits rather than losing someone's tweaks.
  if [ -n "$(git -C "$NISOS_HOME" status --porcelain --untracked-files=no)" ]; then
    echo "You have uncommitted changes. Commit or stash them first:"
    git -C "$NISOS_HOME" status --short
    return 1
  fi

  # Snapshot for rollback. Config, models and logs are gitignored and live
  # outside the checkout's tracked files, so they survive either way.
  rm -rf "$PREV"
  mkdir -p "$PREV"
  echo "$here" > "$PREV/version"
  git -C "$NISOS_HOME" rev-parse HEAD > "$PREV/commit" 2>/dev/null

  git -C "$NISOS_HOME" fetch --tags --quiet origin || { echo "fetch failed"; return 1; }
  if ! git -C "$NISOS_HOME" checkout --quiet "v$there" 2>/dev/null; then
    echo "Couldn't check out v$there"; return 1
  fi

  echo "Now on $(local_version)."

  # Restart the model so the new code is what is serving.
  pkill -f 'llama-server' 2>/dev/null
  bash "$NISOS_HOME/scripts/nisos.sh" --text "ok" >/dev/null 2>&1 &

  command -v termux-notification-remove >/dev/null 2>&1 \
    && termux-notification-remove nisos-update 2>/dev/null
  return 0
}

# --------------------------------------------------------------------------
# rollback
# --------------------------------------------------------------------------

cmd_rollback() {
  if [ ! -f "$PREV/commit" ]; then
    echo "Nothing to roll back to."
    return 1
  fi
  local commit; commit="$(cat "$PREV/commit")"
  echo "Rolling back to $(cat "$PREV/version") ($commit)"
  git -C "$NISOS_HOME" checkout --quiet "$commit" || { echo "rollback failed"; return 1; }
  pkill -f 'llama-server' 2>/dev/null
  echo "Done. Now on $(local_version)."
}

# --------------------------------------------------------------------------
# status -- one cached line for the control panel, no network
# --------------------------------------------------------------------------

cmd_status() {
  [ -f "$CACHE" ] || { echo ""; return 0; }
  local here there
  here="$(sed -n 's/.*"local":"\([^"]*\)".*/\1/p' "$CACHE")"
  there="$(sed -n 's/.*"latest":"\([^"]*\)".*/\1/p' "$CACHE")"
  if [ -n "$there" ] && newer "$there" "$here"; then
    echo "$there"
  else
    echo ""
  fi
}

# --------------------------------------------------------------------------

case "${1:-status}" in
  check)    cmd_check    ;;
  notify)   cmd_notify   ;;
  install)  cmd_install  ;;
  rollback) cmd_rollback ;;
  status)   cmd_status   ;;
  *) echo "usage: update.sh {check|notify|install|rollback|status}"; exit 2 ;;
esac
