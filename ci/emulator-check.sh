#!/usr/bin/env bash
set -u
screen="$1"
mkdir -p app/build/device-evidence
adb shell wm size "$screen"
adb shell wm density 160
adb shell wm dismiss-keyguard
adb shell input keyevent 82
adb shell appwidget grantbind --package io.github.gaogaoza.simpletimetable --user 0
if ./gradlew connectedDebugAndroidTest --console=plain; then
  test_exit=0
else
  test_exit=$?
fi
adb pull /sdcard/Android/data/io.github.gaogaoza.simpletimetable/files/acceptance app/build/device-evidence/ || true
adb logcat -d > app/build/device-evidence/logcat.txt || true
adb shell dumpsys notification > app/build/device-evidence/notification.txt || true
exit "$test_exit"
