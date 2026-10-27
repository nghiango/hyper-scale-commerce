#!/usr/bin/env python3
"""Verify release scopes select the correct images without contacting Docker."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='hyperscale-publish-test-') as temp:
    folder = Path(temp)
    docker = folder / 'docker'
    docker.write_text('#!/usr/bin/env python3\nimport json,os,sys\n'
                      'with open(os.environ["IMAGE_TEST_LOG"], "a") as f: '
                      'f.write(json.dumps(sys.argv[1:])+"\\n")\n')
    docker.chmod(0o755)
    for scope, expected in [('application', {'app', 'order-query'}),
                            ('infrastructure', {'patroni'}),
                            ('platform', set()),
                            ('bootstrap', {'app', 'order-query', 'patroni'})]:
        log = folder / f'{scope}.jsonl'
        env = os.environ.copy()
        env.update(PATH=f'{folder}:{env["PATH"]}', IMAGE_TEST_LOG=str(log),
                   RELEASE_SCOPE=scope, PUSH_IMAGES='true',
                   REGISTRY='registry.local:5000', IMAGE_PREFIX='registry.local:5000/hyperscale',
                   REVISION='0123456789012345678901234567890123456789')
        subprocess.run(['bash', 'local-ci/scripts/publish.sh'], cwd=root, env=env, check=True)
        commands = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        builds = [cmd for cmd in commands if cmd[:2] == ['buildx', 'build']]
        names = {cmd[cmd.index('--tag')+1].split('/hyperscale-')[1].split(':')[0] for cmd in builds}
        assert names == expected, (scope, names)
        assert all('--push' in cmd and cmd[cmd.index('--platform')+1] == 'linux/arm64' for cmd in builds)
print('Scoped ARM64 image publication selection passed without Docker/network access.')
