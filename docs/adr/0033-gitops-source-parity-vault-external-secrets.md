# ADR-0033: Bring GitOps-only Vault/External Secrets changes back into source

Status: Proposed
Date: 2026-11-19
Task: LOCAL-GITOPS-PARITY-01
Relates to: ADR-0031 (independent local release ownership)

## Context and authorization

On 2026-11-11 fifteen commits were made directly in `hyper-scale-commerce-gitops`:
Vault (persistent, auto-unseal), External Secrets (`vault-backend`,
`postgres-credentials`, `redis-secret`, `local-platform-credentials`), removal
of plaintext Postgres/Redis passwords, the Patroni `postgres-role-labeler`,
replica-count ingress peers and rate-limit env wiring. `release.py` regenerates
`charts/` and `environments/` from this repository, so each release scope
silently reverts them. The 2026-11-19 platform release removed the Grafana
ExternalSecret from Git (the live object survived only because pruning is
off). Owner approved porting the GitOps state into source on 2026-11-19.

## Alternatives

Exclude these files from regeneration: two sources of truth for one chart.
Stop regenerating charts: abandons ADR-0031 release ownership. Port the GitOps
state into `performance/helm/hyperscale-commerce`, `local-ci/platform` and
`local-ci/gitops/environments`: chosen.

## Decision

Source templates and values now match the deployed GitOps state. Rendered
output of a simulated release equals current GitOps exactly for application,
equals infrastructure except Helm `# Source:` comments (chart name), and restores
the platform ExternalSecret. Rate limiting stays disabled by default and is
enabled in the local application environment, as deployed.

`verify-release.py` now requires: no plaintext `redis_secure_password` /
`replicator_pass`; app and order-query read `POSTGRES_PASSWORD` from
`postgres-credentials`; the `vault-backend` ClusterSecretStore, Postgres/Redis
and Grafana ExternalSecrets, the Vault StatefulSet, the role labeler and the
rate-limit env vars. The ownership rule "no Deployment in infrastructure" becomes
"only `postgres-role-labeler`". `verify-k8s-redis.sh` checks that the Redis
password comes from the `redis-secret` ExternalSecret and is not rendered from Git.

## Consequences and failures

The performance chart now requires External Secrets CRDs and Vault, matching the
cluster. Future infrastructure changes must be made here, not in GitOps; GitOps
edits outside `topics/`, `argocd/`, docs and scripts will be overwritten.

## Verification and rollback

`local-ci/scripts/validate.sh` and `performance/kubernetes/scripts/*` pass.
Rollback is reverting this commit, which re-introduces plaintext credentials
and is not recommended.
