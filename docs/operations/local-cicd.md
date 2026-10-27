# Local CI/CD

This owner-approved exception is tracked as LOCAL-CICD-01 / ADR-0028. It does
not complete Phase 18. Kafka remains the business event broker; RabbitMQ is
excluded at the owner's request.

## Topology and prerequisites

GitHub push → local Git polling → Jenkins → Gradle build/unit/integration/architecture tests →
Buildx ARM64 app/query/Patroni images → Kubernetes local registry → GitOps
commit (charts plus image tags) → Argo CD → Kubernetes rolling deployment.

Brave spans → OpenTelemetry Collector (Zipkin receiver) → OTLP → Tempo.
Prometheus discovers individual service endpoints. Grafana provisions both
backends and the HyperScale dashboard. Metrics-server feeds existing HPA v2.

Install Docker, kind, kubectl, Helm 3+, Python 3 and Git on the host. The
containerized Jenkins agent supplies JDK 21, Docker CLI/Buildx, Python and Helm.
Allow substantial memory: the six kind nodes, stateful quorums, monitoring,
Jenkins and test containers share one machine (24 GB Docker RAM is a starting
point, not a qualification result). Ports 5001, 8080, 8081, 8090 and 8404 must
be available. Bootstrap explicitly selects `kind-hyperscale-k8s`; it never
runs against the previously selected k3d context. kind creation itself updates
the current context; all subsequent scripts still specify their target.

## 1. Bootstrap cluster, registry, Argo CD and metrics-server

```bash
bash local-ci/scripts/bootstrap.sh
```

Bootstrap also starts the monitoring workloads before the first application
release. Argo later adopts their identical resources. Bootstrap skips applying
monitoring once its Argo Application exists, preserving GitOps ownership.

The registry runs in namespace `local-registry` with a 10 Gi PVC. On the host,
`http://localhost:5001/v2/` accesses its NodePort through a loopback kind mapping.
BuildKit runs on Docker's `kind` network and publishes to
`hyperscale-k8s-worker:30500/hyperscale`. Cluster nodes use that same address
with a host-specific HTTP containerd registry configuration. This avoids the
common error of treating `localhost` inside a pod/node as the host registry.
The registry is an unauthenticated local lab service. Do not expose it outside
the isolated Docker host. Tag retention/garbage collection is manual; disk
exhaustion blocks publishing. Cluster deletion destroys local PVC storage.

A pre-existing kind cluster without the mapping is rejected rather than
recreated. Review migration/backup requirements before recreating any cluster.
metrics-server's insecure kubelet TLS option is scoped to this local lab.
Monitoring data uses emptyDir and disappears when pods restart; registry and
application state use PVCs. Grafana admin credentials are generated once in
`local-platform-credentials`, outside Git.

## 2. Jenkins and repository credentials

This workspace is provisioned: Jenkins 2.580.1, the `local-arm64` inbound agent
(online, one executor), controller executors set to zero, and Pipeline-from-SCM
job `hyperscale-local-ci`. The GitOps write credential is `gitops-write`
(username `git`). The source repository is public and needs no checkout key.
A separate read-only key is configured in Argo's `hyperscale-gitops-repository`
Secret. GitHub write authorization was verified for Jenkins and rejected for
Argo via dry-run pushes; neither check changed the repository.

Open http://localhost:8090. The generated `admin` account remains enabled with
authentication; bootstrap finished the setup wizard without changing its password.
Retrieve the password locally if needed:

```bash
docker compose -f local-ci/compose.yaml exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Do not paste that password into Git or chat. Keys live under ignored
`local-ci/secrets/` with owner-only private-key permissions. The generated agent
environment lives in ignored `local-ci/.env` with mode 0600. `.dockerignore`
excludes these credentials from Docker build contexts. The temporary controller
key-import file is deleted after encrypted Jenkins credential storage.

For a fresh local controller, start Compose, install the locked plugins and
restart before using the one-time configuration helper. `plugins.txt` lists
required roots; `plugins.lock` records the full installed version set. The
initial controller was updated because its compatible plugin set reported
security advisories. The official archive endpoint avoids a refusing mirror:

```bash
docker compose -f local-ci/compose.yaml up -d jenkins
docker compose -f local-ci/compose.yaml cp local-ci/jenkins/plugins.lock jenkins:/tmp/hyperscale-plugins.lock
docker compose -f local-ci/compose.yaml exec jenkins jenkins-plugin-cli \
  --war /usr/share/jenkins/jenkins.war \
  --plugin-download-directory /var/jenkins_home/plugins \
  --plugin-file /tmp/hyperscale-plugins.lock --latest=false \
  --jenkins-update-center-download-url https://archives.jenkins.io/plugins
docker compose -f local-ci/compose.yaml restart jenkins
python3 local-ci/scripts/configure-jenkins.py
docker compose --env-file local-ci/.env -f local-ci/compose.yaml --profile agent up -d --build agent
python3 local-ci/scripts/verify-jenkins.py
```

Run those commands from the application repository root. The configuration and
verification helpers use the initial generated admin credential and are for
initial local provisioning; after rotating the admin password, manage access
through your authenticated Jenkins session instead. Verification schedules a
temporary read-only GitOps access-check job on the actual agent, captures its
console under ignored build storage, and removes that diagnostic job afterward.
No application build or deployment is started by these helpers.

The pipeline reads `Jenkinsfile` from application `main`, with the local-branch
extension set to `main`. The application-side CI changes are still uncommitted
in this workspace; publish the reviewed source changes before running this job.
The full catalog SLO gate remains failed; no gate is bypassed by credential setup.

The agent has Docker authority and must run only trusted commits. Do not enable
untrusted fork/PR discovery. Jenkins serializes builds and publishes only after
the full Gradle build (including integration tests). JUnit XML is archived even
on failure. GitHub host keys are pinned in `local-ci/jenkins/github_known_hosts`;
the host-local `personal.github.com` alias is replaced with canonical `github.com`.
No registry login is needed for the isolated local registry.

## 3. Initialize the separate GitOps repository

The owner supplied `git@personal.github.com:nghiango/hyper-scale-commerce-gitops.git`.
The repository was initially empty and was seeded on 2026-10-27 with bootstrap
charts, pending-ci values and Argo manifests. Jenkins uses
`git@github.com:nghiango/hyper-scale-commerce-gitops.git` and creates the first
`main` branch with the first successful release. If a nonempty repository lacks
the configured branch, publication fails instead of branching implicitly.
No GitOps releases should be written back to the application repository:
that creates a webhook feedback loop and combines permissions unnecessarily.

The first successful main-branch pipeline copies these paths into GitOps:

- `charts/commerce`: the existing application Helm chart from the tested commit.
- `charts/platform`: local monitoring chart from the same commit.
- `environments/local/values.yaml`: full SHA image tags and local profile.

Both chart sources and values are updated in one GitOps commit. Concurrent
external writes cause a non-fast-forward failure, never a forced overwrite.
Do not rerun historical builds as a deployment mechanism; use a reviewed
GitOps revert instead. Commit tags are traceable but the local registry does
not enforce tag immutability; do not overwrite published commit tags.

Argo read-only repository credentials and the two bootstrap Applications are
now configured. Platform is Synced/Healthy; commerce remains manual-sync with
`pending-ci` tags. For a fresh cluster, configure Argo repository credentials
before applying Applications. The Application manifests already point to the supplied canonical GitOps URL.
After the first release commit is published, apply:

```bash
kubectl --context kind-hyperscale-k8s apply -f local-ci/gitops/applications.yaml
```

Argo uses polling to detect GitOps commits. Auto-sync and self-heal are enabled;
pruning is disabled so deleted chart resources cannot silently erase stateful
infrastructure. Deployment replicas are ignored with RespectIgnoreDifferences
so reconciliation does not fight HPA. Argo runs Helm as a renderer, not as
Helm release management. Do not also run Helm upgrades for these applications.

The baseline chart contains development PostgreSQL and Redis credentials.
They are local-only defaults, not production secrets. Do not reuse them in
shared environments. It also contains pre-existing backup and stateful setup;
this task does not establish its recovery correctness or ARM64 compatibility
of every upstream image. Publish and verify Patroni alongside application
images before the first sync.

## 4. Local Git polling (owner-selected)

The owner selected local Git polling because no public HTTPS endpoint exists.
Both the checked-in Jenkinsfile and the configured job use `H/2 * * * *`
(approximately every two minutes). No tunnel, public endpoint or GitHub webhook
was created. GitHub push triggers were removed from this job.

The source CI definition is not yet published on application main, so the job
is currently disabled to prevent automatic failures from a missing Jenkinsfile.
Publish the reviewed application-side CI files to main, rerun
`python3 local-ci/scripts/configure-jenkins.py` (or enable the job after verifying
source availability), then run Build Now once to establish the checkout baseline.
Subsequent source changes are detected locally by polling. The helper verifies
the public source Jenkinsfile is available before enabling automatic builds.
The catalog SLO remains part of the full-build gate and must pass before release.

A future webhook migration requires a GitHub-reachable HTTPS endpoint, matching
secret validation, the `/github-webhook/` route, and switching the job/Jenkinsfile
trigger back to `githubPush()`. It is not required for the selected polling flow.

## 5. Verify a release

Static checks (no cluster required):

```bash
bash local-ci/scripts/validate.sh
```

Live checks after successful publication and Argo sync:

```bash
curl -f http://localhost:5001/v2/_catalog
kubectl --context kind-hyperscale-k8s -n argocd get applications
kubectl --context kind-hyperscale-k8s -n hyperscale rollout status deploy/app --timeout=300s
kubectl --context kind-hyperscale-k8s -n hyperscale rollout status deploy/order-query --timeout=300s
kubectl --context kind-hyperscale-k8s -n hyperscale get pods,hpa
kubectl --context kind-hyperscale-k8s -n hyperscale top pods
# Run each port-forward in a separate terminal.
kubectl --context kind-hyperscale-k8s -n hyperscale port-forward svc/grafana 3000:3000
kubectl --context kind-hyperscale-k8s -n hyperscale port-forward svc/prometheus 9090:9090
kubectl --context kind-hyperscale-k8s -n argocd port-forward svc/argocd-server 8443:443
```

Grafana username is `admin`; retrieve its password locally from the
`local-platform-credentials` Secret's `grafana-password` key. Do not commit it.
Check Prometheus `/targets`: every ready app/query pod should be discovered,
including newly scaled pods. Check the HyperScale dashboard. Generate normal
HTTP/order traffic and search Tempo in Grafana Explore for the service name
and trace ID from application logs. Histogram panels populate after traffic.
Alert rules are evaluated in Prometheus; external notification routing is not
configured. This starter dashboard covers application/JVM/pool metrics;
cluster CPU/memory comes from `kubectl top`, not a full node-exporter stack.

Under a controlled existing load scenario, observe HPA scale up and back down;
record current/max replicas, CPU/memory, DB pool totals and response failures.
Do not infer autoscaling success merely from a rendered HPA manifest. The local
profile budgets 216 total possible pooled connections across both services at
8 replicas plus one surge each, before operator/migration overhead. Reassess
this budget whenever changing HPA limits or pools.

Verify all three published manifests contain `linux/arm64` with
`docker buildx imagetools inspect <image>:<sha>` from a registry-reachable
network, or inspect pulled images from the kind nodes. Compare running image
IDs with published digests. Push a second harmless commit and record GitHub
delivery → Jenkins build → image upload → GitOps commit → Argo sync → rollout.
Until this is observed, delivery is configured but not end-to-end qualified.

## Rollback and shutdown

Revert the GitOps release commit and let Argo reconcile it. Check database
migration compatibility first; an older binary may not tolerate a newer schema.
Do not use `kubectl rollout undo` as the durable rollback because Argo would
restore the Git desired state. Missing images stall rollouts; check registry
PVC space, image names and containerd host configuration.

`docker compose -f local-ci/compose.yaml --profile agent stop` stops Jenkins
without deleting its volume. Leave kind/PVCs intact to preserve local data.
Deleting kind or Compose volumes is a separate destructive action.

## Implementation notes and references

Clean CI exposed two pre-existing portability problems: jOOQ's DDL scripts
setting must match actual SQL files (the build now uses an absolute SQL glob),
and PostgreSQL outage tests must persist data on Docker-managed named volumes
rather than container-local host bind paths. The outage fixtures remove their
unique volumes after the test, and query-side Kafka producers use the container's
advertised bootstrap address. Business outage assertions remain in place.

- [Jenkins Declarative Pipeline syntax](https://www.jenkins.io/doc/book/pipeline/syntax/)
- [Argo CD Helm rendering](https://argo-cd.readthedocs.io/en/latest/user-guide/helm/)
- [OpenTelemetry Collector configuration](https://opentelemetry.io/docs/collector/configuration/)
- [Zipkin asynchronous Brave reporter](https://github.com/openzipkin/zipkin-reporter-java)
- [jOOQ DDL scripts configuration](https://www.jooq.org/doc/latest/manual/code-generation/codegen-meta-sources/codegen-ddl/)

## Health contract

The owner approved dependency-independent Kubernetes readiness on 2026-10-27.
PostgreSQL outage tests inspect `/actuator/health`, assert its top-level and
database component status plus HTTP 200/503, and verify readiness stays UP
during the outage. Redis health is disabled only in these PostgreSQL/Kafka-only
fixtures, which do not start Redis. Both outage/recovery regressions pass.
Production readiness configuration is unchanged. See the verification report
for the full CI build result and remaining delivery setup.

The readiness mismatch is resolved. The latest full CI run is still blocked by
the existing catalog SLO test (163.508334 ms p95 against a 100 ms limit). The
threshold and publication gate remain unchanged; see the evidence report.

## GitOps repository seeded (2026-10-27)

The owner explicitly requested cloning and adding the prepared files. The
checkout is `/Users/ngng/working/projects/personal/hyper-scale-commerce-gitops`.
Its initial main commit includes `charts/commerce`, `charts/platform`,
`environments/local/values.yaml`, `argocd/applications.yaml` and a README.
Values use `pending-ci` tags and the remote bootstrap commerce Application
has auto-sync disabled; this is configuration seeding, not a verified release.
Both Helm charts linted and rendered successfully. After the first green Jenkins
release replaces the placeholder tags, follow the GitOps README to enable
commerce auto-sync. No Argo Application was applied during this seeding step.

## Grafana login troubleshooting

Use username `admin` and the current `grafana-password` field from the
`local-platform-credentials` Secret. On macOS, copy it directly without exposing
it in terminal output:

```bash
kubectl --context kind-hyperscale-k8s -n hyperscale get secret local-platform-credentials -o jsonpath='{.data.grafana-password}' | base64 -d | pbcopy
```

The initial trailing newline in the generated Secret was removed and the admin
password synchronized on 2026-10-27. Browser login and its authenticated session
were verified. Repeated failed attempts can temporarily lock the account; let
that lockout expire rather than disabling login protection. Future bootstrap
passwords are generated without a trailing newline. Any CLI reset must use the
same data path as the server (`/var/lib/grafana`) and password-from-stdin, so the
credential is not placed in process arguments. Resetting does not require
recreating the pod or deleting dashboards.

## Kafka UI and k9s (LOCAL-OPS-01)

The operator dashboard uses Kafbat Kafka UI v1.5.0, pinned by image digest in
`local-ci/platform/values.yaml`. Its ClusterIP Service has no public ingress.
Run this foreground command to expose it only on local IPv4 loopback:

```bash
bash local-ci/scripts/kafka-ui.sh
# Open http://localhost:8082
```

The UI is configured read-only against `kafka:9092` in namespace hyperscale;
dynamic configuration is disabled. There is no dashboard login in this local
loopback setup. Read-only UI configuration is not a Kafka ACL: trusted local
cluster operators still control the plaintext internal brokers.

Kafka uses the existing three-broker KRaft StatefulSet and three 5 Gi PVCs.
Only Kafka resources were selectively synchronized through the held commerce
Argo Application. This avoids a second owner for the same broker resources.
A fresh setup can repeat that infrastructure-only operation with the exact
GitOps commit that contains the chart:

```bash
bash local-ci/scripts/start-kafka.sh <full-GitOps-commit-SHA>
```

The helper refuses commerce auto-sync and an already active operation, selects
only the Kafka StatefulSet, its two Services, its PDB and its service account,
and waits for broker readiness. It does not start app/query/PostgreSQL/Redis.
The failed application CI gate remains in effect. This broker startup is not
high-availability, recovery or performance qualification.

k9s 0.51.0 was already installed on this Mac. Launch a project-scoped read-only
terminal session with either command:

```bash
bash local-ci/scripts/k9s.sh
# Or from any directory:
k9s --context kind-hyperscale-k8s --namespace hyperscale --readonly
```

Press `?` for help, `:pods` for pods, `:svc` for Services, `:sts` for StatefulSets,
and Ctrl+C to quit. k9s is a terminal UI, not a browser service. The launcher
explicitly selects this local cluster and namespace; no kubeconfig replacement
or package upgrade was needed. Its first launch initializes its normal macOS
configuration under `~/Library/Application Support/k9s`.

Stop the Kafka UI forward to close browser access. Preserve Kafka PVCs if
stopping brokers; do not delete the cluster/volumes as a dashboard shutdown step.

During setup, the inherited cp-kafka:7.6.0 image was found to provide Kafka 3.6.
It was corrected to cp-kafka:7.7.0 to match the phase-approved Kafka 3.7 line.
The same three PVCs were retained. The UI can still display `3.6-IV2`: the
retained metadata feature level was verified with `kafka-features describe`,
while the running broker CLI reports `7.7.0-ccs` and all pod images are 7.7.0.
No metadata feature upgrade was forced solely to change the UI display.

### Lightweight local replica profile

Local delivery retains three Kafka brokers, three etcd members and three Patroni
instances. Existing quorum membership and Kafka RF=3/min ISR=2 require these
members; retain their PVCs when recovering from accidental scale-down. PostgreSQL
read pools also require available standby instances.

App/query start at one and scale from one to three on CPU. The local profile
omits memory-based HPA because idle JVM resident memory kept triggering expansion;
resource requests and limits remain unchanged. Baseline performance HPAs retain
both CPU and memory metrics. Ingress, Redis and monitoring stay singletons.
Local app/query/ingress/Redis PDBs permit zero available pods for voluntary
maintenance, so drains can interrupt service. Quorum PDBs retain minimum two.
This development profile does not demonstrate production availability.
