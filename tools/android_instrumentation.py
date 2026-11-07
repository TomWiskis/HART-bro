#!/usr/bin/env python3
"""Run the selected Android contracts from prebuilt APKs. No Gradle or blind adb exit-code checks."""
from __future__ import annotations
import argparse
import json
import queue
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP = 'ru.hartbro.app'
COMPONENT = APP+'.test/'+APP+'.FieldRegressionRunner'
SOAK_TEST = 'homeScreenLockAndActivityRecreationKeepOneForegroundSession'


class InstrumentationResults:
    def __init__(self, expected, profile='fast', clock=time.monotonic):
        self.expected = list(expected)
        if len(self.expected) != len(set(self.expected)):
            raise ValueError('Duplicate requested tests')
        self.profile = profile
        self.clock = clock
        self.status, self.result = {}, {}
        self.continuation = None
        self.active, self.active_at = None, None
        self.started, self.records, self.errors = [], {}, []
        self.terminal = None
        self.attested = False

    @property
    def failed(self):
        return bool(self.errors or any(r['status'] != 'passed' for r in self.records.values()))

    def feed(self, raw):
        line = raw.rstrip('\r\n')
        for prefix, target in [('INSTRUMENTATION_STATUS: ', self.status), ('INSTRUMENTATION_RESULT: ', self.result)]:
            if line.startswith(prefix):
                key, sep, value = line[len(prefix):].partition('=')
                if sep:
                    target[key] = value; self.continuation = (target, key)
                return
        if line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            self.continuation = None
            try: code = int(line.partition(':')[2])
            except ValueError: self.errors.append('Invalid status code'); return
            data = self.status; self.status = {}
            if code == 2:
                if 'hartbroProfile' in data:
                    window = '300000' if self.profile == 'soak' else '20000'
                    if self.attested or data.get('hartbroProfile') != self.profile or data.get('hartbroWindowMillis') != window:
                        self.errors.append('Wrong or duplicated runtime test profile')
                    else: self.attested = True
                return
            test_id = data.get('class', '')+'#'+data.get('test', '')
            if test_id not in self.expected:
                self.errors.append('Unexpected test/status: '+test_id); return
            if code == 1:
                if not self.attested: self.errors.append('Runtime profile was not attested before tests')
                if test_id in self.started: self.errors.append('Duplicate test start: '+test_id)
                self.started.append(test_id); self.active = test_id; self.active_at = self.clock()
                return
            if code not in (0, -1, -2, -3, -4):
                self.errors.append('Unknown test status: '+str(code)); return
            if self.active != test_id or test_id in self.records:
                self.errors.append('Missing start or duplicate completion: '+test_id); return
            state = {0:'passed', -1:'error', -2:'failed', -3:'skipped', -4:'skipped'}[code]
            self.records[test_id] = {'status':state, 'seconds':max(0, self.clock()-self.active_at),
                                     'message':data.get('stack') or data.get('stream') or state}
            self.active, self.active_at = None, None
            return
        if line.startswith('INSTRUMENTATION_CODE: '):
            self.continuation = None
            try: self.terminal = int(line.partition(':')[2])
            except ValueError: self.errors.append('Invalid terminal code')
            return
        if line.startswith('INSTRUMENTATION_FAILED') or line.startswith('INSTRUMENTATION_ABORTED'):
            self.errors.append(line); self.continuation = None; return
        if self.continuation:
            target, key = self.continuation
            target[key] = (target[key]+'\n'+line)[-16000:]

    def finish(self, returncode=0):
        errors = list(self.errors)
        if returncode != 0: errors.append('adb/instrumentation process exit '+str(returncode))
        if self.terminal != -1: errors.append('Instrumentation did not finish normally')
        if not self.attested: errors.append('Missing runtime profile attestation')
        missing = sorted(set(self.expected)-set(self.records))
        if missing: errors.append(f'{len(missing)} planned tests did not finish')
        passed = not errors and len(self.records) == len(self.expected) and all(r['status']=='passed' for r in self.records.values())
        return {'status':'passed' if passed else 'failed', 'profile':self.profile,
                'expected':self.expected, 'started':self.started, 'records':self.records,
                'not_run':missing, 'errors':errors}


def validate_plan(plan):
    selected, preflight, remaining = plan['android'], plan['preflight'], plan['remaining']
    if (plan.get('schema') != 1 or plan['profile'] not in ('fast', 'soak')
            or plan['background_window_ms'] != (300000 if plan['profile']=='soak' else 20000)):
        raise ValueError('Invalid plan/profile')
    if not selected or len(selected) != len(set(selected)) or set(preflight).intersection(remaining):
        raise ValueError('Empty or overlapping Android plan')
    if len(preflight+remaining) != len(selected) or set(preflight+remaining) != set(selected):
        raise ValueError('Plan does not cover selected cases exactly once')
    if not preflight or any(not re.fullmatch(r'ru\.hartbro\.app\.\w+#\w+', s) for s in selected):
        raise ValueError('Invalid or missing preflight selector')


def xml_text(text):
    return re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f]', '\ufffd', str(text))


def write_junit(phase, path):
    suite = ET.Element('testsuite', name='hartbro.'+path.stem)
    failures = errors = skipped = 0
    for test_id in phase['expected']:
        cls, name = test_id.split('#')
        record = phase['records'].get(test_id)
        case = ET.SubElement(suite, 'testcase', classname=cls, name=name, time=str(record['seconds'] if record else 0))
        if not record:
            skipped += 1; ET.SubElement(case, 'skipped', message='Not run: earlier failure or aborted instrumentation')
        elif record['status'] == 'failed':
            failures += 1; ET.SubElement(case, 'failure', message='Android assertion failed').text=xml_text(record['message'])
        elif record['status'] == 'error':
            errors += 1; ET.SubElement(case, 'error', message='Android test error').text=xml_text(record['message'])
        elif record['status'] == 'skipped':
            skipped += 1; ET.SubElement(case, 'skipped', message=xml_text(record['message']))
    if phase['errors']:
        errors += 1
        ET.SubElement(ET.SubElement(suite, 'testcase', classname='ru.hartbro.ci', name='instrumentation'),
                      'error', message='Instrumentation/selection error').text=xml_text('\n'.join(phase['errors']))
    suite.set('tests',str(len(list(suite)))); suite.set('failures',str(failures)); suite.set('errors',str(errors)); suite.set('skipped',str(skipped))
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding='utf-8', xml_declaration=True)


def execute_phase(expected, profile, command, output, stop, *, setup_timeout=60, case_timeout=120):
    """Read native status live: fail-fast after the failed case's JUnit teardown has run."""
    parser = InstrumentationResults(expected, profile)
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, errors='replace', bufsize=1)
    messages = queue.Queue()
    def reader():
        try:
            for line in process.stdout: messages.put(line)
        except (OSError, ValueError):
            pass  # Pipe can close after deliberate fail-fast termination.
        finally: messages.put(None)
    thread = threading.Thread(target=reader, daemon=True); thread.start()
    last_progress = time.monotonic(); aborted = False
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('w') as log:
        try:
            while True:
                try: line = messages.get(timeout=.2)
                except queue.Empty:
                    budget = 1050 if profile=='soak' and parser.active and SOAK_TEST in parser.active else case_timeout
                    if (parser.active_at is not None and time.monotonic()-parser.active_at > budget
                            or parser.active is None and time.monotonic()-last_progress > setup_timeout):
                        parser.errors.append('Test/setup watchdog expired'); aborted=True; break
                    continue
                if line is None: break
                log.write(line); log.flush()
                before = len(parser.records)
                parser.feed(line)
                if len(parser.records) != before:
                    last_progress = time.monotonic()
                    key = next(reversed(parser.records)); record = parser.records[key]
                    print(f"{record['status'].upper()} {key} ({record['seconds']:.2f}s)", flush=True)
                    if record['status'] != 'passed':
                        message = record['message']
                        print(message if len(message)<=4000 else message[:2000]+'\n...\n'+message[-2000:],flush=True)
                if parser.failed:
                    aborted=True; break
        finally:
            if aborted:
                # The failure status is emitted after Rules/@After; stop the next case before it can continue.
                try: stop()
                except Exception as exc: parser.errors.append('Stop failed: '+type(exc).__name__)
                process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(); parser.errors.append('Instrumentation process required kill')
            thread.join(timeout=1)
            process.stdout.close()
    phase = parser.finish(process.returncode)
    phase['aborted_early'] = aborted
    write_junit(phase, output.with_suffix('.xml'))
    return phase


class Adb:
    def __init__(self, serial): self.prefix=['adb','-s',serial]
    def run(self, *args, timeout=30, check=True):
        return subprocess.run(self.prefix+list(args), capture_output=True, text=True, errors='replace', timeout=timeout, check=check)
    def stop(self): self.run('shell','am','force-stop',APP,timeout=5,check=False)


def collect_diagnostics(adb, folder):
    folder.mkdir(parents=True, exist_ok=True)
    commands = {
        'last-anr.txt':['shell','dumpsys','activity','lastanr'],
        'window-state.txt':['shell','dumpsys','window','windows'],
        'input-method-state.txt':['shell','dumpsys','input_method'],
        'device-logcat.txt':['logcat','-d','-v','threadtime','-t','4000'],
        'anr-crash-events.txt':['logcat','-d','-b','events','-s','am_anr','am_crash'],
    }
    for name, args in commands.items():
        try: text=adb.run(*args,timeout=10,check=False).stdout
        except Exception as exc: text=type(exc).__name__+': diagnostic timed out/unavailable'
        (folder/name).write_text(text)
        selected=text.splitlines()[:80] if name=='last-anr.txt' else [l for l in text.splitlines() if re.search(r'ANR|am_crash|FATAL EXCEPTION|not responding|mCurrentFocus|mFocusedApp|mInputShown|mCurFocusedWindow',l,re.I)][-30:]
        print('DEVICE DIAGNOSTIC '+name+'\n'+'\n'.join(selected),flush=True)
    try:
        result=subprocess.run(adb.prefix+['exec-out','screencap','-p'],capture_output=True,timeout=10)
        if result.returncode==0: (folder/'post-failure.png').write_bytes(result.stdout)
    except subprocess.TimeoutExpired: pass


def verify_result(plan, report, require_soak=False):
    validate_plan(plan)
    if require_soak and (plan['profile'] != 'soak' or not plan.get('full_selection')
                         or len(plan['android']) != plan.get('inventory',{}).get('android')):
        raise ValueError('Release requires a full soak plan, never a fast/partial report')
    if report.get('profile') != plan['profile'] or report.get('status') != 'passed':
        raise ValueError('Android run did not pass the requested profile')
    records = {}
    for phase in report['phases']:
        if phase['status'] != 'passed' or phase['errors'] or phase['not_run']:
            raise ValueError('Incomplete/failed Android phase')
        for key, record in phase['records'].items():
            if key in records or record['status'] != 'passed':
                raise ValueError('Repeated or non-passing case: '+key)
            records[key] = record
    if set(records) != set(plan['android']): raise ValueError('Selected Android coverage mismatch')
    return {'tests':len(records),'failures':0,'errors':0,'skipped':0}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan',type=Path,default=Path('test-plan.json'))
    parser.add_argument('--apks',type=Path,default=Path('ci-apks'))
    parser.add_argument('--output',type=Path,default=Path('app/build/outputs/androidTest-results/selected'))
    args=parser.parse_args(); plan=json.loads(args.plan.read_text()); validate_plan(plan)
    args.output.mkdir(parents=True,exist_ok=True)
    report={'schema':1,'profile':plan['profile'],'head':plan.get('head'),'status':'failed','phases':[]}
    adb=None
    try:
        devices=subprocess.run(['adb','devices'],capture_output=True,text=True,check=True,timeout=10).stdout
        serials=re.findall(r'^(emulator-\d+)\s+device$',devices,re.M)
        if len(serials)!=1: raise RuntimeError('Expected exactly one ready emulator')
        adb=Adb(serials[0])
        if adb.run('shell','getprop','sys.boot_completed').stdout.strip()!='1': raise RuntimeError('Emulator boot incomplete')
        for name in ['app-debug.apk','app-debug-androidTest.apk']:
            files=list(args.apks.rglob(name))
            if len(files)!=1: raise RuntimeError('Missing/ambiguous prebuilt APK: '+name)
            adb.run('install','-r','-t',str(files[0]),timeout=90)
        instruments=adb.run('shell','pm','list','instrumentation').stdout
        if 'instrumentation:'+COMPONENT+' (target='+APP+')' not in instruments:
            raise RuntimeError('Expected fixture runner is not installed')
        for name, expected in [('preflight',plan['preflight']),('remaining',plan['remaining'])]:
            if not expected: continue
            if report['phases'] and report['phases'][-1]['status']!='passed':
                blocked={'status':'blocked','profile':plan['profile'],'expected':expected,'started':[],
                         'records':{},'not_run':expected,'errors':[]}
                report['phases'].append(blocked); write_junit(blocked,args.output/(name+'.xml')); break
            cmd=adb.prefix+['shell','am','instrument','-w','-r','-e','class',','.join(expected),
                            '-e','hartbroProfile',plan['profile'],'-e','timeout_msec',
                            '1200000' if plan['profile']=='soak' else '120000',COMPONENT]
            print(f"Android {name}: {len(expected)} unique cases; profile={plan['profile']}",flush=True)
            report['phases'].append(execute_phase(expected,plan['profile'],cmd,args.output/(name+'.log'),adb.stop))
        report['status']='passed' if all(p['status']=='passed' for p in report['phases']) else 'failed'
        report['counts']=verify_result(plan,report)
        external='/sdcard/Android/data/'+APP+'/files/export-review'
        if adb.run('shell','test','-d',external,check=False).returncode==0:
            adb.run('pull',external+'/.','app/build/outputs/export-review/',timeout=60,check=False)
    except Exception as exc:
        report['status']='failed'; report['error']=str(exc)
        if not report['phases']:
            setup={'status':'failed','profile':plan['profile'],'expected':plan['android'],'started':[],
                   'records':{},'not_run':plan['android'],'errors':[str(exc)]}
            report['phases'].append(setup); write_junit(setup,args.output/'setup.xml')
        if adb: collect_diagnostics(adb,Path('app/build/outputs/export-review/diagnostics'))
    finally:
        (args.output/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    if report['status']!='passed':
        print('Android FAILED: '+report.get('error','See phase reports'),flush=True); return 1
    print('Android PASSED: '+json.dumps(report['counts'])+'; profile='+plan['profile'],flush=True)
    return 0

if __name__=='__main__': raise SystemExit(main())
