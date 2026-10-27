#!/usr/bin/env python3
"""Exercise lifecycle commands with fake Docker/kubectl; never stop real services."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='hyperscale-lifecycle-test-') as temp:
    folder = Path(temp)
    fake = '''#!/usr/bin/env python3
import json,os,sys
name=os.path.basename(sys.argv[0]); args=sys.argv[1:]
with open(os.environ['STACK_TEST_LOG'],'a') as f:f.write(json.dumps([name]+args)+'\\n')
if name=='docker' and args[:1]==['ps']:
    rule=args[args.index('--filter')+1]
    if os.environ.get('STACK_EMPTY')!='1':
        if rule=='label=io.x-k8s.kind.cluster=hyperscale-k8s':print('hyperscale-k8s-control-plane\\nhyperscale-k8s-worker')
        elif rule=='label=com.docker.compose.project=hyperscale-local-ci':print('hyperscale-local-ci-jenkins-1\\nhyperscale-local-ci-agent-1')
        elif rule=='name=^/buildx_buildkit_hyperscale-local0$':print('buildx_buildkit_hyperscale-local0')
        else:sys.exit(9)
if name=='kubectl' and 'wait' in args and os.environ.get('STACK_FAIL_WAIT')=='1':sys.exit(1)
'''
    for name in ['docker', 'kubectl']:
        path = folder / name
        path.write_text(fake)
        path.chmod(0o755)

    def run(action=None, *extra, input_text=None, **flags):
        log = folder / 'commands.jsonl'
        log.write_text('')
        env = os.environ.copy()
        env.update(PATH=f'{folder}:{env["PATH"]}', STACK_TEST_LOG=str(log), **flags)
        args = [action, *extra] if action is not None else []
        result = subprocess.run(['/bin/bash', str(root / 'local-ci/scripts/local-stack.sh'), *args],
                                cwd=folder, env=env, text=True, capture_output=True, input=input_text)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        return result, calls

    result, calls = run('stop')
    assert result.returncode == 0, result.stderr
    stops = [c for c in calls if c[:2] == ['docker', 'stop']]
    assert len(stops) == 3
    assert 'hyperscale-local-ci-jenkins-1' in stops[0]
    assert 'buildx_buildkit_hyperscale-local0' in stops[1]
    assert 'hyperscale-k8s-control-plane' in stops[2]
    assert all(c[:2] not in [['docker', 'rm'], ['docker', 'volume']] for c in calls)

    for action in ['start', 'stop']:
        result, calls = run(action, '--dry-run')
        assert result.returncode == 0, result.stderr
        assert not any(c[:2] in [['docker', 'start'], ['docker', 'stop']] or c[0] == 'kubectl' for c in calls)

    result, calls = run('start')
    assert result.returncode == 0, result.stderr
    starts = [c for c in calls if c[:2] == ['docker', 'start']]
    assert 'hyperscale-k8s-control-plane' in starts[0]
    assert 'hyperscale-local-ci-jenkins-1' in starts[-1]
    registry = next(i for i,c in enumerate(calls) if 'deployment/registry' in c)
    assert registry < calls.index(starts[-1])

    result, calls = run('start', STACK_FAIL_WAIT='1')
    assert result.returncode != 0
    assert not any('hyperscale-local-ci-jenkins-1' in c for c in calls if c[:2] == ['docker', 'start'])

    for action in ['status', 'stop']:
        result, calls = run(action, STACK_EMPTY='1')
        assert result.returncode == 0, result.stderr
    result, calls = run('start', STACK_EMPTY='1')
    assert result.returncode != 0
    assert not any(c[:2] == ['docker', 'start'] for c in calls)
    result, calls = run(input_text='4\n5\n3\n0\n')
    assert result.returncode == 0, result.stderr
    assert 'Would run: docker start' in result.stdout
    assert 'Would run: docker stop' in result.stdout
    assert any(c[:2] == ['docker', 'inspect'] for c in calls)
    assert not any(c[:2] in [['docker', 'start'], ['docker', 'stop']] for c in calls)
    result, calls = run(input_text='invalid\n0\n')
    assert result.returncode == 0 and not calls
    assert 'Invalid option' in result.stdout
    result, calls = run(input_text='')
    assert result.returncode == 0 and not calls
print('Local stack lifecycle, interactive menu, dry-run and readiness-failure checks passed.')
