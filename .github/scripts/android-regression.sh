#!/usr/bin/env bash
set -euo pipefail
# APKs were compiled and unit-tested once in the prerequisite build job.
python3 tools/android_instrumentation.py --plan test-plan.json --apks ci-apks
