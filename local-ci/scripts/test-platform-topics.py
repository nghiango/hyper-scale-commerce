#!/usr/bin/env python3
"""Platform must also observe system-design topic namespaces (topic-*), without changing the hyperscale pipelines."""
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = subprocess.run(['helm', 'template', 'platform', str(root / 'local-ci/platform')],
                     check=True, text=True, stdout=subprocess.PIPE).stdout


def require(fragment, what):
    if fragment not in out:
        raise SystemExit(f'missing {what}: {fragment!r}')


# Existing hyperscale scraping and log discovery stay as they are.
require('names: [hyperscale]', 'existing hyperscale scrape restriction')
require('discovery.kubernetes "pods"', 'existing hyperscale log discovery')
# Prometheus job for topic namespaces.
require('job_name: topics', 'Prometheus topics job')
require('regex: topic-.+', 'topic namespace filter')
require('__meta_kubernetes_pod_label_system_design_topic', 'topic label relabelling')
# Alloy pipeline for topic namespaces.
require('discovery.kubernetes "topic_pods"', 'Alloy topic pod discovery')
require('loki.source.kubernetes "topic_pod_logs"', 'Alloy topic log source')
# Cluster-wide read-only RBAC bound to the existing service accounts.
for name, sa in (('local-monitoring-topics', 'local-monitoring'), ('local-log-reader-topics', 'local-log-reader')):
    if not re.search(rf'kind: ClusterRole\nmetadata:\n  name: {name}\n', out):
        raise SystemExit(f'missing ClusterRole {name}')
    if not re.search(rf'kind: ClusterRoleBinding\nmetadata:\n  name: {name}\n(?:.*\n)*?.*name: {sa}', out):
        raise SystemExit(f'missing ClusterRoleBinding {name} -> {sa}')
for verb in ('create', 'update', 'patch', 'delete'):
    block = re.findall(r'kind: ClusterRole\nmetadata:\n  name: local-[a-z-]+-topics\n(?:  .*\n|rules:\n|- .*\n)*', out)
    if any(verb in b for b in block):
        raise SystemExit(f'topic ClusterRoles must be read-only, found {verb}')
# Grafana dashboard with a topic variable, mounted next to the existing one.
require('/dashboards/topics-dashboard.json', 'topics dashboard mount')
require('  topics-dashboard.json: |', 'topics dashboard ConfigMap key')
dashboard = json.loads((root / 'local-ci/platform/files/topics-dashboard.json').read_text())
assert dashboard['uid'] == 'system-design-topics', dashboard['uid']
assert any(v['name'] == 'topic' for v in dashboard['templating']['list']), 'topic variable'
print('platform topic observability OK')
