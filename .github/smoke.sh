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

# Settings is reached by tapping the header, top right. Derived from the real
# display size rather than hardcoded, so a different emulator profile does not
# silently start tapping somewhere else.
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $3}')
W=${SIZE%x*}
H=${SIZE#*x}
echo "  display ${W}x${H}"
adb shell input tap $((W * 82 / 100)) $((H * 6 / 100))
sleep 3
adb exec-out screencap -p > screen-settings.png
echo "  after tapping the header: $(stat -c%s screen-settings.png) bytes"
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
