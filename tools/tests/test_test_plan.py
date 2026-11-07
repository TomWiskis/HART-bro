import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from tools.test_plan import ROOT, changed_files, make_plan, validate_catalog, test_methods


def script(name):
    spec=importlib.util.spec_from_file_location(name.replace('-','_'),ROOT/'.github/scripts'/name)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

resolve=script('resolve-test-plan.py')
build=script('build-selected.py')


class TestSelection(unittest.TestCase):
    def test_documentation_does_not_install_sdk_build_or_boot_android(self):
        p=make_plan(['README.md','docs/ZERO_0.6.0.md'])
        self.assertFalse(p['build_enabled']);self.assertFalse(p['android_enabled']);self.assertEqual([],p['core_classes'])

    def test_zero_ui_uses_only_its_scenarios_no_unrelated_pdf_or_core_tests(self):
        p=make_plan(['app/src/main/kotlin/ru/hartbro/app/ZeroScreen.kt'])
        self.assertEqual(3,len(p['android']));self.assertFalse(p['core_classes']);self.assertFalse(p['usb_classes'])
        self.assertTrue(all('Zero' in c or 'compactHistory' in c for c in p['android']))
        self.assertFalse(any('ReportExport' in c for c in p['android']))

    def test_pdf_does_not_run_excel_or_usb_or_range(self):
        p=make_plan(['app/src/main/kotlin/ru/hartbro/app/reports/SessionPdf.kt'])
        self.assertEqual(4,len(p['android']));self.assertTrue(all('ReportExportTest' in c for c in p['android']))
        self.assertFalse(any('Excel' in c for c in p['android']));self.assertFalse(p['usb_classes'])

    def test_single_jvm_test_change_does_not_boot_emulator_or_build_apks(self):
        p=make_plan(['hart-core/src/test/kotlin/ru/hartbro/core/HartCodecTest.kt'])
        self.assertEqual(['ru.hartbro.core.HartCodecTest'],p['core_classes'])
        self.assertFalse(p['android_enabled']);self.assertFalse(p['build_apk'])

    def test_usb_change_keeps_both_write_guards_and_recovery(self):
        p=make_plan(['transport-usb/src/main/kotlin/ru/hartbro/usb/UsbHartTransport.kt'])
        self.assertIn('ru.hartbro.core.RangeWriteTest',p['core_classes'])
        self.assertIn('ru.hartbro.core.SensorOperations053Test',p['core_classes'])
        self.assertTrue(any('usbDetach' in c for c in p['android']))
        self.assertTrue(any('unknownPressureModelZero' in c for c in p['android']))

    def test_unknown_production_and_unavailable_diff_expand_to_all(self):
        for files in [None,['hart-core/src/main/kotlin/NewEngine.kt'],['settings.gradle.kts'],['.github/workflows/android.yml']]:
            with self.subTest(files=files):
                p=make_plan(files);self.assertTrue(p['full_selection']);self.assertEqual(35,len(p['android']))
                self.assertEqual(26,len(p['core_classes']));self.assertEqual(4,len(p['usb_classes']))

    def test_full_fast_and_soak_have_same_checks_but_explicit_real_wall_times(self):
        fast=make_plan([],mode='full');soak=make_plan([],mode='soak')
        self.assertEqual(fast['android'],soak['android']);self.assertEqual(20000,fast['background_window_ms'])
        self.assertEqual(300000,soak['background_window_ms']);self.assertEqual('soak',soak['profile'])
        self.assertRaises(ValueError,make_plan,[],mode='pretend-soak')

    def test_preflight_is_disjoint_and_every_case_runs_once(self):
        p=make_plan(None)
        self.assertEqual(1,len(p['preflight']));self.assertEqual(34,len(p['remaining']))
        self.assertFalse(set(p['preflight'])&set(p['remaining']))
        self.assertEqual(set(p['android']),set(p['preflight']+p['remaining']))

    def test_real_catalogue_covers_every_existing_test(self):
        a,c,u,_=validate_catalog();self.assertEqual((35,247,18),(len(a),len(c),len(u)))
        catalog=json.loads((ROOT/'tools/test_catalog.json').read_text())
        self.assertRaises(ValueError,validate_catalog,ROOT,catalog[:-1])
        self.assertRaises(ValueError,validate_catalog,ROOT,catalog+[catalog[0]])

    def test_unsupported_or_nested_test_declarations_cannot_disappear_silently(self):
        for source in ['class T { @Test fun `late xor`() {} }',
                       'class T { class Other { @Test fun hidden() {} } }',
                       'class T { fun helper() { @Test fun hidden() {} } }']:
            with self.subTest(source=source):self.assertRaises(ValueError,test_methods,source,'T')
        self.assertEqual(['visible'],test_methods('class T { private class Wire {} /* @Test */ @Test fun visible() {} }','T'))

    def test_shared_android_fixture_expands_to_all(self):
        p=make_plan(['app/src/androidTest/kotlin/ru/hartbro/app/FieldRegressionTest.kt'])
        self.assertEqual(35,len(p['android']))

    def test_selected_gradle_test_tasks_fail_fast_and_do_not_build_unrelated_apk(self):
        p=make_plan(['hart-core/src/test/kotlin/ru/hartbro/core/HartCodecTest.kt'])
        args=build.command(p);self.assertIn('--fail-fast',args);self.assertNotIn(':app:assembleDebug',args)
        self.assertNotIn(':transport-usb:testDebugUnitTest',args)

    def test_git_rename_includes_old_and_new_scope_and_missing_base_is_conservative(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);subprocess.run(['git','init','-q'],cwd=p,check=True)
            subprocess.run(['git','-c','user.name=test','-c','user.email=test@example.invalid','commit','--allow-empty','-qm','base'],cwd=p,check=True)
            (p/'old.kt').write_text('x');subprocess.run(['git','add','.'],cwd=p,check=True)
            subprocess.run(['git','-c','user.name=test','-c','user.email=test@example.invalid','commit','-qm','add'],cwd=p,check=True)
            base=subprocess.check_output(['git','rev-parse','HEAD'],cwd=p,text=True).strip()
            (p/'old.kt').rename(p/'new.kt');subprocess.run(['git','add','-A'],cwd=p,check=True)
            subprocess.run(['git','-c','user.name=test','-c','user.email=test@example.invalid','commit','-qm','rename'],cwd=p,check=True)
            self.assertEqual({'old.kt','new.kt'},set(changed_files(base,root=p)))
            self.assertIsNone(changed_files('0'*40,root=p));self.assertIsNone(changed_files('missing',root=p))


class VerifiedBase(unittest.TestCase):
    def test_cancelled_failed_and_nonancestor_commits_cannot_hide_unverified_changes(self):
        runs=[{'event':'push','conclusion':'cancelled','head_sha':'cancelled'},
              {'event':'push','conclusion':'failure','head_sha':'broken'},
              {'event':'push','conclusion':'success','head_sha':'other-branch'},
              {'event':'workflow_dispatch','conclusion':'success','head_sha':'partial-manual'},
              {'event':'push','conclusion':'success','head_sha':'known'}]
        self.assertEqual('known',resolve.verified_push_base(runs,'head',lambda a,b:a=='known'))

    def test_no_token_or_api_error_selects_full_not_empty(self):
        def fail(path):raise OSError('network')
        base,skip,note=resolve.resolve('push',{'repository':{'full_name':'TomWiskis/HART-bro'}},'head','refs/heads/feature/test',api=fail)
        self.assertEqual('',base);self.assertFalse(skip);self.assertIn('full coverage',note)

    def test_pr_includes_unverified_target_changes_and_uses_only_successful_ancestors(self):
        event={'repository':{'full_name':'TomWiskis/HART-bro'},'pull_request':{'base':{'sha':'pending-base','ref':'main'}}}
        api=lambda path:{'workflow_runs':[{'event':'push','conclusion':'success','head_sha':'known-base'}]}
        base,skip,note=resolve.resolve('pull_request',event,'merge-head','refs/pull/1/merge',api=api,is_ancestor=lambda a,b:a=='known-base' and b=='pending-base')
        self.assertEqual('known-base',base);self.assertFalse(skip);self.assertIn('unverified target',note)

    def test_open_pr_skips_only_duplicate_push(self):
        base,skip,_=resolve.resolve('push',{'repository':{'full_name':'TomWiskis/HART-bro'}},'head','refs/heads/feature/test',api=lambda path:[{'number':1}])
        self.assertTrue(skip)

if __name__=='__main__':unittest.main()
