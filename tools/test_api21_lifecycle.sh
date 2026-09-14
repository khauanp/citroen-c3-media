#!/bin/sh
set -eu

package="com.c3media.dashboard"
component="$package/io.github.jqssun.airplay.MainActivity"
apk="app/build/outputs/apk/debug/app-debug.apk"

test -f "$apk"
adb install -r "$apk"
adb logcat -c
adb shell am start -n "$component" > /tmp/c3-first-start.txt
sleep 10
adb shell ps | grep -F "$package" > /dev/null
adb shell dumpsys activity activities | grep -F "$package" > /dev/null

# Open the real prebuilt native audio output and cycle the three AirPlay
# formats before the lifecycle/orientation stress. This catches the exact
# first-play regression that a UI-only demo cannot exercise.
adb shell am force-stop "$package"
adb shell am start -n "$component" --es debug_demo audio-probe > /tmp/c3-audio-probe.txt
probe_wait=0
while [ "$probe_wait" -lt 30 ]; do
    if adb logcat -d | grep -F 'C3_AUDIO_PROBE_PASS' > /dev/null; then break; fi
    sleep 1
    probe_wait=$((probe_wait + 1))
done
adb logcat -d | grep -F 'C3_AUDIO_PROBE_PASS' > /dev/null
if adb logcat -d | grep -F 'C3_AUDIO_PROBE_FAIL' > /dev/null; then
    echo "Native AirPlay audio probe failed"
    exit 1
fi
adb shell ps | grep -F "$package" > /dev/null

pass=1
while [ "$pass" -le 20 ]; do
    adb shell am start -n "$component" > /tmp/c3-start.txt
    sleep 1
    adb shell ps | grep -F "$package" > /dev/null
    adb shell settings put system user_rotation $((pass % 2))
    adb shell input keyevent KEYCODE_HOME
    pass=$((pass + 1))
done

adb shell am start -n "$component"
sleep 3
adb shell ps | grep -F "$package" > /dev/null
adb shell dumpsys activity activities | grep -F "$package" > /dev/null
adb logcat -d > c3-lifecycle-logcat.txt

if grep -E 'FATAL EXCEPTION|Fatal signal|ANR in com\.c3media\.dashboard|Process com\.c3media\.dashboard .* has died' c3-lifecycle-logcat.txt; then
    echo "Crash or ANR detected during Android 5 lifecycle stress"
    exit 1
fi

crash_path="$(adb shell run-as "$package" ls files/last-crash.txt 2>/dev/null | tr -d '\r' || true)"
if [ "$crash_path" = "files/last-crash.txt" ]; then
    echo "Application crash recorder contains a failure"
    adb shell run-as "$package" cat files/last-crash.txt || true
    exit 1
fi

echo "PASS: 20 Android 5 lifecycle/rotation cycles without process death, fatal exception or ANR"
