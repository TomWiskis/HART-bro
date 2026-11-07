import json
import contextlib
import io
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from tools.android_instrumentation import InstrumentationResults, execute_phase, validate_plan, verify_result, write_junit

A='ru.hartbro.app.FakeTest#first'
B='ru.hartbro.app.FakeTest#second'


def attestation(profile='fast'):
    return 'INSTRUMENTATION_STATUS: hartbroProfile='+profile+'\nINSTRUMENTATION_STATUS: hartbroWindowMillis='+('300000' if profile=='soak' else '20000')+'\nINSTRUMENTATION_STATUS_CODE: 2\n'

def case(key,code=0):
    cls,name=key.split('#')
    block=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={name}\n'
    return block+'INSTRUMENTATION_STATUS_CODE: 1\n'+block+('INSTRUMENTATION_STATUS: stack=Assertion failed\n at fixture.kt:1\n' if code else '')+f'INSTRUMENTATION_STATUS_CODE: {code}\n'

def parse(text,expected=None,profile='fast'):
    p=InstrumentationResults(expected or [A],profile)
    for line in text.splitlines():p.feed(line)
    return p


class NativeProtocol(unittest.TestCase):
    def test_normal_success_requires_every_exact_case_once_and_runtime_profile(self):
        p=parse(attestation()+case(A)+case(B)+'INSTRUMENTATION_CODE: -1\n',[A,B])
        result=p.finish();self.assertEqual('passed',result['status']);self.assertEqual({A,B},set(result['records']))

    def test_adb_exit_zero_does_not_make_crashed_or_empty_instrumentation_green(self):
        for text in ['', 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\nINSTRUMENTATION_CODE: 0\n',attestation()+'INSTRUMENTATION_CODE: -1\n']:
            with self.subTest(text=text):self.assertEqual('failed',parse(text).finish(0)['status'])

    def test_missing_duplicate_unexpected_and_ignored_cases_fail_closed(self):
        for body in [case(A),case(A)+case(A),case(A)+case('ru.hartbro.app.Other#alien'),case(A)+case(B,-3)]:
            with self.subTest(body=body):self.assertEqual('failed',parse(attestation()+body+'INSTRUMENTATION_CODE: -1\n',[A,B]).finish()['status'])

    def test_failed_assertion_or_assumption_is_never_accepted_as_success(self):
        for code in [-1,-2,-3,-4]:
            p=parse(attestation()+case(A,code)+'INSTRUMENTATION_CODE: -1\n')
            self.assertTrue(p.failed);self.assertEqual('failed',p.finish()['status'])

    def test_soak_cannot_be_claimed_from_a_fast_or_unattested_runner(self):
        self.assertEqual('failed',parse(attestation('fast')+case(A)+'INSTRUMENTATION_CODE: -1\n',profile='soak').finish()['status'])
        self.assertEqual('failed',parse(case(A)+'INSTRUMENTATION_CODE: -1\n').finish()['status'])
        self.assertEqual('passed',parse(attestation('soak')+case(A)+'INSTRUMENTATION_CODE: -1\n',profile='soak').finish()['status'])

    def test_plan_refuses_duplicate_preflight_or_incorrect_profile_duration(self):
        p={'schema':1,'profile':'fast','background_window_ms':20000,'android':[A,B],'preflight':[A],'remaining':[B]}
        validate_plan(p)
        self.assertRaises(ValueError,validate_plan,{**p,'remaining':[A,B]})
        self.assertRaises(ValueError,validate_plan,{**p,'background_window_ms':1})

    def test_aggregate_verifier_rejects_repeated_passes_and_missing_selection(self):
        p={'schema':1,'profile':'fast','background_window_ms':20000,'android':[A,B],'preflight':[A],'remaining':[B]}
        phase=parse(attestation()+case(A)+'INSTRUMENTATION_CODE: -1\n').finish()
        report={'profile':'fast','status':'passed','phases':[phase,phase]}
        self.assertRaises(ValueError,verify_result,p,report)

    def test_release_gate_rejects_a_successful_fast_report(self):
        p={'schema':1,'profile':'fast','background_window_ms':20000,'android':[A],'preflight':[A],'remaining':[],
           'full_selection':True,'inventory':{'android':1}}
        phase=parse(attestation()+case(A)+'INSTRUMENTATION_CODE: -1\n').finish()
        report={'profile':'fast','status':'passed','phases':[phase]}
        self.assertEqual(1,verify_result(p,report)['tests'])
        self.assertRaises(ValueError,verify_result,p,report,require_soak=True)

    def test_junit_marks_unexecuted_cases_as_skipped_not_passed(self):
        r=parse(attestation()+case(A,-2),[A,B]).finish()
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'out.xml';write_junit(r,path);root=ET.parse(path).getroot()
            self.assertEqual('1',root.get('failures'));self.assertEqual('1',root.get('skipped'))
            self.assertIsNotNone(root.find("testcase[@name='second']/skipped"))

    def test_failure_report_remains_valid_xml_for_control_characters_in_stack(self):
        r=parse(attestation()+case(A,-2),[A]).finish()
        r['records'][A]['message']='bad\x00value\x1btrace'
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'out.xml';write_junit(r,path)
            self.assertIn('bad',ET.parse(path).getroot().find('testcase/failure').text)

    def test_live_failure_stops_instrumentation_and_never_executes_next_case(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);marker=p/'later';script=p/'fake-adb.py'
            script.write_text('import sys,time\nprint('+repr(attestation()+case(A,-2))+',flush=True)\ntime.sleep(2)\nfrom pathlib import Path\nPath('+repr(str(marker))+').write_text("ran")\n')
            stops=[]
            with contextlib.redirect_stdout(io.StringIO()):
                result=execute_phase([A,B],'fast',[sys.executable,str(script)],p/'raw.log',lambda:stops.append('stop'))
            self.assertEqual(['stop'],stops);self.assertFalse(marker.exists());self.assertTrue(result['aborted_early'])
            self.assertEqual([B],result['not_run']);self.assertEqual('failed',result['status'])

    def test_setup_watchdog_stops_a_hung_runner_before_other_tests(self):
        with tempfile.TemporaryDirectory() as tmp:
            stops=[]
            result=execute_phase([A],'fast',[sys.executable,'-c','import time;time.sleep(5)'],Path(tmp)/'raw.log',lambda:stops.append('stop'),setup_timeout=.1)
            self.assertEqual(['stop'],stops);self.assertTrue(any('watchdog' in e for e in result['errors']))


class AndroidCliGates(unittest.TestCase):
    def run_cli(self, mode):
        import os, subprocess
        from tools.test_plan import ROOT
        tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup);p=Path(tmp.name)
        (p/'bin').mkdir();(p/'apks').mkdir()
        for name in ['app-debug.apk','app-debug-androidTest.apk']:(p/'apks'/name).write_bytes(b'fixture')
        plan={'schema':1,'profile':'fast','background_window_ms':20000,'android':[A,B],'preflight':[A],'remaining':[B]}
        (p/'plan.json').write_text(json.dumps(plan))
        adb=p/'bin/adb'
        adb.write_text('#!'+sys.executable+'\n'+r'''
import sys,os,time,json
args=sys.argv[1:]
if args==['devices']:
 print('List of devices attached\nemulator-5554\tdevice');sys.exit(0)
args=args[2:]
if args[:2]==['shell','getprop']: print('1')
elif args and args[0]=='install':
 if os.environ['FAKE_MODE']=='install-failure':print('Failure [bad APK]');sys.exit(1)
 print('Success')
elif args[:4]==['shell','pm','list','instrumentation']:
 print('instrumentation:ru.hartbro.app.test/ru.hartbro.app.FieldRegressionRunner (target=ru.hartbro.app)')
elif args[:3]==['shell','am','instrument']:
 keys=args[args.index('class')+1].split(',')
 with open(os.environ['FAKE_TRACE'],'a') as f:f.write(json.dumps(keys)+'\n')
 print('INSTRUMENTATION_STATUS: hartbroProfile=fast\nINSTRUMENTATION_STATUS: hartbroWindowMillis=20000\nINSTRUMENTATION_STATUS_CODE: 2',flush=True)
 for key in keys:
  cls,name=key.split('#');block='INSTRUMENTATION_STATUS: class='+cls+'\nINSTRUMENTATION_STATUS: test='+name+'\n'
  print(block+'INSTRUMENTATION_STATUS_CODE: 1',flush=True)
  failed=os.environ['FAKE_MODE']=='assertion-failure'
  print(block+('INSTRUMENTATION_STATUS: stack=fixture assertion\n' if failed else '')+'INSTRUMENTATION_STATUS_CODE: '+('-2' if failed else '0'),flush=True)
  if failed:time.sleep(2)
 print('INSTRUMENTATION_CODE: -1',flush=True)
elif args[:2]==['shell','test']:sys.exit(1)
''')
        adb.chmod(0o755)
        env={**os.environ,'PATH':str(p/'bin')+os.pathsep+os.environ['PATH'],'FAKE_MODE':mode,'FAKE_TRACE':str(p/'trace')}
        result=subprocess.run([sys.executable,str(ROOT/'tools/android_instrumentation.py'),'--plan',str(p/'plan.json'),'--apks',str(p/'apks'),'--output',str(p/'out')],cwd=p,env=env,capture_output=True,text=True,timeout=20)
        report=json.loads((p/'out/result.json').read_text())
        trace=[json.loads(line) for line in (p/'trace').read_text().splitlines()] if (p/'trace').exists() else []
        return result,report,trace,p

    def test_success_runs_disjoint_phases_once_and_checks_the_native_report(self):
        process,report,trace,_=self.run_cli('success')
        self.assertEqual(0,process.returncode,process.stdout+process.stderr)
        self.assertEqual([[A],[B]],trace);self.assertEqual(2,report['counts']['tests'])
        self.assertEqual('passed',report['status'])

    def test_preflight_assertion_never_launches_remaining_suite(self):
        process,report,trace,p=self.run_cli('assertion-failure')
        self.assertEqual(1,process.returncode);self.assertEqual([[A]],trace)
        self.assertEqual('blocked',report['phases'][1]['status'])
        self.assertIsNotNone(ET.parse(p/'out/remaining.xml').getroot().find('testcase/skipped'))

    def test_failed_apk_install_never_runs_any_test(self):
        process,report,trace,_=self.run_cli('install-failure')
        self.assertEqual(1,process.returncode);self.assertEqual([],trace)
        self.assertEqual([A,B],report['phases'][0]['not_run'])

if __name__=='__main__':unittest.main()
