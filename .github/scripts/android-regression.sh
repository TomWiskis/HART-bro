#!/usr/bin/env bash
set -uo pipefail
# Preserve device evidence before emulator teardown if a UI/system-window failure occurs.
diag_dir=app/build/outputs/export-review
collect_failure_diagnostics() {
  mkdir -p "$diag_dir"
  adb shell dumpsys activity lastanr >"$diag_dir/last-anr.txt" 2>&1 || true
  adb shell dumpsys window windows >"$diag_dir/window-state.txt" 2>&1 || true
  adb shell dumpsys input_method >"$diag_dir/input-method-state.txt" 2>&1 || true
  adb logcat -d -v threadtime -t 4000 >"$diag_dir/device-logcat.txt" 2>&1 || true
  adb logcat -d -b events -s am_anr am_crash >"$diag_dir/anr-crash-events.txt" 2>&1 || true
  adb shell uiautomator dump /sdcard/hartbro-failure.xml || true
  adb pull /sdcard/hartbro-failure.xml "$diag_dir/window-hierarchy.xml" || true
  adb exec-out screencap -p >"$diag_dir/android-failure.png" || true
  python3 - <<'PYDIAG'
from pathlib import Path
import re
p=Path('app/build/outputs/export-review')
for name in ['last-anr.txt','anr-crash-events.txt','window-state.txt','input-method-state.txt','device-logcat.txt','window-hierarchy.xml']:
    f=p/name
    if not f.exists(): continue
    lines=f.read_text(errors='replace').splitlines()
    if name=='last-anr.txt':
        selected=lines[:100]
        for i,l in enumerate(lines):
            if l.startswith('"main"'): selected+=lines[i:i+45]
    elif name=='window-hierarchy.xml': selected=[l[:10000] for l in lines]
    else: selected=[l for l in lines if re.search(r'ANR|am_anr|am_crash|FATAL EXCEPTION|not responding|Input dispatching|mCurrentFocus|mFocusedApp|mImeLayeringTarget|mInputMethodTarget|mInputShown|mShowRequested|mCurFocusedWindow|mServedView',l,re.I)][-100:]
    print('DEVICE DIAGNOSTIC '+name)
    for l in selected: print(l[:1800])
PYDIAG
}
# Check the new input/navigation flows before the retained 15-minute lifecycle scenario.
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.hartbro.app.Interface052Test,ru.hartbro.app.QuickView053Test,ru.hartbro.app.FieldRegressionTest#archiveBackRemainsVisibleAfterScrollingAndSystemBackReturnsToList,ru.hartbro.app.RangeRegressionTest,ru.hartbro.app.Usb060Test,ru.hartbro.app.Supplement060AndroidTest --no-daemon 2>&1 | tee /tmp/interface-053-smoke.log
smoke_result=$?
if [ "$smoke_result" -ne 0 ]; then collect_failure_diagnostics; exit "$smoke_result"; fi
./gradlew :app:connectedDebugAndroidTest --no-daemon 2>&1 | tee /tmp/android-regression-gradle.log
gradle_result=$?
if [ "$gradle_result" -ne 0 ]; then collect_failure_diagnostics; fi
# Retain the actual Android-generated control workbook, PDF and page renders for review.
mkdir -p app/build/outputs/export-review
adb pull /sdcard/Android/data/ru.hartbro.app/files/export-review/. app/build/outputs/export-review/ || true
exit "$gradle_result"
