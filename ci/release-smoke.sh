#!/usr/bin/env bash
set -euo pipefail
app=io.github.gaogaoza.simpletimetable
mkdir -p app/build/device-evidence
collect() {
  adb pull /data/local/tmp/simpletimetable app/build/device-evidence/ || true
  adb logcat -d > app/build/device-evidence/release-logcat.txt || true
}
trap collect EXIT
# Seed while the development build allows run-as; then replace it with the
# real optimized, non-debuggable release. Only CI uses the ephemeral test key.
./gradlew installDebug installDebugAndroidTest assembleRelease -PciReleaseSigning=true --console=plain
adb shell am force-stop "$app"
adb shell run-as "$app" mkdir -p files
python3 ci/release-fixture.py > app/build/device-evidence/release-fixture.json
adb shell "run-as $app sh -c 'cat > files/timetable.json'" < app/build/device-evidence/release-fixture.json
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appwidget grantbind --package "$app" --user 0
timeout 180 adb shell am instrument -w -r -e class "$app.ReleaseSmokeTest" "$app.test/androidx.test.runner.AndroidJUnitRunner" | tee app/build/device-evidence/release-instrumentation.txt
python3 - <<'PY'
from pathlib import Path
text=Path('app/build/device-evidence/release-instrumentation.txt').read_text()
assert 'OK (2 tests)' in text, text
assert 'FAILURES!!!' not in text, text
PY
