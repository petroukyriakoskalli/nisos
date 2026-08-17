#!/usr/bin/env bash
#
# Install the APK on an emulator, start it, photograph it, and prove it is alive.
#
# This is the gap that let two layout bugs and a signing bug reach a phone: CI
# proved the Kotlin compiled and that `core/` behaved, and then published an APK
# that nothing had ever installed or launched. "It builds" and "it runs" are
# different claims and only one of them was being made.
#
# ⚠️ What this cannot test is the phone's own policy. Samsung's Auto Blocker, the
# per-source "install unknown apps" permission and Play Protect all refuse
# perfectly valid APKs, and an emulator has none of them. Green here means the
# APK is installable and starts -- not that a given handset will allow it.
set -euo pipefail

APK=${1:-nisos.apk}
PKG=app.nisos
ACTIVITY=$PKG/$PKG.ui.MainActivity

launch() {
    adb shell am force-stop $PKG || true
    adb logcat -c
    adb shell am start -W -n "$ACTIVITY"
    # Compose needs a moment to inflate, and a crash on the first frame is
    # exactly what this is looking for -- so look after it has had one.
    sleep 10
}

alive() {
    # Tested on pidof's *output*, not its exit status: the status is not reliably
    # propagated back through `adb shell`, which made run 20 report a healthy app
    # as dead.
    local pid
    pid=$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
    if [ -z "$pid" ]; then
        echo "::error::$PKG is not running -- $1"
        adb logcat -d -v brief | grep -iE "nisos|FATAL|AndroidRuntime" | tail -60
        return 1
    fi
    echo "  pid $pid"
    if adb logcat -d | grep -E "FATAL EXCEPTION|E AndroidRuntime" > /tmp/fatal.txt; then
        echo "::error::a fatal exception was logged -- $1"
        cat /tmp/fatal.txt
        return 1
    fi
    echo "  no fatal exceptions"
}

echo "::group::Install"
adb wait-for-device
# -r so a rerun replaces rather than colliding. A failure here is the real prize:
# INSTALL_PARSE_FAILED_* and INSTALL_FAILED_INVALID_APK are exactly what a phone
# reports as an unexplained "App not installed".
adb install -r "$APK"
adb shell dumpsys package $PKG | grep -m1 versionName | sed 's/^/  /' || true
echo "::endgroup::"

# ---------------------------------------------------------------------------
# First run, with nothing granted. This is the check that matters.
#
# The previous version granted all six permissions *before* launching, to keep
# the permission dialog out of the screenshot. That is precisely what hid a
# guaranteed crash: `RequestMultiplePermissions` has a synchronous short-circuit
# when every permission is already held, so `launch()` returned immediately and
# never called `requestPermissions` -- the one line that was broken. The test
# passed while the app crashed on every real first run.
#
# So the first launch is now a genuine first run: fresh install, nothing granted,
# permission dialog and all. Tidiness comes second.
# ---------------------------------------------------------------------------
echo "::group::First run, no permissions granted"
launch
adb exec-out screencap -p > screen-first-run.png
echo "  first run: $(stat -c%s screen-first-run.png) bytes"
alive "crashed on a first run with no permissions granted"
echo "::endgroup::"

echo "::group::Grant the runtime permissions"
for p in RECORD_AUDIO READ_CALENDAR WRITE_CALENDAR READ_CONTACTS SEND_SMS READ_SMS; do
    adb shell pm grant $PKG android.permission.$p 2>/dev/null \
        && echo "  granted $p" \
        || echo "  could not grant $p (not fatal)"
done
echo "::endgroup::"

echo "::group::Relaunch, and photograph it"
launch
adb exec-out screencap -p > screen-main.png
echo "  main screen: $(stat -c%s screen-main.png) bytes"
alive "crashed after the permissions were granted"
echo "::endgroup::"

# Settings is reached by tapping the header. Found by asking the UI where it is
# rather than guessing a percentage of the screen -- the first attempt tapped
# 82%/6% at a gear sitting nearer 92%/10%, missed, and produced two
# byte-identical screenshots. A silent miss is the worst outcome for a check
# whose entire job is to show what a screen looks like.
tap_text() {
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
    adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || true
    local spot
    spot=$(python3 - "$1" <<'PY'
import re, sys
want = sys.argv[1]
try:
    xml = open("/tmp/ui.xml", encoding="utf-8").read()
except OSError:
    sys.exit(0)
for node in re.findall(r"<node[^>]*>", xml):
    text = re.search(r'text="([^"]*)"', node)
    desc = re.search(r'content-desc="([^"]*)"', node)
    haystack = (text.group(1) if text else "") + (desc.group(1) if desc else "")
    if want.lower() in haystack.lower():
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
    )
    if [ -z "$spot" ]; then
        echo "::warning::could not find '$1' on screen -- not tapping"
        return 1
    fi
    echo "  tapping '$1' at $spot"
    adb shell input tap $spot
}

echo "::group::Settings"
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || true
echo "  on screen: $(python3 -c "
import re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
print(' | '.join(dict.fromkeys(re.findall(r'text=\"([^\"]+)\"', xml))))
" 2>/dev/null || echo '(no dump)')"

if tap_text "router only"; then
    sleep 3
    adb exec-out screencap -p > screen-settings.png
    echo "  settings: $(stat -c%s screen-settings.png) bytes"
    if cmp -s screen-main.png screen-settings.png; then
        echo "::error::the settings screenshot is identical to the main one -- the tap did nothing"
        exit 1
    fi
    alive "crashed on the settings screen"
fi
echo "::endgroup::"

echo "smoke test passed"
