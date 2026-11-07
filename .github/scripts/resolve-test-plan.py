#!/usr/bin/env python3
"""Resolve a safe CI comparison base, then write the exact test plan and small job outputs."""
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.test_plan import ROOT, changed_files, make_plan


def ancestor(base, head, root=ROOT):
    return subprocess.run(['git', 'merge-base', '--is-ancestor', base, head], cwd=root,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def verified_push_base(runs, head, is_ancestor):
    # A cancelled or failed immediately preceding commit is not an acceptable baseline.
    for run in runs:
        sha = run.get('head_sha', '')
        if (run.get('event') == 'push' and run.get('conclusion') == 'success'
                and sha and sha != head and is_ancestor(sha, head)):
            return sha
    return ''


def github_get(path):
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if not token:
        raise RuntimeError('No Actions token: use full coverage')
    req = urllib.request.Request('https://api.github.com/'+path,
        headers={'Authorization':'Bearer '+token, 'Accept':'application/vnd.github+json',
                 'X-GitHub-Api-Version':'2022-11-28'})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


def resolve(event_name, event, head, ref, api=github_get, is_ancestor=ancestor):
    repository = event.get('repository', {}).get('full_name', '')
    if not repository or len(repository.split('/')) != 2:
        return '', False, 'Repository context unavailable: full coverage'
    if event_name == 'pull_request':
        target = event['pull_request']['base']
        try:
            query = urllib.parse.urlencode({'branch':target['ref'],'status':'success','event':'push','per_page':100})
            runs = api(f'repos/{repository}/actions/workflows/android.yml/runs?'+query).get('workflow_runs', [])
            base = verified_push_base(runs, target['sha'], lambda a,b: a==b or is_ancestor(a,b))
            return base, False, 'Entire PR plus unverified target changes' if base else 'No verified PR target: full coverage'
        except Exception as exc:
            return '', False, f'PR baseline unavailable ({type(exc).__name__}): full coverage'

    if event_name == 'workflow_dispatch':
        return event.get('inputs', {}).get('base', ''), False, 'Explicit manual base, or full coverage'
    if event_name == 'push' and not ref.startswith('refs/tags/'):
        branch = ref.removeprefix('refs/heads/')
        try:
            owner = repository.split('/')[0]
            prs = api(f'repos/{repository}/pulls?'+urllib.parse.urlencode({'state':'open','head':owner+':'+branch,'per_page':100}))
            if not isinstance(prs, list): raise ValueError('Unexpected PR response')
            if prs:
                return '', True, 'Open PR owns validation; avoid duplicate push + PR jobs'
            query = urllib.parse.urlencode({'branch':branch,'status':'success','event':'push','per_page':100})
            runs = api(f'repos/{repository}/actions/workflows/android.yml/runs?'+query).get('workflow_runs', [])
            base = verified_push_base(runs, head, is_ancestor)
            return base, False, 'Last successful push ancestor' if base else 'No verified push ancestor: full coverage'
        except Exception as exc:
            # Never silently narrow selection when the API/network/history is unavailable.
            return '', False, f'Baseline lookup unavailable ({type(exc).__name__}): full coverage'
    return '', False, 'Full validation requested'


def main():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    event_name = os.environ['GITHUB_EVENT_NAME']
    head = os.environ['GITHUB_SHA']
    ref = os.environ['GITHUB_REF']
    mode = event.get('inputs', {}).get('mode', 'affected') if event_name == 'workflow_dispatch' else 'affected'
    if ref.startswith('refs/tags/'):
        mode = 'soak'
    base, redundant, note = resolve(event_name, event, head, ref)
    plan = make_plan([] if redundant else changed_files(base, head), mode)
    if redundant:
        plan = make_plan([], 'affected')
    plan.update({'base':base or None, 'head':head, 'event':event_name, 'baseline_reason':note, 'duplicate_push':redundant})
    Path('test-plan.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2)+'\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for name, value in {'build':plan['build_enabled'], 'android':plan['android_enabled'],
                            'sdk':plan['build_apk'] or bool(plan['usb_classes'])}.items():
            output.write(f'{name}={str(value).lower()}\n')
    summary = (f"Profile: {plan['profile']}; {note}\n"
               f"JVM classes: core={len(plan['core_classes'])}, USB={len(plan['usb_classes'])}; "
               f"Android cases={len(plan['android'])}, unique preflight={len(plan['preflight'])}; "
               f"background window={plan['background_window_ms']} ms\n")
    print(summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as f:
            f.write('## Selected checks\n\n'+summary+'\n\n```json\n'+json.dumps(plan,ensure_ascii=False,indent=2)+'\n```\n')

if __name__ == '__main__': main()
