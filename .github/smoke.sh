#!/usr/bin/env bash
#
# Install the APK on an emulator, start it, and prove it is still alive.
#
# This is the gap that let two layout bugs and a signing bug reach a phone: CI
# proved the Kotlin compiled and that `core/` behaved, and then published an APK
# that nothing had ever installed or launched. "It builds" and "it runs" are
# different claims and only one of them was being made.
#
# What this cannot test: the phone's own policy. Samsung's Auto Blocker, the
# per-source "install unknown apps" permission and Play Protect all refuse
# perfectly valid APKs, and an emulator has none of them. So a green run here
# means the APK is installable and launches -- it does not mean a particular
# handset will let you install it.
set -euo pipefail

APK=${1:-nisos.apk}
PKG=app.nisos
ACTIVITY=$PKG/$PKG.ui.MainActivity

echo "::group::Install"
adb wait-for-device
# -r so a rerun replaces rather than colliding. A failure here is the real
# prize: INSTALL_PARSE_FAILED_*, INSTALL_FAILED_INVALID_APK and friends are
# exactly what a phone reports as an unexplained "App not installed".
adb install -r "$APK"
echo "installed: $(adb shell dumpsys package $PKG | grep -m1 versionName || true)"
echo "::endgroup::"

echo "::group::Launch"
adb logcat -c
adb shell am start -W -n "$ACTIVITY"
# Compose needs a moment to inflate, and a crash-on-first-frame is precisely
# what we are looking for -- so look after it has had one.
sleep 10
echo "::endgroup::"

echo "::group::Still running?"
if ! adb shell pidof "$PKG" > /dev/null 2>&1; then
    echo "::error::$PKG is not running -- it crashed or never started"
    adb logcat -d -v brief | tail -120
    exit 1
fi
echo "pid $(adb shell pidof $PKG)"
echo "::endgroup::"

echo "::group::Fatal exceptions"
# grep -q with a pipeline under `set -o pipefail` needs care: tolerate no match.
if adb logcat -d | grep -E "FATAL EXCEPTION|E AndroidRuntime" > /tmp/fatal.txt; then
    echo "::error::a fatal exception was logged"
    cat /tmp/fatal.txt
    exit 1
fi
echo "none"
echo "::endgroup::"

# A picture, because both bugs found on hardware so far were layout -- neither
# was visible in the source, and neither would have been caught by any assertion
# anyone would think to write. A screenshot is the cheapest possible way to see
# the controls sitting on top of the ring.
echo "::group::Screenshots"
adb exec-out screencap -p > screen-main.png
echo "main screen: $(stat -c%s screen-main.png) bytes"

# The settings screen is reached by tapping the header, top right. Derived from
# the real display size rather than hardcoded, so this does not silently start
# tapping the wrong thing on a different emulator profile.
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $3}')
W=${SIZE%x*}
H=${SIZE#*x}
adb shell input tap $((W * 82 / 100)) $((H * 6 / 100))
sleep 3
adb exec-out screencap -p > screen-settings.png
echo "after tapping the header: $(stat -c%s screen-settings.png) bytes"
echo "::endgroup::"

echo "smoke test passed"
