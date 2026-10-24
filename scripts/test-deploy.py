#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

"""Offline fault injection against the actual deployment worker, never SSH or production."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
OLD = 'example/things@sha256:' + 'a' * 64
NEW = 'example/things@sha256:' + 'b' * 64
FAKE = r'''#!/usr/bin/env python3
import json, os, sys, time, signal
from pathlib import Path
base=Path(os.environ['TB_DEPLOY_BASE']); file=base/'fake.json'
s=json.loads(file.read_text()); a=sys.argv[1:]; command=Path(sys.argv[0]).name
s['events'].append(command+' '+' '.join(a))
def save(): file.write_text(json.dumps(s))
def die(code=1): save(); sys.exit(code)
failure=s.get('failure','')
if command=='curl':
    url=a[-1]
    if 'https://' in url and failure=='https': die(22)
    if '/setup/state' in url:
        if s['running']=='bad': die(22)
        print(json.dumps({'status':s.get('setup','READY')}))
    else: print('<html>智工物联</html>')
    die(0)
if command=='sleep': time.sleep(.01); die(0)
if a[:2]==['image','inspect']:
    fmt=a[a.index('--format')+1] if '--format' in a else ''
    image=a[2]
    if not fmt:
        labels={} if s.get('unknown') else {'com.zhigong.compatibility':s.get('old_compatibility',s['compatibility']),'com.zhigong.production-schema':s['production_schema']}
        print(json.dumps([{'Config':{'Labels':labels}}]))
    elif 'RepoDigests' in fmt: print(s['old'] if image==s['old'] else s['new'])
    elif 'com.zhigong.schema' in fmt: print(s.get('expected','4004000000'))
    elif 'com.zhigong.release-policy' in fmt: print('<no value>' if image==s['old'] and s.get('legacy_target') else s['policy_hash'])
    die(0)
if a and a[0]=='run':
    print((base/'policy.json').read_text(),end=''); die(0)
if a and a[0]=='inspect':
    print(s['old'] if s['running']=='old' else s['new']); die(0)
if a and a[0]=='compose':
    release=Path(a[a.index('-f')+1]).parent.name
    cmd=a[a.index('-f')+2:]
    if cmd[0]=='config':
        if '--format' in cmd:
            url='jdbc:postgresql://postgres:5432/thingsboard'
            if s.get('database_drift') and release=='new': url='jdbc:postgresql://postgres:5432/other'
            print(json.dumps({'services':{'postgres':{'image': 'postgres:18.1' if not (s.get('infra') and release=='new') else 'postgres:19'},'caddy':{'image':'caddy:2.10.2'},'thingsboard':{'environment':{'SPRING_DATASOURCE_URL':url}}}}))
        die(0)
    if cmd[0]=='pull': die(0)
    if cmd[0]=='ps': print('test-container'); die(0)
    if cmd[0]=='up':
        if 'thingsboard' in cmd:
            image=os.environ['APP_IMAGE']
            s['running']='old' if image==s['old'] else 'new'
            if image!=s['old']: s['production_version']=s['target_production_version']
            if failure=='start' and image!=s['old']: s['running']='bad'
            if s.get('kill')=='start' and not s.get('killed'):
                s['killed']=True; save(); os.kill(os.getppid(),signal.SIGKILL); sys.exit(0)
        die(0)
    if cmd[0]=='stop':
        s['running']='stopped'
        if s.get('kill')=='stop' and not s.get('killed'):
            s['killed']=True; save(); os.kill(os.getppid(),signal.SIGKILL); sys.exit(0)
        if failure=='stop': die()
        die(0)
    if cmd[0]=='run':
        if 'install' in cmd: s['tables']=10; s['schema']=s.get('expected','4004000000')
        if 'upgrade' in cmd:
            if failure=='upgrade': die()
            s['schema']=s['expected']
        die(0)
    if cmd[0]=='exec':
        if 'pg_isready' in cmd: die(0)
        if 'psql' in cmd:
            sql=cmd[-1]
            if 'count(*)' in sql: print(s.get('tables',10))
            elif 'to_regclass' in sql: print('t' if s.get('production_version') else 'f')
            elif 'production_schema_settings' in sql: print(s.get('production_version',0))
            elif 'schema_version' in sql: print(s.get('schema','4004000000'))
            elif 'pg_database_size' in sql: print(1024)
            elif 'SELECT 1' in sql: print(1)
            die(0)
        if 'pg_dump' in cmd:
            if failure=='backup': die()
            print('database archive'); die(0)
        if 'pg_restore' in cmd: sys.stdin.read(); die(0)
        if 'caddy' in cmd:
            if 'sh' in cmd:
                contents=sys.stdin.read(); s['proxy']='maintenance' if '服务更新中' in contents else 'normal'
                if failure=='proxy' and s['proxy']=='maintenance': die()
            die(0)
        die(0)
    die(0)
die(0)
'''


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='zg-deploy-test-')
        self.base = Path(self.tmp.name)
        for version in ('old', 'new'):
            release = self.base / 'releases' / version
            release.mkdir(parents=True)
            for filename in ('compose.yml', 'Caddyfile', 'Caddyfile.maintenance'):
                shutil.copy(ROOT / 'deploy' / filename, release / filename)
        self.bin = self.base / 'bin'; self.bin.mkdir()
        for cmd in ('docker', 'curl', 'sleep'):
            path = self.bin / cmd; path.write_text(FAKE); path.chmod(0o755)
        (self.bin / 'flock').write_text('#!/usr/bin/env python3\nimport fcntl,sys\nfcntl.flock(int(sys.argv[-1]),fcntl.LOCK_EX|fcntl.LOCK_NB)\n')
        (self.bin / 'flock').chmod(0o755)
        (self.base / 'socket.py').write_text('''class Connection:
 def __enter__(self): return self
 def __exit__(self,*args): pass
 def settimeout(self,*args): pass
 def sendall(self,*args): pass
 def recv(self,*args): return b'\\x20\\x02\\x00\\x05'
def create_connection(*args): return Connection()
''')
        policy = (ROOT / 'deploy/release-policy.json').read_bytes()
        (self.base / 'policy.json').write_bytes(policy)
        self.data = {'events': [], 'running': 'old', 'old': OLD, 'new': NEW,
                     'policy_hash': hashlib.sha256(policy).hexdigest(),
                     'production_schema': json.loads(policy)['production_schema_sha256'],
                     'compatibility': json.loads(policy)['compatibility'],
                     'target_production_version': json.loads(policy)['production_schema_version']}
        self.write()
        (self.base / '.env').write_text('POSTGRES_PASSWORD=test-only\n')
        (self.base / 'current').write_text(f'VERSION=old\nAPP_IMAGE={OLD}\nSCHEMA=4004000000\n')
        self.env = {**os.environ, 'TB_DEPLOY_BASE': str(self.base), 'TB_DEPLOY_REPO': 'example/things',
                    'TB_DEPLOY_DOMAIN': 'test.invalid', 'TB_DEPLOY_READY_TIMEOUT': '1',
                    'TB_DEPLOY_OBSERVE_SECONDS': '0', 'PATH': str(self.bin)+os.pathsep+os.environ['PATH'],
                    'TB_DEPLOY_PUBLIC_TIMEOUT': '1',
                    'PYTHONPATH': str(self.base)}

    def tearDown(self): self.tmp.cleanup()
    def write(self): (self.base/'fake.json').write_text(json.dumps(self.data))
    def read(self): return json.loads((self.base/'fake.json').read_text())
    def run_worker(self, action='deploy', version='new', from_version='', mode='single'):
        return subprocess.run(['bash', str(ROOT/'scripts/deploy-remote.sh'), action, version, from_version, mode],
                              env=self.env, text=True, capture_output=True, timeout=20)
    def state(self): return (self.base/'deployment.state').read_text()
    def assert_pass(self, result): self.assertEqual(0, result.returncode, result.stdout+result.stderr)

    def test_online_backup_precedes_stop_and_infrastructure_untouched(self):
        self.assert_pass(self.run_worker())
        events=self.read()['events']
        backup=next(i for i,e in enumerate(events) if 'pg_dump' in e)
        stop=next(i for i,e in enumerate(events) if ' stop thingsboard' in e)
        self.assertLess(backup,stop)
        self.assertFalse(any(' up ' in e and e.endswith('postgres') for e in events))
        self.assertIn('PHASE=COMMITTED',self.state())
        self.assertIn('VERSION=new',(self.base/'current').read_text())

    def test_backup_failure_leaves_old_application_and_proxy(self):
        self.data['failure']='backup'; self.write()
        result=self.run_worker(); self.assertNotEqual(0,result.returncode)
        self.assertEqual('old',self.read()['running'])
        self.assertFalse(any(' stop thingsboard' in e for e in self.read()['events']))
        self.assertIn('PHASE=FAILED',self.state())

    def test_start_failure_restores_old_without_database_restore(self):
        self.data['failure']='start'; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertEqual('old',self.read()['running'])
        self.assertIn('PHASE=RESTORED',self.state())
        self.assertEqual('normal',self.read()['proxy'])
        self.assertFalse(any('pg_restore' in e and '--list' not in e for e in self.read()['events']))

    def test_unknown_legacy_uses_stopped_backup_and_no_speculative_rollback(self):
        self.data.update(unknown=True,failure='start'); self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertIn('PHASE=MANUAL_RECOVERY',self.state())
        events=self.read()['events']
        self.assertLess(next(i for i,e in enumerate(events) if ' stop thingsboard' in e),
                        next(i for i,e in enumerate(events) if 'pg_dump' in e))
        self.assertNotEqual('old',self.read()['running'])
        self.assertNotEqual(0,self.run_worker().returncode)

    def test_infrastructure_change_rejected_before_stop(self):
        self.data['infra']=True; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertEqual('old',self.read()['running'])
        self.assertFalse(any(' stop ' in e for e in self.read()['events']))

    def test_newer_production_schema_is_rejected_before_stop(self):
        self.data['production_version']=self.data['target_production_version']+1; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertFalse(any(' stop ' in e for e in self.read()['events']))

    def test_schema_two_image_uses_maintenance_and_is_not_restored_after_schema_three_start(self):
        self.data.update(old_compatibility='tb44-production-v1',production_version=2,failure='start'); self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertIn('PHASE=MANUAL_RECOVERY',self.state())
        events=self.read()['events']
        self.assertLess(next(i for i,e in enumerate(events) if ' stop thingsboard' in e),
                        next(i for i,e in enumerate(events) if 'pg_dump' in e))
        self.assertNotEqual('old',self.read()['running'])

    def test_changed_application_database_target_is_rejected_before_stop(self):
        self.data['database_drift']=True; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertEqual('old',self.read()['running'])
        self.assertFalse(any(' stop ' in e for e in self.read()['events']))

    def test_policy_hash_mismatch_fails_before_stop_and_cleans_expanded_secrets(self):
        self.data['policy_hash']='0'*64; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertEqual('old',self.read()['running'])
        self.assertIn('PHASE=FAILED',self.state())
        self.assertFalse(list(self.base.glob('plan.*')))

    def test_reused_version_with_another_digest_is_rejected(self):
        (self.base/'releases/new/release.env').write_text('APP_IMAGE='+OLD+'\n')
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertFalse(any(' stop ' in e for e in self.read()['events']))

    def test_explicitly_reviewed_legacy_rollback_uses_current_policy_and_maintenance_template(self):
        policy=json.loads((self.base/'policy.json').read_text())
        policy['compatible_legacy_digests'].append(OLD.split('@')[1])
        raw=json.dumps(policy).encode()
        (self.base/'policy.json').write_bytes(raw)
        self.data['policy_hash']=hashlib.sha256(raw).hexdigest(); self.write()
        self.assert_pass(self.run_worker())
        (self.base/'releases/old/release.env').write_text('VERSION=old\nAPP_IMAGE='+OLD+'\n')
        (self.base/'releases/old/Caddyfile.maintenance').unlink()
        self.data=self.read(); self.data['legacy_target']=True; self.write()
        self.assert_pass(self.run_worker('rollback','old'))
        self.assertEqual('old',self.read()['running'])
        self.assertIn('VERSION=old',(self.base/'current').read_text())

    def test_public_failure_restores_old_application(self):
        self.data['failure']='https'; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertEqual('old',self.read()['running'])
        self.assertIn('PHASE=RESTORED',self.state())

    def test_interrupted_stop_is_reconciled_before_retry(self):
        self.data['kill']='stop'; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertIn('PHASE=STOPPING',self.state())
        self.assert_pass(self.run_worker())
        self.assertEqual('new',self.read()['running'])

    def test_interrupted_start_restores_then_retries(self):
        self.data['kill']='start'; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertIn('PHASE=STARTING',self.state())
        self.assert_pass(self.run_worker())
        self.assertEqual('new',self.read()['running'])

    def test_upgrade_failure_never_restores_database_or_old_application(self):
        self.data.update(expected='4005000000',failure='upgrade'); self.write()
        self.assertNotEqual(0,self.run_worker(from_version='4.4.0').returncode)
        self.assertIn('MIGRATED=true',self.state())
        self.assertIn('PHASE=MANUAL_RECOVERY',self.state())
        self.assertEqual('stopped',self.read()['running'])

    def test_initial_install_accepts_account_required_only_for_empty_new_database(self):
        (self.base/'current').unlink()
        self.data.update(tables=0,setup='ACCOUNT_REQUIRED'); self.write()
        self.assert_pass(self.run_worker())
        self.assertTrue(any(' install' in e for e in self.read()['events']))

    def test_existing_release_rejects_account_required(self):
        self.data['setup']='ACCOUNT_REQUIRED'; self.write()
        self.assertNotEqual(0,self.run_worker().returncode)
        self.assertFalse(any(' stop ' in e for e in self.read()['events']))

    def test_backup_command_is_online_and_does_not_change_journal(self):
        self.assert_pass(self.run_worker('backup',''))
        self.assertEqual('old',self.read()['running'])
        self.assertFalse((self.base/'deployment.state').exists())

    def test_plan_never_stops_or_pulls(self):
        self.assert_pass(self.run_worker('plan'))
        self.assertFalse(any(' stop ' in e or ' pull ' in e for e in self.read()['events']))

    def test_same_release_retry_is_idempotent(self):
        self.assert_pass(self.run_worker())
        self.data=self.read(); self.data['events']=[]; self.write()
        self.assert_pass(self.run_worker())
        self.assertFalse(any(' stop ' in e or ' up ' in e for e in self.read()['events']))

    def test_job_runs_in_background_and_reports_result(self):
        for filename in ('deploy-remote.sh','deploy-policy.py','deploy-job.sh'):
            shutil.copy(ROOT/'scripts'/filename,self.base/filename)
        result=subprocess.run(['bash',str(self.base/'deploy-job.sh'),'start','deploy','new','','single'],
                              env=self.env,text=True,capture_output=True,timeout=5)
        self.assert_pass(result); job=result.stdout.strip(); directory=self.base/'jobs'/job
        deadline=time.monotonic()+10
        while not (directory/'result').exists() and time.monotonic()<deadline: time.sleep(.05)
        self.assertEqual('0\n',(directory/'result').read_text(),(directory/'output.log').read_text())
        poll=subprocess.run(['bash',str(self.base/'deploy-job.sh'),'poll',job],env=self.env,text=True,capture_output=True)
        self.assertTrue(poll.stdout.startswith('DONE 0'))


if __name__=='__main__': unittest.main(verbosity=2)
