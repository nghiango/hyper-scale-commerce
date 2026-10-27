#!/usr/bin/env bash
set -euo pipefail
: "${1:?Usage: bash local-ci/scripts/start-kafka.sh <full-GitOps-commit-SHA>}"
python3 - "$1" <<'PY'
import json,re,subprocess,sys,time,uuid
revision=sys.argv[1]
if not re.fullmatch('[0-9a-f]{40}', revision):
    raise SystemExit('A full GitOps commit SHA is required')
base=['kubectl','--context','kind-hyperscale-k8s','-n','argocd']
app=json.loads(subprocess.check_output(base+['get','application','hyperscale-local','-o','json'],text=True))
if 'automated' in app['spec'].get('syncPolicy',{}):
    raise SystemExit('Refusing selective bootstrap while commerce automatic sync is configured')
if app.get('operation') or app.get('status',{}).get('operationState',{}).get('phase') in ['Running','Terminating']:
    raise SystemExit('An Argo operation is already active; do not overwrite it')
resources=[
 {'group':'','kind':'ServiceAccount','name':'hyperscale-service-account'},
 {'group':'','kind':'Service','name':'kafka'},
 {'group':'','kind':'Service','name':'kafka-headless'},
 {'group':'policy','kind':'PodDisruptionBudget','name':'kafka-pdb'},
 {'group':'apps','kind':'StatefulSet','name':'kafka'},
]
request_id='LOCAL-OPS-01-'+uuid.uuid4().hex[:12]
patch={'operation':{'initiatedBy':{'username':request_id},'sync':{
 'revision':revision,'prune':False,'resources':resources}}}
subprocess.run(base+['patch','application','hyperscale-local','--type=merge','--patch-file=/dev/stdin'],input=json.dumps(patch),text=True,check=True)
print('Requested selective sync of Kafka infrastructure only; application promotion remains held.')
deadline=time.monotonic()+300
while True:
    current=json.loads(subprocess.check_output(base+['get','application','hyperscale-local','-o','json'],text=True))
    state=current.get('status',{}).get('operationState',{})
    target=state.get('operation',{}).get('sync',{}).get('revision')
    if target==revision and state.get('operation',{}).get('initiatedBy',{}).get('username')==request_id:
        if state.get('phase') in ['Failed','Error']:
            raise SystemExit('Selective Kafka sync failed: '+state.get('message',''))
        if state.get('phase')=='Succeeded' and state.get('syncResult',{}).get('revision')==revision:
            break
    if time.monotonic()>deadline:
        raise SystemExit('Timed out waiting for the selected Argo revision to finish')
    time.sleep(1)
print('Argo applied the requested Kafka revision; checking Kubernetes rollout.')
PY
kubectl --context kind-hyperscale-k8s -n hyperscale wait --for=create statefulset/kafka --timeout=180s
kubectl --context kind-hyperscale-k8s -n hyperscale rollout status statefulset/kafka --timeout=300s
