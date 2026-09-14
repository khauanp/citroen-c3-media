#!/bin/sh
set -eu

package="com.c3media.dashboard"
component="$package/io.github.jqssun.airplay.MainActivity"
apk="app/build/outputs/apk/debug/app-debug.apk"

test -f "$apk"
adb install -r "$apk"
adb logcat -c
timeout 120s adb shell am start -W -n "$component" > /tmp/c3-first-start.txt
grep -Eq 'Status: ok|Activity:' /tmp/c3-first-start.txt

pass=1
while [ "$pass" -le 80 ]; do
    adb shell am start -n "$component" > /tmp/c3-start.txt
    sleep 1
    adb shell ps | grep -F "$package" > /dev/null
    adb shell settings put system user_rotation $((pass % 2))
    adb shell input keyevent KEYCODE_HOME
    pass=$((pass + 1))
done

timeout 120s adb shell am start -W -n "$component"
adb shell ps | grep -F "$package" > /dev/null
adb logcat -d > c3-lifecycle-logcat.txt

if grep -E 'FATAL EXCEPTION|Fatal signal|ANR in com\.c3media\.dashboard|Process com\.c3media\.dashboard .* has died' c3-lifecycle-logcat.txt; then
    echo "Crash or ANR detected during Android 5 lifecycle stress"
    exit 1
fi

if adb shell run-as "$package" ls files/last_crash.txt > /dev/null 2>&1; then
    echo "Application crash recorder contains a failure"
    adb shell run-as "$package" cat files/last_crash.txt || true
    exit 1
fi

echo "PASS: 80 Android 5 lifecycle/rotation cycles without process death, fatal exception or ANR"
