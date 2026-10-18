#!/usr/bin/env bash
set -u
screen="$1"
variant="${2:-debug}"
case "$variant" in
  debug) variant_task=Debug ;;
  release) variant_task=Release ;;
  *) printf 'Unsupported build variant: %s\n' "$variant" >&2; exit 2 ;;
esac
gradle_args=("-PtestBuildType=$variant")
if [ "$variant" = release ]; then
  gradle_args+=("-PciReleaseSigning=true")
fi
mkdir -p app/build/device-evidence
adb shell wm size "$screen"
adb shell wm density 160
adb shell wm dismiss-keyguard
adb shell input keyevent 82
adb shell mkdir -p /data/local/tmp/simpletimetable
if ! ./gradlew ":app:install${variant_task}" ":app:install${variant_task}AndroidTest" "${gradle_args[@]}" --console=plain; then
  exit 1
fi
adb shell appwidget grantbind --package io.github.gaogaoza.simpletimetable --user 0
if ./gradlew ":app:connected${variant_task}AndroidTest" "${gradle_args[@]}" --console=plain; then
  test_exit=0
else
  test_exit=$?
fi
adb pull /data/local/tmp/simpletimetable app/build/device-evidence/ || true
python - <<'PYTEST'
from pathlib import Path
import xml.etree.ElementTree as E
for file in Path('app/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        root=E.parse(file).getroot()
        for case in root.iter('testcase'):
            for failure in list(case.findall('failure'))+list(case.findall('error')):
                print(case.attrib.get('name'),(failure.text or '')[:10000])
    except E.ParseError:
        pass
PYTEST
adb logcat -d > app/build/device-evidence/logcat.txt || true
adb shell dumpsys notification > app/build/device-evidence/notification.txt || true
exit "$test_exit"
