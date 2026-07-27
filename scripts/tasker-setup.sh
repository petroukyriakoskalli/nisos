#!/data/data/com.termux/files/usr/bin/bash
#
# Prepare the Termux side of the Tasker bridge.
#
#   bash scripts/tasker-setup.sh
#
# There are two directions across this bridge and they need different things:
#
#   nisos -> Tasker   an outbound broadcast, for the two actions Android will
#                     not let a Termux process do itself (Do Not Disturb, and
#                     reading the calendar). Needs a writable shared directory
#                     for Tasker to leave its answer in.
#
#   Tasker -> nisos   a hardware button running one turn. Needs a script in
#                     ~/.termux/tasker/, because that is the only directory
#                     the Termux:Tasker plugin is allowed to run things from.
#
# This sets up both, then prints the taps you still have to do by hand in
# Tasker -- there is no way to configure another app's profiles from a shell.
#
set -uo pipefail

NISOS_HOME="${NISOS_HOME:-$HOME/nisos}"
TASKER_DIR="$HOME/.termux/tasker"
SHARED="/sdcard/nisos"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
dim()  { printf '\033[2m%s\033[0m\n' "$1"; }
ok()   { printf '\033[32m  ok\033[0m  %s\n' "$1"; }
warn() { printf '\033[33m  !!\033[0m  %s\n' "$1"; }

printf '\n'
bold "Termux side"

# --------------------------------------------------------------------------
# Shared storage: where Tasker leaves answers.
#
# It has to be /sdcard. Termux's home is private app data -- Tasker cannot
# write into it without root, so the obvious-looking ~/.nisos/calendar.json
# can never work no matter how correct the Tasker task is.
if mkdir -p "$SHARED" 2>/dev/null && touch "$SHARED/.w" 2>/dev/null; then
  rm -f "$SHARED/.w"
  ok "$SHARED is writable"
else
  warn "cannot write $SHARED -- run: termux-setup-storage"
  warn "then grant the permission and run this again."
fi

# --------------------------------------------------------------------------
# The script Tasker runs to take one turn.
mkdir -p "$TASKER_DIR"
cat > "$TASKER_DIR/nisos-turn.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
# Installed by scripts/tasker-setup.sh -- do not edit, it gets overwritten.
#
# Termux:Tasker will only run scripts inside ~/.termux/tasker, so this is a
# two-line shim to the real launcher. Keep it that way: putting logic here
# means it is not under version control.
exec bash "$NISOS_HOME/scripts/nisos.sh" "\$@"
EOF
chmod 700 "$TASKER_DIR/nisos-turn.sh"
ok "$TASKER_DIR/nisos-turn.sh installed"

if [ ! -x "$NISOS_HOME/scripts/nisos.sh" ]; then
  chmod +x "$NISOS_HOME/scripts/nisos.sh" 2>/dev/null || true
fi

# --------------------------------------------------------------------------
printf '\n'
bold "Now do these in Tasker (nothing else can do them for you)"
cat <<'EOF'

  1. Tasker -> three-dot menu -> Preferences -> Misc
     Allow External Access                              ON

  2. Import the outbound task:
     Tasks tab -> three-dot -> Import Task -> nisos/tasker/NisosAction.tsk.xml
     Do the same for NisosDnd.tsk.xml.

  3. Give Tasker the permissions it needs to do the work:
     Android Settings -> Apps -> Tasker -> Permissions
       Calendar     Allow          (for calendar.next)
       Files        All files      (to write /sdcard/nisos)
     Android Settings -> Notifications -> Do Not Disturb access
       Tasker       Allow          (for dnd.on)

  4. Button trigger -- Profile -> Event -> Plugin -> Termux:Tasker
       Executable        nisos-turn.sh
       Arguments         (leave empty)
       Terminal          unchecked      <- important, or a terminal pops up
       Stdout            unchecked
     ...then attach whichever trigger you want. Recipes are in
     tasker/README.md; the short version is that a long-press of volume-up
     works with the screen on, and Samsung's own side-key double-press is the
     only one that reliably works with the phone locked.

EOF

bold "Then check it end to end"
dim "  bash $NISOS_HOME/scripts/tasker-test.sh"
printf '\n'
