# ADR-0028: Local CI/CD and collector-backed observability

Status: Proposed
Date: 2026-10-27
Task: LOCAL-CICD-01

## Context and authorization

The owner explicitly approved a local delivery exception on 2026-10-27. Phase
18 remains in remediation. This ADR records that implementation authorization;
its formal architectural review status remains Proposed.

## Alternatives

Manual Helm releases preserve simplicity but do not exercise the requested
webhook/GitOps flow. Jenkins running kubectl combines build and deployment
privileges. Argo CD pulling a dedicated GitOps repository separates those roles
and provides an auditable desired state, at the cost of another controller.
A tracing SDK migration would disturb existing Brave/Kafka propagation; a
Zipkin receiver in the OpenTelemetry Collector preserves it.

## Decision

Use a loopback-only Jenkins controller and a dedicated ARM64 inbound agent
with JDK 21, Docker Buildx, Git, Helm and Python 3. Jenkins builds/tests first,
then pushes commit-tagged app, order-query and Patroni images. It copies the
existing application chart and local platform chart into a separate GitOps
repository with release values from that same commit. Argo CD alone applies
them to the explicitly named local kind context. HPA owns deployment replicas.
Chart copying avoids chart/values version skew and avoids needing Argo
credentials for two repositories. Do not build untrusted pull requests on this
agent, which has host Docker authority.

Brave asynchronously exports Zipkin spans to the OpenTelemetry Collector,
which batches and exports OTLP to Tempo. Prometheus scrapes each pod through
endpoint discovery, and Grafana provisions Prometheus and Tempo sources.
Telemetry failure must not block business requests; bounded in-memory queues
may drop telemetry. Local monitoring storage is disposable.

The owner subsequently removed RabbitMQ from scope. Kafka remains the exclusive
business event transport under ADR-0006. A local Kubernetes registry replaces
the initially suggested GHCR registry. It uses HTTP only on the isolated Docker
kind network; its host port is bound to loopback. Registry storage uses a PVC.

## Operations, failures and rollback

Git write credentials live in Jenkins; repository read credentials
live in Argo. No secrets go into GitOps. A failed build never publishes a
release commit. Failed registry uploads can leave unused images but no release.
A conflicting GitOps push fails instead of overwriting newer releases.
Jenkins serializes builds and deploys only its configured main branch.

Rolling updates use existing readiness probes and maxUnavailable=0; a broken
image stalls rather than replacing every healthy pod. This does not by itself
prove zero request loss or database migration compatibility. Roll back by
reverting the GitOps release commit, after checking migration compatibility.
Argo pruning is disabled to protect local stateful resources; removal is manual.

HPA ceilings and database pools must be budgeted together, including surge
pods. The local override reduces per-pod pools. metrics-server is required.
All services remain on one physical host and local PVCs; no production SLA,
backup validity, broker HA or performance qualification is claimed here.

## Verification

See docs/operations/local-cicd.md and the LOCAL-CICD-01 evidence report for
commands and actual results. Live delivery requires an owner-supplied GitOps URL and
repository credentials, published source CI files and a passing full build.
The owner-selected polling variant requires no public webhook endpoint.

## Owner-approved health contract clarification (2026-10-27)

Kubernetes readiness remains independent of PostgreSQL health. PostgreSQL outage
tests inspect aggregate `/actuator/health` and its database component, including
HTTP status, and verify readiness remains UP during the database outage. Redis
health is disabled only in these PostgreSQL/Kafka-only test fixtures. Database
failures must still surface as business-operation failures; readiness alone is
not evidence that dependencies are healthy.

## Owner-selected local trigger (2026-10-27)

The owner has no public endpoint and explicitly selected local Git polling
instead of a temporary tunnel. Jenkins polls application Git every two minutes
using `H/2 * * * *`. No public exposure or webhook is required for this variant.
The bootstrap job is held until its CI definition is published to source main.
The full build still gates image and GitOps publication. A future webhook
migration is optional and must preserve validation at the public endpoint.
