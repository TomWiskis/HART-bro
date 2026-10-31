#!/usr/bin/env bash
set -uo pipefail
./gradlew :app:connectedDebugAndroidTest --no-daemon 2>&1 | tee /tmp/android-regression-gradle.log
gradle_result=$?
# Retain the actual Android-generated control workbook, PDF and page renders for review.
mkdir -p app/build/outputs/export-review
adb pull /sdcard/Android/data/ru.hartbro.app/files/export-review/. app/build/outputs/export-review/ || true
exit "$gradle_result"
