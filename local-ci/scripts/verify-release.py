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
# Central logging must persist data without privileged node log access.
assert 'claimName: loki-data' in platform
assert 'claimName: local-grafana-data' in platform
assert 'kind: ClusterRole' not in platform
assert 'hostPath:' not in platform
assert 'privileged: true' not in platform
assert 'resources: [pods/log]' in platform
assert 'retention_period: 24h' in platform
assert 'uid: loki' in platform
assert 'names = ["hyperscale"]' in platform
assert 'url = "http://loki:3100/loki/api/v1/push"' in platform
print('Namespace-scoped logging, persistent storage and Grafana source invariants passed.')

application = (root / 'application.yaml').read_text()
infrastructure = (root / 'infrastructure.yaml').read_text()
assert 'kind: StatefulSet' not in application
assert 'kind: Namespace' not in application
assert 'kind: Deployment' not in infrastructure
assert 'kind: HorizontalPodAutoscaler' not in infrastructure
assert 'hyperscale-patroni:' not in application
assert len(re.findall(r'^kind: Deployment$', application, re.M)) == 2
print('Application/infrastructure ownership boundaries passed.')
