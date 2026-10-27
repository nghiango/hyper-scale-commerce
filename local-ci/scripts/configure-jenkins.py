#!/usr/bin/env python3
"""Configure the local controller without printing passwords, private keys or agent secrets."""
import base64
import http.cookiejar
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parents[2]
container = 'hyperscale-local-ci-jenkins-1'
deadline = time.monotonic() + 90
while True:
    try:
        with urllib.request.urlopen('http://localhost:8090/login', timeout=3) as ready:
            if ready.status == 200:
                break
    except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException):
        pass
    if time.monotonic() >= deadline:
        raise SystemExit('Jenkins did not become ready within 90 seconds')
    time.sleep(1)
key = root / 'local-ci/secrets/jenkins-gitops_ed25519'
env_file = root / 'local-ci/.env'
subprocess.run(['git', '-C', str(root), 'check-ignore', '--quiet', str(env_file)], check=True)
if not key.is_file():
    raise SystemExit('Dedicated Jenkins deploy key is missing')
password = subprocess.check_output(
    ['docker', 'exec', container, 'cat', '/var/jenkins_home/secrets/initialAdminPassword'],
    stderr=subprocess.PIPE, text=True).strip()
auth = 'Basic ' + base64.b64encode(('admin:' + password).encode()).decode()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
headers = {'Authorization': auth}
crumb_request = urllib.request.Request('http://localhost:8090/crumbIssuer/api/json', headers=headers)
with opener.open(crumb_request, timeout=30) as response:
    crumb = json.load(response)
headers[crumb['crumbRequestField']] = crumb['crumb']
subprocess.run(['docker', 'cp', str(key), container + ':/var/jenkins_home/secrets/hyperscale-gitops-import'],
               check=True, stdout=subprocess.DEVNULL)
subprocess.run(['docker', 'exec', '-u', '0', container, 'chown', '1000:1000',
                '/var/jenkins_home/secrets/hyperscale-gitops-import'], check=True)
subprocess.run(['docker', 'exec', '-u', '0', container, 'chmod', '600',
                '/var/jenkins_home/secrets/hyperscale-gitops-import'], check=True)
source_ready = False
try:
    with urllib.request.urlopen(
            'https://raw.githubusercontent.com/nghiango/hyper-scale-commerce/main/Jenkinsfile',
            timeout=15) as source:
        source_ready = source.status == 200
except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException):
    pass
script = (root / 'local-ci/jenkins/configure.groovy').read_text()
script = script.replace('__SOURCE_CI_AVAILABLE__', 'true' if source_ready else 'false')
body = urllib.parse.urlencode({'script': script}).encode()
request = urllib.request.Request('http://localhost:8090/scriptText', data=body, headers=headers)
try:
    with opener.open(request, timeout=60) as response:
        raw_result = response.read()
    try:
        result = json.loads(raw_result)
    except json.JSONDecodeError:
        diagnostic = root / 'local-ci/secrets/jenkins-configuration-error.txt'
        fd = os.open(diagnostic, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'wb') as handle:
            handle.write(raw_result)
        raise SystemExit('Jenkins configuration failed; protected diagnostic saved under local-ci/secrets/.')
finally:
    subprocess.run(['docker', 'exec', '-u', '0', container, 'rm', '-f',
                    '/var/jenkins_home/secrets/hyperscale-gitops-import'],
                   check=True, stdout=subprocess.DEVNULL)
secret = result.pop('agentSecret')
if not re.fullmatch('[0-9a-fA-F]+', secret):
    raise SystemExit('Unexpected agent secret format; not writing environment')
socket = Path.home() / '.docker/run/docker.sock'
if not socket.exists():
    socket = Path('/var/run/docker.sock')
lines = env_file.read_text().splitlines() if env_file.exists() else []
lines = [line for line in lines if not line.startswith(('JENKINS_AGENT_SECRET=', 'DOCKER_SOCKET='))]
lines += ['JENKINS_AGENT_SECRET=' + secret, 'DOCKER_SOCKET=' + str(socket)]
# Restrict permissions before writing any secret bytes, including on reruns.
fd = os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.fchmod(fd, 0o600)
with os.fdopen(fd, 'w') as handle:
    handle.write('\n'.join(lines) + '\n')
print(json.dumps(result))
print('Agent environment stored in ignored local-ci/.env with owner-only permissions.')
