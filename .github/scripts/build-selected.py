#!/usr/bin/env python3
"""Build once, run only selected JVM contracts; stop before the emulator if any task fails."""
import json
import subprocess
import sys
from pathlib import Path


def command(plan):
    args = ['./gradlew']
    for key, task in [('core_classes', ':hart-core:test'), ('usb_classes', ':transport-usb:testDebugUnitTest')]:
        if plan[key]:
            args.extend([task, '--fail-fast'])
            for pattern in plan[key]: args.extend(['--tests', pattern])
    if plan['build_apk']: args.append(':app:assembleDebug')
    if plan['android_enabled']: args.append(':app:assembleDebugAndroidTest')
    if plan['lint']: args.append(':app:lintDebug')
    args.append('--no-daemon')
    return args

if __name__ == '__main__':
    plan=json.loads(Path(sys.argv[1] if len(sys.argv)>1 else 'test-plan.json').read_text())
    if not plan['build_enabled']: raise SystemExit('No build required by this plan')
    args=command(plan)
    print('Selected Gradle command:', ' '.join(args), flush=True)
    raise SystemExit(subprocess.run(args).returncode)
