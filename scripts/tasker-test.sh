#!/data/data/com.termux/files/usr/bin/bash
#
# Prove the Tasker bridge works, one link at a time.
#
#   bash scripts/tasker-test.sh
#
# The bridge has four things that can independently be wrong -- the broadcast
# not arriving, Tasker refusing external access, the task not existing, and the
# answer file not being writable. A single "it doesn't work" tells you nothing
# about which. So this walks them in order and stops at the first one that
# fails, with the fix for that specific link.
#
# Nothing here needs the model, the microphone, or the web UI.
#
set -uo pipefail

TASK="${NISOS_TASKER_TASK:-NisosAction}"
SHARED="/sdcard/nisos"
ANSWER="$SHARED/calendar.json"

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
dim()   { printf '\033[2m%s\033[0m\n'  "$1"; }

step=0
say() { step=$((step + 1)); printf '\n%d. %s\n' "$step" "$1"; }

fail() {
  red "   FAILED"
  printf '   %s\n' "$1"
  exit 1
}

broadcast() {
  # $1 = action name, $2 = JSON payload
  am broadcast --user 0 \
    -a net.dinglisch.android.tasker.ACTION_TASK \
    -e task_name "$TASK" \
    -e par1 "$1" \
    -e par2 "${2:-{\}}" 2>&1
}

# --------------------------------------------------------------------------
say "Can Termux send broadcasts at all?"
if ! command -v am >/dev/null 2>&1; then
  fail "no 'am' command. Run: pkg install termux-tools"
fi
green "   yes"

# --------------------------------------------------------------------------
say "Is shared storage writable? (Tasker writes its answer there)"
if ! mkdir -p "$SHARED" 2>/dev/null; then
  fail "cannot create $SHARED. Run: termux-setup-storage, then grant the permission."
fi
if ! touch "$SHARED/.nisos-write-test" 2>/dev/null; then
  fail "cannot write to $SHARED. Run: termux-setup-storage"
fi
rm -f "$SHARED/.nisos-write-test"
green "   yes -- $SHARED"

# --------------------------------------------------------------------------
say "Does the broadcast reach Tasker? (expect a 'nisos: ping ok' flash)"
rm -f "$ANSWER"
out="$(broadcast ping '{}')"
case "$out" in
  *"Broadcast completed"*) ;;
  *) dim "   am said: $out" ;;
esac

for _ in $(seq 1 30); do
  [ -f "$ANSWER" ] && break
  sleep 0.2
done

if [ ! -f "$ANSWER" ]; then
  red "   FAILED"
  cat <<'EOF'
   Nothing came back. Work down this list -- it is almost always the first one:

     a. Tasker -> ... menu -> Preferences -> Misc -> Allow External Access  (ON)
     b. A task named NisosAction must exist. Import tasker/NisosAction.tsk.xml,
        or build it by hand -- see tasker/README.md.
     c. Tasker needs "All files access" to write /sdcard/nisos:
        Android Settings -> Apps -> Tasker -> Permissions -> Files.
     d. Battery optimisation must be off for Tasker, or it will not wake.
EOF
  exit 1
fi
green "   yes -- $(cat "$ANSWER")"

# --------------------------------------------------------------------------
say "Do Not Disturb"
broadcast dnd.on '{"until": null}' >/dev/null
dim "   sent. Check the status bar -- DND should be on now."
dim "   If nothing happened, the NisosDnd task is missing or set to the wrong mode."

# --------------------------------------------------------------------------
say "Next calendar entry"
rm -f "$ANSWER"
broadcast calendar.next '{}' >/dev/null
for _ in $(seq 1 40); do
  [ -f "$ANSWER" ] && break
  sleep 0.2
done

if [ ! -f "$ANSWER" ]; then
  fail "no answer written. See tasker/README.md -- the calendar branch is the
   one part of this that could not be verified without a device."
fi

answer="$(cat "$ANSWER")"
case "$answer" in
  *'"summary": "nothing"'*|*'"summary":"nothing"'*)
    dim "   answered, but found no entry: $answer"
    dim "   Either there is genuinely nothing in the next 7 days, or Tasker is"
    dim "   missing the Calendar permission (Settings -> Apps -> Tasker)."
    ;;
  *)
    green "   $answer"
    ;;
esac

# --------------------------------------------------------------------------
printf '\n'
green "Bridge is up. Now try it for real:"
dim "   python -m nisos --text 'what's next in my calendar'"
printf '\n'
