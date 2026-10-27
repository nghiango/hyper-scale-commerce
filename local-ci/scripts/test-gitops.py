#!/usr/bin/env python3
"""Exercise release publication against a local bare repo; never contact GitHub."""
import os
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
url = 'git@github.com:nghiango/hyper-scale-commerce-gitops.git'


def run(*args, env=None):
    result = subprocess.run(args, cwd=root, env=env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout.strip()


with tempfile.TemporaryDirectory(prefix='hyperscale-gitops-test-') as temp:
    remote = Path(temp) / 'remote.git'
    run('git', 'init', '--bare', str(remote))
    env = os.environ.copy()
    env.update({
        'GITOPS_URL': url,
        'GITOPS_BRANCH': 'main',
        'IMAGE_PREFIX': 'hyperscale-k8s-worker:30500/hyperscale',
        'REVISION': '0123456789012345678901234567890123456789',
        'RELEASE_SCOPE': 'bootstrap',
        'SSH_AUTH_SOCK': str(Path(temp) / 'unused-socket'),
        # Git rewrites the validated SSH URL to this disposable local repository.
        'GIT_CONFIG_COUNT': '1',
        'GIT_CONFIG_KEY_0': f'url.{remote.as_uri()}.insteadOf',
        'GIT_CONFIG_VALUE_0': url,
    })
    run('bash', 'local-ci/scripts/gitops.sh', env=env)
    first = run('git', '--git-dir', str(remote), 'rev-parse', 'refs/heads/main')
    values = run('git', '--git-dir', str(remote), 'show',
                 'main:environments/local/application-values.yaml')
    assert f'tag: "{env["REVISION"]}"' in values
    run('git', '--git-dir', str(remote), 'show', 'main:charts/application/Chart.yaml')
    run('git', '--git-dir', str(remote), 'show', 'main:charts/platform/Chart.yaml')
    run('bash', 'local-ci/scripts/gitops.sh', env=env)
    assert run('git', '--git-dir', str(remote), 'rev-parse', 'main') == first
    infrastructure = run('git', '--git-dir', str(remote), 'rev-parse', 'main:charts/infrastructure')
    platform = run('git', '--git-dir', str(remote), 'rev-parse', 'main:charts/platform')
    infra_values = run('git', '--git-dir', str(remote), 'show', 'main:environments/local/infrastructure-values.yaml')
    env['RELEASE_SCOPE'] = 'application'
    env['REVISION'] = 'abcdef0123456789abcdef0123456789abcdef01'
    run('bash', 'local-ci/scripts/gitops.sh', env=env)
    assert run('git', '--git-dir', str(remote), 'rev-list', '--count', 'main') == '2'
    assert env['REVISION'] in run('git', '--git-dir', str(remote), 'show',
                                 'main:environments/local/application-values.yaml')
    assert infrastructure == run('git', '--git-dir', str(remote), 'rev-parse', 'main:charts/infrastructure')
    assert platform == run('git', '--git-dir', str(remote), 'rev-parse', 'main:charts/platform')
    assert infra_values == run('git', '--git-dir', str(remote), 'show', 'main:environments/local/infrastructure-values.yaml')
print('Local GitOps empty-repo initialization, idempotence and release update passed.')
