#!/usr/bin/env bash
set -uo pipefail
# Catch dialog/window-focus regressions before the mandatory fifteen-minute monitoring scenario.
# The complete suite below remains required by the workflow's expected-method/count gate.
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=ru.hartbro.app.RangeRegressionTest#walkEndAndMonitorTransitionDialogsKeepAllThreeActionsOnSmallLargeFontScreen' --no-daemon 2>&1 | tee /tmp/android-regression-gradle.log
gradle_result=$?
if [ "$gradle_result" -eq 0 ]; then
  ./gradlew :app:connectedDebugAndroidTest --no-daemon 2>&1 | tee -a /tmp/android-regression-gradle.log
  gradle_result=$?
fi
# Retain the actual Android-generated control workbook, PDF and page renders for review.
mkdir -p app/build/outputs/export-review
adb pull /sdcard/Android/data/ru.hartbro.app/files/export-review/. app/build/outputs/export-review/ || true
exit "$gradle_result"
