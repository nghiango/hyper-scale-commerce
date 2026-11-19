#!/usr/bin/env python3
"""Validate critical release invariants without third-party Python packages."""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
commerce = (root / 'commerce.yaml').read_text()
platform = (root / 'platform.yaml').read_text()
sha = '0123456789012345678901234567890123456789'
for image in ['app', 'order-query', 'patroni']:
    assert f'hyperscale-{image}:{sha}' in commerce, image
assert commerce.count('name: APP_TRACING_ZIPKIN_ENDPOINT') == 2
assert commerce.count('name: PRIMARY_DB_POOL_SIZE') == 2
assert commerce.count('maxUnavailable: 0') == 2
assert commerce.count('kind: HorizontalPodAutoscaler') == 2
assert 'kind: NetworkPolicy' in platform
assert 'ApplicationTargetsMissing' in platform
assert 'receivers: [zipkin, otlp]' in platform
assert 'exporters: [otlp/tempo]' in platform
assert 'rabbitmq' not in platform.lower()
# Derive the pool budget from rendered HPAs and deployment environment values.
ceilings = [int(n) for n in re.findall(r"maxReplicas: (\d+)", commerce)]
primary = [int(n) for n in re.findall(
    r'name: PRIMARY_DB_POOL_SIZE\s+value: "(\d+)"', commerce)]
replica = [int(n) for n in re.findall(
    r'name: REPLICA_DB_POOL_SIZE\s+value: "(\d+)"', commerce)]
assert len(ceilings) == len(primary) == len(replica) == 2
budget = sum((ceiling + 1) * (p + r)
             for ceiling, p, r in zip(ceilings, primary, replica))
assert budget <= 240, f"Database pool budget too large: {budget}"
assert not re.search(r'IMAGE_PREFIX|COMMIT_SHA', commerce)
print('Release image, rollout, HPA, pool-budget and monitoring invariants passed.')
# Credentials come from Vault via External Secrets; nothing secret is rendered from Git.
for leaked in ('redis_secure_password', 'replicator_pass'):
    assert leaked not in commerce, leaked
app_manifests = (root / 'application.yaml').read_text()
assert len(re.findall(r'name: POSTGRES_PASSWORD\n\s+valueFrom:\n\s+secretKeyRef:\n\s+name: postgres-credentials\n',
                      app_manifests)) == 2
assert 'kind: ClusterSecretStore\nmetadata:\n  name: vault-backend\n' in commerce
for name in ('postgres-credentials', 'redis-secret'):
    assert re.search(rf'kind: ExternalSecret\nmetadata:\n  name: {name}\n', commerce), name
assert re.search(r'kind: ExternalSecret\nmetadata:\n  name: local-platform-credentials\n', platform)
assert 'kind: StatefulSet\nmetadata:\n  name: vault\n' in commerce
assert 'name: postgres-role-labeler' in commerce
assert commerce.count('name: APP_RATE_LIMITING_ENABLED') == 2
print('Vault/External Secrets, role labeler and rate-limit wiring invariants passed.')
# Central logging must persist data without privileged node log access.
assert 'claimName: loki-data' in platform
assert 'claimName: local-grafana-data' in platform
# ADR-0032: only the two read-only topic-observability ClusterRoles are allowed.
cluster_roles = re.findall(
    r'^kind: ClusterRole\nmetadata:\n  name: (\S+)\nrules:\n((?:  .*\n)+)', platform, re.M)
assert sorted(n for n, _ in cluster_roles) == ['local-log-reader-topics', 'local-monitoring-topics'], cluster_roles
assert len(re.findall(r'^kind: ClusterRole$', platform, re.M)) == 2
for _, rules in cluster_roles:
    assert set(re.findall(r'verbs: \[([^\]]*)\]', rules)) <= {'get, list, watch', 'get'}, rules
    assert not re.search(r'secrets|configmaps|\*', rules), rules
assert sorted(re.findall(r'^kind: ClusterRoleBinding\nmetadata:\n  name: (\S+)', platform, re.M)) == [
    'local-log-reader-topics', 'local-monitoring-topics']
assert 'hostPath:' not in platform
assert 'privileged: true' not in platform
assert 'resources: [pods/log]' in platform
assert 'retention_period: 24h' in platform
assert 'uid: loki' in platform
assert 'names = ["hyperscale"]' in platform
assert 'url = "http://loki:3100/loki/api/v1/push"' in platform
print('Scoped logging RBAC, persistent storage and Grafana source invariants passed.')

application = (root / 'application.yaml').read_text()
infrastructure = (root / 'infrastructure.yaml').read_text()
assert 'kind: StatefulSet' not in application
assert 'kind: Namespace' not in application
# The only infrastructure Deployment is the Patroni role labeler (ADR-0033); business workloads stay in application.
assert re.findall(r'^kind: Deployment\nmetadata:\n  name: (\S+)', infrastructure, re.M) == ['postgres-role-labeler']
assert 'kind: HorizontalPodAutoscaler' not in infrastructure
assert 'hyperscale-patroni:' not in application
assert len(re.findall(r'^kind: Deployment$', application, re.M)) == 2
print('Application/infrastructure ownership boundaries passed.')
