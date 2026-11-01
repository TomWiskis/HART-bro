#!/usr/bin/env bash
set -uo pipefail
# Check the new input/navigation flows before the retained 15-minute lifecycle scenario.
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.hartbro.app.Interface052Test --no-daemon 2>&1 | tee /tmp/interface-052-smoke.log
smoke_result=$?
if [ "$smoke_result" -ne 0 ]; then exit "$smoke_result"; fi
./gradlew :app:connectedDebugAndroidTest --no-daemon 2>&1 | tee /tmp/android-regression-gradle.log
gradle_result=$?
# Retain the actual Android-generated control workbook, PDF and page renders for review.
mkdir -p app/build/outputs/export-review
adb pull /sdcard/Android/data/ru.hartbro.app/files/export-review/. app/build/outputs/export-review/ || true
exit "$gradle_result"
