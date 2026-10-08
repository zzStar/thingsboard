#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

"""Reuse the local Docker credential for this registry, without printing secrets."""
import base64
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

try:
    registry, host = sys.argv[1:]
    config = json.loads((Path(os.environ.get('DOCKER_CONFIG', str(Path.home() / '.docker'))) / 'config.json').read_text())
    key = next((k for k in config.get('auths', {}) if k.rstrip('/').removeprefix('https://') == registry), registry)
    helper = config.get('credHelpers', {}).get(registry) or config.get('credsStore')
    if helper:
        result = subprocess.run(['docker-credential-' + helper, 'get'], input=key,
                                text=True, capture_output=True, check=True)
        credential = json.loads(result.stdout)
        username, password = credential['Username'], credential['Secret']
    else:
        username, password = base64.b64decode(config['auths'][key]['auth']).decode().split(':', 1)
    command = ['docker', 'login', registry, '--username', username, '--password-stdin']
    if host != 'local':
        command = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host, shlex.join(command)]
    result = subprocess.run(command, input=password + '\n', text=True, capture_output=True)
    if result.returncode:
        sys.exit('Registry login failed; verify Docker credentials and registry access.')
    print('Registry authentication verified: ' + host)
except (OSError, KeyError, ValueError, subprocess.CalledProcessError):
    sys.exit("Cannot read Docker registry credentials. Run docker login registry.cn-hangzhou.aliyuncs.com first.")
