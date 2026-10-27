# LOCAL-OPS-01 — Kafka UI and k9s

Date: 2026-10-27
Status: **PASS — requested local operator tools operational**.
Scope: owner-requested Kafka UI and k9s; application promotion and Phase 18
qualification remain held. Procedures: implement-bootcamp-task, create-adr,
record-task-issue and verify-task. See ADR-0029 and the append-only issue history.

## Implemented result

- Kafbat Kafka UI v1.5.0 is managed by the platform Helm chart and Argo CD.
- ClusterIP Service, loopback-only localhost:8082 forward, read-only cluster,
  dynamic configuration disabled, resource limits and restricted non-root pod.
- Existing three-node Kafka chart selectively synchronized through the same
  held commerce Application, avoiding competing infrastructure ownership.
- Existing native ARM64 k9s 0.51.0 initialized and tested; explicit project
  context/namespace read-only launcher added. No package upgrade required.

## Reproducible commands

```bash
bash local-ci/scripts/validate.sh
bash local-ci/scripts/start-kafka.sh 3ca2f39d26966454706e37388ccc8b64e51edd96
bash local-ci/scripts/kafka-ui.sh
bash local-ci/scripts/k9s.sh --command pods --splashless
```

The UI forward and k9s are foreground interactive commands. Ctrl+C closes
those clients without deleting Kubernetes resources or PVCs. The broker helper
uses only context kind-hyperscale-k8s and namespace hyperscale, requires a full
GitOps SHA, refuses automatic commerce sync and queued/running operations,
selects only the five infrastructure resources, waits for its unique Argo
operation/revision and then waits for actual StatefulSet rollout.

## Image and architecture verification

Kafbat image index digest:
`sha256:7cda86a33344160309fdb65146332e4da65db81a945614f2fe32e210803f6fd1`.
ARM64 manifest:
`sha256:3928503e8b05193c9bfed691e08adc0ef5ad0063ea5fb2ca70941e1e2832d936`.
Both amd64/arm64 manifests were inspected; the index is pinned in values.

The inherited chart pinned cp-kafka:7.6.0, which supplies Kafka 3.6. It was
corrected to cp-kafka:7.7.0 because the current phase approves Kafka 3.7.
[Confluent's release notes map 7.7 to Kafka 3.7](https://docs.confluent.io/platform/7.7/release-notes/index.html).
ARM64 image manifest:
`sha256:e773a52e3cf3f3c8ca76362740481140e4b9d402cfd03e79611646b267e47b98`.
All three final pod specs report cp-kafka:7.7.0 and Ready=true; broker CLI reports
7.7.0-ccs and uname reports aarch64. No storage formatting or deletion occurred.

## Actual live checks

Helm lint/render and the existing release/GitOps invariant checks passed.
Existing Grafana, Prometheus, Tempo and collector Deployment renderings were
byte-identical before/after the generic component-env extension; their running
pod names and restart counts remained unchanged.

Argo selective operation: Succeeded at revision
`3ca2f39d26966454706e37388ccc8b64e51edd96`, with exactly:

- ServiceAccount hyperscale-service-account
- Service kafka
- Service kafka-headless
- PodDisruptionBudget kafka-pdb
- StatefulSet kafka

The dashboard commit is `cfbe3cc`; the broker-version correction is `3ca2f39`.
The GitOps checkout is clean and tracks origin/main.

Final broker quorum snapshot from `kafka-metadata-quorum describe --status`:
LeaderId=2, LeaderEpoch=3, HighWatermark=1467, MaxFollowerLag=0,
MaxFollowerLagTimeMs=146, CurrentVoters=[1,2,3]. This is a point-in-time health
check, not resilience or throughput qualification.

Three 5 Gi PVCs are Bound. Original volume identities were retained:

- kafka-0: pvc-542d7693-9eee-4386-85e1-e5242480f6fd
- kafka-1: pvc-9f0f9166-fc66-492e-b302-54e93144bae7
- kafka-2: pvc-fd003e52-6c35-4617-bdd0-78530b90bafd

`kafka-features describe` reports supported maximum 3.7-IV4 and retained
finalized metadata level 3.6-IV2. The UI version field still shows that metadata
level. No feature finalization was forced merely to change a display label.

UI verification:

- GET / → HTTP 200; browser title Kafbat UI, version v1.5.0.
- GET /api/clusters → HTTP 200, hyperscale-local ONLINE, brokerCount=3,
  readOnly=true, lastError=null.
- GET /api/clusters/hyperscale-local/brokers → HTTP 200, IDs 1, 2, 3 with
  internal headless-service addresses.
- Browser dashboard visibly showed one online cluster, three brokers and the
  readonly marker. No topics/messages were created for verification.
- Service type is ClusterIP; forward binds 127.0.0.1 only.

k9s verification: native TUI opened with kind-hyperscale-k8s [R], Kubernetes
v1.32.2, hyperscale pod view. It displayed the existing monitoring pods and
newly created Kafka/UI pods. Ctrl+C exited with code 0. The verification TUI
was closed; the Kafka dashboard forward remains running.

## Preserved constraints and limits

Platform Application is Synced/Healthy. Commerce remains OutOfSync/Missing
because unrelated resources remain deliberately undeployed. Its auto-sync is
absent. No app/query Deployment or PostgreSQL/Redis StatefulSet exists. The
source CI job/catalog SLO gate is unchanged; this is not an application release.
No user messages, credentials or private keys were published in GitOps.

The existing plaintext broker configuration remains local. UI read-only mode
is not a replacement for broker ACLs. There is no public ingress, local brokers
use one Docker host and local storage, and no production availability, recovery,
performance or data-durability claim is made.

Prior application/CI worktree changes were preserved. Task-specific source and
GitOps diffs were reviewed and git diff --check passed. Generated metadata/API
snapshots remain under ignored build storage. No unrelated Java application
code changed for this task, so the application test suite was not rerun; its
previously documented catalog SLO failure remains unresolved.
