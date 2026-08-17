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

echo "::group::Install"
adb wait-for-device
# -r so a rerun replaces rather than colliding. A failure here is the real prize:
# INSTALL_PARSE_FAILED_* and INSTALL_FAILED_INVALID_APK are exactly what a phone
# reports as an unexplained "App not installed".
adb install -r "$APK"
adb shell dumpsys package $PKG | grep -m1 versionName | sed 's/^/  /' || true
echo "::endgroup::"

echo "::group::Grant the runtime permissions"
# Granted up front for two reasons. The app asks for all five in a LaunchedEffect
# on first frame, so without this the permission dialog is what the screenshot
# photographs instead of the app. And a dialog on top makes "is our activity
# resumed?" answer no for a reason that has nothing to do with the app.
for p in RECORD_AUDIO READ_CALENDAR WRITE_CALENDAR READ_CONTACTS SEND_SMS READ_SMS; do
    adb shell pm grant $PKG android.permission.$p 2>/dev/null \
        && echo "  granted $p" \
        || echo "  could not grant $p (not fatal)"
done
echo "::endgroup::"

echo "::group::Launch"
adb logcat -c
adb shell am force-stop $PKG || true
adb shell am start -W -n "$ACTIVITY"
# Compose needs a moment to inflate, and a crash on the first frame is precisely
# what this is looking for -- so look after it has had one.
sleep 10
echo "::endgroup::"

# Photographs FIRST, before anything can fail.
#
# The previous version put this after the liveness check, so the one run where
# the check went wrong produced no picture at all -- the diagnostic was gated
# behind the assertion it was supposed to explain. Never again.
echo "::group::Screenshots"
adb exec-out screencap -p > screen-main.png
echo "  main screen: $(stat -c%s screen-main.png) bytes"

# Settings is reached by tapping the header. Found by asking the UI where it is
# rather than by guessing a percentage of the screen -- the first attempt tapped
# 82%/6% at a gear sitting nearer 92%/10%, missed, and produced two byte-identical
# screenshots. A silent miss is the worst outcome for a check whose entire job is
# to show you what a screen looks like.
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

# Log what is actually on screen, as text. Cheap, and it makes "did the screen
# I expected render?" answerable without eyeballing a PNG.
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || true
echo "  on screen: $(python3 -c "
import re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
seen = [t for t in re.findall(r'text=\"([^\"]+)\"', xml)]
print(' | '.join(dict.fromkeys(seen)))
" 2>/dev/null || echo '(no dump)')"

if tap_text "router only"; then
    sleep 3
    adb exec-out screencap -p > screen-settings.png
    echo "  settings: $(stat -c%s screen-settings.png) bytes"
    if cmp -s screen-main.png screen-settings.png; then
        echo "::error::the settings screenshot is identical to the main one -- the tap did nothing"
        exit 1
    fi
fi
echo "::endgroup::"

echo "::group::Is it alive?"
# Tested on pidof's *output*, not its exit status. The exit status is not
# reliably propagated back through `adb shell`, which is what failed run 20:
# it reported the app as dead while the launch had returned `Status: ok` and
# logcat held no exception at all.
PID=$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
if [ -z "$PID" ]; then
    echo "::error::$PKG is not running -- it crashed or never started"
    adb logcat -d -v brief | grep -iE "nisos|FATAL|AndroidRuntime" | tail -60
    exit 1
fi
echo "  pid $PID"

# And is our screen actually the one in front? A process that is alive but whose
# activity died would otherwise pass.
RESUMED=$(adb shell dumpsys activity activities | tr -d '\r' \
    | grep -m1 -E "topResumedActivity|mResumedActivity" || true)
echo "  $RESUMED"
case "$RESUMED" in
    *"$PKG"*) echo "  our activity is in front" ;;
    *) echo "::warning::$PKG is running but not the resumed activity" ;;
esac
echo "::endgroup::"

echo "::group::Fatal exceptions"
if adb logcat -d | grep -E "FATAL EXCEPTION|E AndroidRuntime" > /tmp/fatal.txt; then
    echo "::error::a fatal exception was logged"
    cat /tmp/fatal.txt
    exit 1
fi
echo "  none"
echo "::endgroup::"

echo "smoke test passed"
