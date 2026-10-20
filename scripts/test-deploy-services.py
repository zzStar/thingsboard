#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

"""Real isolated PostgreSQL backup/restore and Caddy reload verification. No SSH."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
CADDY=os.environ.get('TEST_CADDY_IMAGE','m.daocloud.io/docker.io/library/caddy:2.10.2')
POSTGRES=os.environ.get('TEST_POSTGRES_IMAGE','postgres:18.1')
PREFIX='zg-update-test-'+uuid.uuid4().hex[:10]
containers=[]


def docker(*args, data=None):
    result=subprocess.run(['docker',*args],input=data,capture_output=True)
    if result.returncode:
        raise RuntimeError('Docker test command failed: '+result.stderr.decode()[-1500:])
    return result.stdout


def request(url):
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url,timeout=3) as response:
            return response.status,dict(response.headers),response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code,dict(error.headers),error.read().decode()


def wait_for(url,code):
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        try:
            result=request(url)
            if result[0]==code: return result
        except (OSError,ValueError): pass
        time.sleep(.2)
    raise AssertionError('Unexpected response: '+url+' last='+repr(locals().get('result')))


with tempfile.TemporaryDirectory(prefix=PREFIX) as directory:
    root=Path(directory)
    # Docker Desktop exposes /tmp under /private/tmp; use the resolved path for mounts.
    root=root.resolve()
    network=PREFIX
    try:
        docker('network','create',network)
        upstream=root/'upstream'; upstream.write_text(':8080 {\n respond /api/noauth/setup/state `{"status":"READY"}`\n respond "upstream OK"\n}\n')
        proxy=root/'proxy'; proxy.mkdir()
        normal=(ROOT/'deploy/Caddyfile').read_text().replace('{$DOMAIN}',':8080')
        maintenance=(ROOT/'deploy/Caddyfile.maintenance').read_text().replace('{$DOMAIN}',':8080')
        (proxy/'Caddyfile').write_text(normal)
        for name,config,alias in [('upstream',upstream,'thingsboard')]:
            container=PREFIX+'-'+name; containers.append(container)
            docker('run','-d','--name',container,'--network',network,'--network-alias',alias,
                   '-v',str(config)+':/etc/caddy/Caddyfile:ro',CADDY)
        # Simulate the existing single-file bind mount during the first transition.
        container=PREFIX+'-proxy'; containers.append(container)
        docker('run','-d','--name',container,'--network',network,'-p','127.0.0.1::8080',
               '-v',str(proxy/'Caddyfile')+':/etc/caddy/Caddyfile:ro',CADDY)
        port=docker('port',container,'8080/tcp').decode().strip().split(':')[-1]
        url='http://127.0.0.1:'+port
        assert json.loads(wait_for(url+'/api/noauth/setup/state',200)[2])['status']=='READY'
        def reload(config):
            docker('exec','-i',container,'sh','-c','cat > /config/candidate && caddy validate --config /config/candidate --adapter caddyfile',data=config.encode())
            docker('exec',container,'caddy','reload','--config','/config/candidate','--adapter','caddyfile')
        reload(maintenance)
        status,headers,body=wait_for(url+'/api/device/token/telemetry',503)
        assert json.loads(body)['status']==503 and headers['Retry-After']=='30' and headers['Cache-Control']=='no-store'
        assert request(url+'/')[0]==503
        reload(normal)
        assert wait_for(url+'/api/noauth/setup/state',200)[2]=='{"status":"READY"}'
        docker('rm','-f','-v',container)
        # Verify the new stable directory binding sees an atomic replacement on restart.
        docker('run','-d','--name',container,'--network',network,'-p','127.0.0.1::8080',
               '-v',str(proxy)+':/etc/caddy:ro',CADDY)
        port=docker('port',container,'8080/tcp').decode().strip().split(':')[-1]; url='http://127.0.0.1:'+port
        wait_for(url+'/',200)
        temporary=proxy/'Caddyfile.tmp'; temporary.write_text(maintenance); temporary.replace(proxy/'Caddyfile')
        docker('restart',container)
        port=docker('port',container,'8080/tcp').decode().strip().split(':')[-1]; url='http://127.0.0.1:'+port
        wait_for(url+'/api/test',503)
        temporary.write_text(normal); temporary.replace(proxy/'Caddyfile')
        docker('exec',container,'caddy','reload','--config','/etc/caddy/Caddyfile','--adapter','caddyfile')
        wait_for(url+'/',200)
        print('PASS: real Caddy legacy reload, JSON 503/Retry-After, directory mount and restart/recovery')

        db=PREFIX+'-db'; containers.append(db)
        env=root/'postgres.env'; env.write_text('POSTGRES_PASSWORD='+uuid.uuid4().hex+'\nPOSTGRES_DB=source\n'); env.chmod(0o600)
        docker('run','-d','--name',db,'--env-file',str(env),POSTGRES)
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            check=subprocess.run(['docker','exec',db,'pg_isready','-h','127.0.0.1','-U','postgres','-d','source'],capture_output=True)
            if check.returncode==0: break
            time.sleep(.3)
        fixture="CREATE TABLE device(id uuid PRIMARY KEY,tenant_id uuid,name text,label text);\n"+(ROOT/'application/src/main/resources/production-schema.sql').read_text()
        fixture+="\nINSERT INTO device VALUES ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','test',NULL);\n"
        fixture+="INSERT INTO production_repair VALUES ('00000000-0000-0000-0000-000000000001',100,200,100,100,0,0,'PENDING');\n"
        docker('exec','-i',db,'psql','-U','postgres','-d','source','-v','ON_ERROR_STOP=1',data=fixture.encode())
        # Keep the source DB writable while taking its consistent snapshot.
        dump=docker('exec',db,'pg_dump','-U','postgres','-d','source','-Fc','--lock-wait-timeout=10s')
        docker('exec','-i',db,'pg_restore','--list',data=dump)
        docker('exec',db,'createdb','-U','postgres','restored')
        docker('exec','-i',db,'pg_restore','-U','postgres','-d','restored','--exit-on-error',data=dump)
        restored=docker('exec',db,'psql','-U','postgres','-d','restored','-Atc','SELECT cursor_ts FROM production_repair;').decode().strip()
        assert restored=='100'
        docker('exec',db,'psql','-U','postgres','-d','source','-v','ON_ERROR_STOP=1','-c',"UPDATE production_repair SET cursor_ts=150;")
        assert docker('exec',db,'psql','-U','postgres','-d','restored','-Atc','SELECT cursor_ts FROM production_repair;').decode().strip()=='100'
        print('PASS: real PostgreSQL 18 custom archive full restore, extension schema, snapshot boundary')
    finally:
        for name in containers:
            subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
        subprocess.run(['docker','network','rm',network],capture_output=True)
