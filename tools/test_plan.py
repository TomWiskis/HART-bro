#!/usr/bin/env python3
"""Conservative, reviewable test selection. Unknown inputs expand coverage; they never skip it."""
from __future__ import annotations
import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'tools/test_catalog.json'
# A class can cover several contracts. Cheap JVM fault-injection tests are retained.
CORE_SUITES = {
 'ChartAxesTest': ['chart', 'pdf'], 'ChartViewportTest': ['chart'],
 'ConfigurationLeaseTest': ['session', 'range', 'zero', 'walk'],
 'ConfigurationTest': ['device', 'range', 'metadata'],
 'DiagnosticJournalTest': ['diagnostics'], 'ExcelExportTest': ['xlsx', 'device'],
 'HartCapture060Test': ['capture'], 'HartClientTest': ['protocol'],
 'HartCodecTest': ['protocol'], 'HartDescriptionsTest': ['device'],
 'HartDeviceTest': ['device', 'protocol'], 'JournalExportTest': ['archive', 'csv', 'pdf', 'xlsx'],
 'MeasurementsTest': ['measurements'], 'MetranRegressionTest': ['protocol', 'device'],
 'MonitoringEngineTest': ['session', 'connection'], 'PassportTest': ['device', 'walk'],
 'Presentation052Test': ['quick', 'chart', 'connection'],
 'QuickView053Test': ['quick', 'connection', 'device'],
 'RangeWriteTest': ['range'], 'SensorOperations053Test': ['zero', 'metadata'],
 'SessionHistoryTest': ['archive', 'session', 'csv', 'xlsx', 'pdf'],
 'SessionLifecycle030Test': ['session', 'connection'],
 'Supplement060Test': ['zero', 'device', 'connection'],
 'Walk060Test': ['walk', 'xlsx'], 'WalkTest': ['walk', 'xlsx'], 'WalkTiming060Test': ['walk'],
}
SAFETY_CLASSES = {'HartClientTest', 'RangeWriteTest', 'SensorOperations053Test', 'Supplement060Test'}
CORE_FILES = {
 'HartDeviceCatalog.kt': ['device'], 'HartDescriptions.kt': ['device', 'range', 'measurements'],
 'ChartAxes.kt': ['chart', 'pdf'], 'ChartViewport.kt': ['chart'],
 'Configuration.kt': ['device', 'range', 'metadata', 'walk'],
 'DiagnosticJournal.kt': ['diagnostics', 'capture'], 'HartAnalysis.kt': ['capture'],
 'Passport.kt': ['walk', 'device'], 'Presentation.kt': ['quick', 'chart', 'connection'],
 'RangeEngine.kt': ['range', 'session', 'walk'], 'RangeJournal.kt': ['range', 'archive'],
 'RangeModel.kt': ['range', 'zero'], 'RangeOperations.kt': ['range', 'zero'],
 'SensorJournal.kt': ['zero', 'metadata', 'archive'], 'SensorOperations.kt': ['zero', 'metadata'],
 'ZeroMethods.kt': ['zero'], 'ZeroOperations.kt': ['zero'],
 'SessionExport.kt': ['csv', 'pdf'], 'SessionHistory.kt': ['archive', 'session', 'pdf', 'csv', 'xlsx'],
 'SessionXlsx.kt': ['xlsx'], 'TabularExport.kt': ['xlsx', 'csv', 'pdf', 'device'],
 'WalkCatalog.kt': ['walk'], 'WalkEngine.kt': ['walk', 'connection'], 'WalkStore.kt': ['walk'],
 'WalkXlsx.kt': ['walk', 'xlsx'], 'XlsxPackage.kt': ['xlsx'],
}
# Shared wire/model/monitoring changes affect all consumers, including write serialization.
CORE_GLOBAL = {'HartClient.kt', 'HartCodec.kt', 'HartDevice.kt', 'Measurements.kt', 'MonitoringEngine.kt'}
APP_FILES = {
 'ActionDialog.kt': ['range', 'zero', 'metadata', 'walk', 'capture'],
 'AnalysisScreen.kt': ['capture'], 'ExportFormat.kt': ['pdf', 'xlsx', 'csv', 'archive'],
 'JournalScreen.kt': ['archive', 'pdf', 'xlsx', 'csv'], 'MonitoringChart.kt': ['chart'],
 'RangeScreen.kt': ['range', 'zero', 'metadata', 'archive'],
 'SensorScreen.kt': ['quick', 'device', 'metadata', 'zero', 'connection'],
 'UsbConnectionScreen.kt': ['usb', 'connection'], 'WalkScreen.kt': ['walk'],
 'ZeroScreen.kt': ['zero'], 'reports/SessionPdf.kt': ['pdf'],
}
APP_GLOBAL = {'MainActivity.kt', 'HartApplication.kt', 'session/SessionViewModel.kt', 'session/MonitoringService.kt'}
USB_FILES = {
 'EniUsbDrivers.kt': ['EniUsbDriversTest'], 'UsbPermissionGate.kt': ['UsbPermissionGateTest'],
 'UsbReadQualification.kt': ['UsbReadQualificationTest'],
 'UsbHartTransport.kt': ['UsbHartTransportTest', 'UsbReadQualificationTest'],
 'UsbHostController.kt': ['EniUsbDriversTest', 'UsbPermissionGateTest', 'UsbHartTransportTest'],
}
TEST_RE = re.compile(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)')



def test_methods(source: str, owner: str) -> list[str]:
    # Ignore braces/annotations in strings/comments. Unsupported nested/backtick test layouts fail closed.
    literals = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/', re.S)
    code = literals.sub(lambda m: ' ' * len(m[0]), source)
    methods = list(TEST_RE.finditer(code))
    if len(methods) != len(re.findall(r'@Test\b', code)):
        raise ValueError('Unsupported @Test declaration; update inventory parser explicitly')
    positions = {m.start() for m in methods}
    stack, pending, primary_depth = [], None, None
    for token in re.finditer(r'\bclass\s+(\w+)|[{}]|@Test\b', code):
        text = token[0]
        if token[1]:
            pending = token[1]
        elif text == '{':
            stack.append(pending)
            if pending == owner: primary_depth = len(stack)
            pending = None
        elif text == '}':
            if stack: stack.pop()
        elif token.start() in positions:
            if primary_depth is None or len(stack) != primary_depth or stack[-1] != owner:
                raise ValueError('Tests must be direct members of the file-named JUnit class')
    return [m[1] for m in methods]


def inventory(root: Path, folder: str) -> dict[str, str]:
    found = {}
    for file in sorted((root / folder).rglob('*.kt')):
        source = file.read_text()
        methods = test_methods(source, file.stem)
        if not methods:
            continue
        package = re.search(r'^package\s+([\w.]+)', source, re.M)
        # All current test classes match their file name. A new layout must be explicitly supported.
        if not package or not re.search(r'\bclass\s+' + re.escape(file.stem) + r'\b', source):
            raise ValueError(f'Cannot safely discover test ownership: {file.relative_to(root)}')
        for method in methods:
            key = f'{package[1]}.{file.stem}#{method}'
            if key in found:
                raise ValueError(f'Duplicate test: {key}')
            found[key] = file.relative_to(root).as_posix()
    return found


def validate_catalog(root: Path = ROOT, catalog: list[dict] | None = None) -> tuple[dict, dict, dict, list]:
    android = inventory(root, 'app/src/androidTest/kotlin')
    core = inventory(root, 'hart-core/src/test/kotlin')
    usb = inventory(root, 'transport-usb/src/test/kotlin')
    catalog = catalog if catalog is not None else json.loads((root / 'tools/test_catalog.json').read_text())
    names = [c['id'] for c in catalog]
    if len(names) != len(set(names)) or set(names) != set(android):
        raise ValueError(f'Android catalogue drift: missing={set(android)-set(names)}, stale={set(names)-set(android)}')
    for c in catalog:
        if not c['domains'] or not re.fullmatch(r'ru\.hartbro\.app\.\w+#\w+', c['id']):
            raise ValueError(f'Invalid Android catalogue entry: {c}')
    classes = {k.split('#')[0].split('.')[-1] for k in core}
    if classes != set(CORE_SUITES):
        raise ValueError(f'JVM catalogue drift: {classes ^ set(CORE_SUITES)}')
    usb_classes = {k.split('#')[0].split('.')[-1] for k in usb}
    if usb_classes != set().union(*map(set, USB_FILES.values())):
        raise ValueError('USB JVM catalogue drift')
    return android, core, usb, catalog


def changed_files(base: str, head: str = 'HEAD', root: Path = ROOT) -> list[str] | None:
    """None means no trustworthy diff. Empty means a real, empty diff."""
    if not base or set(base) == {'0'}:
        return None
    for ref in (base, head):
        if subprocess.run(['git', 'rev-parse', '--verify', '--end-of-options', ref+'^{commit}'], cwd=root,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            return None
    result = subprocess.run(['git', 'diff', '--name-only', '--no-renames', '-z', base, head, '--'],
                            cwd=root, capture_output=True, check=True)
    return [p.decode('utf-8') for p in result.stdout.split(b'\0') if p]


def make_plan(files: list[str] | None, mode: str = 'affected', root: Path = ROOT) -> dict:
    if mode not in {'affected', 'full', 'soak'}:
        raise ValueError('mode must be affected, full or soak')
    android, core, usb, catalog = validate_catalog(root)
    core_classes, usb_classes, android_ids, domains = set(), set(), set(), set()
    directly_changed_android = set()
    reasons = []
    full = mode != 'affected' or files is None
    build_apk = lint = False
    if files is None:
        reasons.append('No verified diff: expand to all fast checks')
    for path in files or []:
        if path.startswith('docs/') or path in {'README.md', 'CHANGELOG.md', 'LICENSE', 'LICENSE.txt', '.gitignore'}:
            continue
        if path.startswith('tools/tests/'):
            continue  # Python policy/runner regression tests always run in the planning job.
        if path in {'tools/validate_walk_xlsx.py', 'tools/validate_export_control.py'}:
            domains.add('xlsx'); core_classes.update({'ExcelExportTest', 'WalkTest'})
            continue
        if path.startswith('test-support/') or path.startswith('.github/') or path.startswith('tools/'):
            full = True; reasons.append(f'CI/shared test support: {path}'); continue
        prefix = 'hart-core/src/main/kotlin/ru/hartbro/core/'
        if path.startswith(prefix):
            name = path.removeprefix(prefix)
            if name in CORE_GLOBAL or name not in CORE_FILES:
                full = True; reasons.append(f'Shared/unknown core dependency: {path}'); continue
            local = set(CORE_FILES[name]); domains.update(local)
            core_classes.update(c for c, groups in CORE_SUITES.items() if local.intersection(groups))
            if local.intersection({'range', 'zero', 'connection', 'session'}):
                core_classes.update(SAFETY_CLASSES)
            build_apk = lint = True
            continue
        prefix = 'app/src/main/kotlin/ru/hartbro/app/'
        if path.startswith(prefix):
            name = path.removeprefix(prefix)
            if name in APP_GLOBAL or name not in APP_FILES:
                full = True; reasons.append(f'Shared/unknown app dependency: {path}'); continue
            domains.update(APP_FILES[name]); build_apk = lint = True; continue
        prefix = 'transport-usb/src/main/kotlin/ru/hartbro/usb/'
        if path.startswith(prefix) and path.removeprefix(prefix) in USB_FILES:
            name = path.removeprefix(prefix); usb_classes.update(USB_FILES[name])
            local = {'usb', 'connection'}
            if name in {'UsbHartTransport.kt', 'UsbHostController.kt', 'UsbReadQualification.kt'}:
                local.update({'range', 'zero', 'capture', 'session', 'walk'})
                core_classes.update(SAFETY_CLASSES | {'MonitoringEngineTest', 'ConfigurationLeaseTest'})
            domains.update(local); build_apk = lint = True; continue
        if path.startswith('transport-bluetooth/src/main/'):
            domains.update({'connection', 'session', 'range', 'zero', 'walk', 'capture'})
            core_classes.update(SAFETY_CLASSES | {'MonitoringEngineTest', 'ConfigurationLeaseTest'})
            build_apk = lint = True; continue
        test_owners = [(core, core_classes), (usb, usb_classes)]
        matched = False
        for tests, selected in test_owners:
            owned = {k.split('#')[0].split('.')[-1] for k, p in tests.items() if p == path}
            if owned:
                selected.update(owned); matched = True
        if matched:
            continue
        if path.startswith('hart-core/src/test/resources/'):
            full = True; reasons.append(f'Shared recorded fixtures: {path}'); continue
        owned = {k for k, p in android.items() if p == path}
        if path.endswith('/FieldRegressionTest.kt') and path.startswith('app/src/androidTest/'):
            # This file also contains the Application/runner fixture shared by all Android tests.
            full = True; reasons.append('Shared Android Application fixture changed'); continue
        if owned:
            android_ids.update(owned); directly_changed_android.update(owned); continue
        if path.startswith('app/src/androidTest/'):
            android_ids.update(android); continue  # Rules/helpers can affect every UI scenario.
        full = True; reasons.append(f'Unmapped/build/resource change: {path}')
    if full:
        core_classes = set(CORE_SUITES)
        usb_classes = {k.split('#')[0].split('.')[-1] for k in usb}
        android_ids = set(android)
        build_apk = lint = True
    else:
        android_ids.update(c['id'] for c in catalog if domains.intersection(c['domains']))
    priority = lambda key: (next(c.get('priority', 50) for c in catalog if c['id']==key), key)
    ordered = sorted(android_ids, key=priority)
    # Check a small edited test class first, even when unverified shared changes require the full set.
    # This diagnoses a local harness failure before unrelated/longer regression cases, without repeats.
    preflight = sorted(directly_changed_android,key=priority) if 0 < len(directly_changed_android) <= 3 else ordered[:1]
    remaining = [key for key in ordered if key not in preflight]
    if android_ids:
        build_apk = lint = True
    return {
        'schema': 1, 'requested_mode': mode, 'profile': 'soak' if mode == 'soak' else 'fast',
        'background_window_ms': 300000 if mode == 'soak' else 20000,
        'changed_files': files, 'full_selection': full, 'domains': sorted(domains), 'reasons': reasons,
        'core_classes': ['ru.hartbro.core.'+c for c in sorted(core_classes)],
        'usb_classes': ['ru.hartbro.usb.'+c for c in sorted(usb_classes)],
        'android': ordered, 'preflight': preflight, 'remaining': remaining,
        'build_apk': build_apk, 'lint': lint, 'android_enabled': bool(android_ids),
        'build_enabled': bool(core_classes or usb_classes or build_apk or lint),
        'inventory': {'core': len(core), 'usb': len(usb), 'android': len(android)},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--mode', choices=['affected', 'full', 'soak'], default='affected')
    parser.add_argument('--files', nargs='*', default=None, help='Explicit paths for inspection; CI uses verified git diff')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    plan = make_plan(args.files if args.files is not None else changed_files(args.base, args.head), args.mode)
    data = json.dumps(plan, ensure_ascii=False, indent=2)+'\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(data)
    print(data, end='')

if __name__ == '__main__': main()
