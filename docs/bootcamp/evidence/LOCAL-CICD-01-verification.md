# LOCAL-CICD-01 verification — 2026-10-27

Status: **NOT COMPLETE — full build blocked by legacy catalog SLO; end-to-end delivery not qualified**.
Procedure: implement-bootcamp-task, create-adr, record-task-issue, verify-task.
Owner approved an isolated local infrastructure exception; Phase 18 remains in
remediation. RabbitMQ was explicitly removed from scope by the owner.

## Environment

- macOS ARM64; Docker engine aarch64, 10 CPUs, 25,159,561,216 bytes RAM;
  engine version 29.8.1.
- Host JDK 26; build verification executed inside eclipse-temurin:21-jdk-jammy.
- New `kind-hyperscale-k8s`: kind 0.27.0, Kubernetes 1.32.2, three control-plane
  nodes and three workers. Existing k3d context was not targeted. kind creation
  changed the selected context; all operational commands explicitly name kind.
- Argo CD chart 7.8.26 and metrics-server chart 3.12.2 installed successfully.
- Jenkins 2.541.3 controller running on loopback port 8090, setup wizard pending.
- Dedicated agent image built successfully: Java 21.0.10, Helm 3.17.2,
  Python 3.12.15, Docker CLI 27.5.1. Agent tools can access Docker and attach to
  the BuildKit daemon on the kind network. Permanent agent is not enrolled yet.

## Passing checks

`bash local-ci/scripts/validate.sh`:

- Both Helm charts lint and render successfully.
- Generated release uses full, quoted commit tags for app/query/Patroni.
- Two rolling deployment policies, two HPAs, opt-in exporter environment,
  monitoring pipeline and missing-target alert are present.
- Rendered HPA ceilings plus surge and rendered pools total 216 possible
  connections (validation rejects totals above 240).
- RabbitMQ is absent. Numeric-only SHA regression passes.
- A disposable local bare-Git test exercises the actual GitOps shell script:
  initial main branch creation, chart/value publication, no-op idempotence and
  a second release update pass. Git URL rewriting keeps this test entirely local;
  it does not publish to the owner's repository.

Unit/architecture and formatting verification:

```bash
docker run --rm \
  -v "$PWD:/workspace" -w /workspace \
  -v hyperscale-ci-gradle:/root/.gradle \
  eclipse-temurin:21-jdk-jammy \
  ./gradlew :app:test :order-query:test spotlessCheck --no-daemon
```

Result: **BUILD SUCCESSFUL in 24s**. App 83 tests, zero failures; order-query
29 tests, zero failures. App task was up-to-date from the preceding execution;
its XML reports were independently counted. Both services' three new trace
export tests pass: exporter absent by default, credential-bearing endpoint
rejected, completed span received over HTTP with correct service name. All
architecture rules and Spotless checks pass.

Live infrastructure:

- Local registry Deployment ready with a 10 Gi PVC.
- BuildKit pushed `hyperscale-k8s-worker:30500/hyperscale/registry-smoke:local`
  with `--platform linux/arm64`. Published manifest digest:
  `sha256:b9190fc7b9fdc72deeaf8906f126cbb78759f113bc5165619ca7369e5adac6d8`.
- `docker exec hyperscale-k8s-worker crictl pull` retrieved that image, config
  digest `sha256:71ca506e40958daf783dbe2e5d2d3809a8b5b082473fdfc0f25ce34d8a9e279f`.
- Host registry `/v2/_catalog` lists `hyperscale/registry-smoke`.
- Grafana, Prometheus, OpenTelemetry Collector and Tempo are each 1/1 ready.
  Grafana `/api/health` returns database ok/version 11.6.0;
  Prometheus `/-/ready` returns ready and its self-scrape reports `up=1`.
- A synthetic Zipkin span named `local-ci-collector-smoke`, trace ID
  `0123456789abcdef0123456789abcdef`, was posted to the collector. Tempo's
  `/api/traces/<id>` returned that span via JSON. This proves the configured
  collector-to-Tempo transport, not deployed application trace continuity.
- `kubectl --context kind-hyperscale-k8s top nodes` returns CPU/memory for all
  six nodes. HPA under actual application load is not yet verified.
- `git ls-remote git@personal.github.com:nghiango/hyper-scale-commerce-gitops.git`
  succeeded with no refs. Pipeline/Argo defaults use canonical `github.com` SSH;
  independent deploy keys must be configured in those services.

## Failed full gate and remediation boundaries

The complete build was executed with a Docker socket for Testcontainers:

```bash
docker run --rm \
  -v "$PWD:/workspace" -w /workspace \
  -v hyperscale-ci-gradle:/root/.gradle \
  -v /Users/ngng/.docker/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  eclipse-temurin:21-jdk-jammy ./gradlew spotlessApply build --no-daemon
```

The clean compilation problem was resolved by changing jOOQ's existing directory
setting to an absolute SQL glob. Application compile and packaging then passed.
The app integration run executed 62 tests: 61 passed, one fixture initialization
failed due to PostgreSQL bind-mount ownership. Its Docker-managed volume fix
allows startup, and a subsequent run reached the existing readiness assertion:
`PostgresOutageIntegrationTest.java:105` expects DOWN when PostgreSQL stops,
but the readiness group contains only readinessState. The failed follow-up
runner was stopped. No assertion was removed and no test is skipped in Jenkins.

The owner subsequently approved keeping readiness independent and using aggregate
health for outage assertions. Both service outage fixtures
were made container-compatible; query-side producer bootstrap was corrected to
use the advertised container address. Both corrected outage fixtures passed in a focused run (see the follow-up
section below). The full rerun reached a separate legacy catalog SLO failure. No application image release or GitOps
promotion was performed from this failing candidate.

See the append-only error history at
`docs/bootcamp/issues/LOCAL-CICD-01-errors.md` for all failures and diagnoses.

## Remaining acceptance gaps

- Resolve the legacy catalog SLO failure (163.508334 ms p95 versus <100 ms)
  and obtain a passing full build; retain the existing publication gate.
- Publish the reviewed application-side CI changes so the SCM job can load
  Jenkinsfile from main; the local files remain uncommitted.
- Publish the first successful application release and enable commerce sync.
  Argo credentials/Applications are configured; bootstrap platform is healthy.
  The GitOps repo contains configuration, but a verified release is still pending.
- Initialize the selected local Git polling flow with a first source checkout;
  no public endpoint or webhook is required for the owner-selected variant.
- Observe one real commit through Git polling, tests, ARM64 image publication,
  GitOps commit, Argo sync and rolling app/query deployment.
- Verify real application metrics/traces and HPA scale-up/down under load.

No production availability, zero request loss, full infrastructure ARM64 support,
backup recovery, or performance target is established by these smoke checks.

## Diff and artifacts

Initial worktree was clean. Full tracked diff and new delivery files were
reviewed; `git diff --check` passed. Generated earlier-phase evidence overwritten
by integration tests was preserved under ignored `build/local-ci-generated-evidence/`
and restored to HEAD, avoiding unrelated report changes. Reports/recordings stay
under ignored build storage. No credentials/private keys are checked in;
GitHub host keys are public metadata from its official HTTPS API.

The temporary collector/Tempo test forwards were closed. Jenkins, registry,
kind, Argo, monitoring and the Grafana/Prometheus loopback UI forwards remain
running. No application-repository commit or push was made.

## Follow-up: approved aggregate-health assertions

The owner chose dependency-independent readiness on 2026-10-27. No production
readiness group was changed. Both service fixtures assert aggregate health
status, the db component status and matching HTTP 200/503, and explicitly
verify readiness remains UP during the database outage. The fixtures exclude
Redis health only locally because they provision PostgreSQL and Kafka, not Redis.

Focused command (same Docker environment as above):

```bash
./gradlew spotlessApply \
  :app:integrationTest --tests '*PostgresOutageIntegrationTest' \
  :order-query:integrationTest --tests '*PostgresOutageIntegrationTest' \
  --no-daemon
```

Result: **BUILD SUCCESSFUL in 1m 56s**; both tests passed. Existing committed-order
persistence and post-recovery publication/projection checks remain intact.
The complete `./gradlew build --no-daemon` finished **FAILED in 6m 33s**.
App integration: 62 tests, 61 passing, one failure in the pre-existing
`CatalogSloVerificationTest`. Its by-ID p95 was **163.508334 ms**, above the
unchanged **100 ms** limit. The corrected PostgreSQL outage test passed again
in that full run. No latency threshold or workload was changed, and no tests
were excluded. Raw log: ignored `build/local-ci-full-build.log`.

The order-query suite was verified separately because Gradle stopped after the
app task failed. Command (same Docker environment):

```bash
./gradlew :order-query:integrationTest spotlessCheck --no-daemon
```

Result: **BUILD SUCCESSFUL in 2m 44s**. All **17 order-query integration tests**
passed, including both broker outage/recovery and PostgreSQL outage/recovery.
Spotless passed for all modules. Raw log: `build/local-ci-query-build.log`.
The related query Kafka fixture also uses the advertised Docker host address
for offline startup and the container bootstrap address after restart.

Final XML counts across these commands: app unit/architecture 83/83; query
unit/architecture 29/29; app integration 61/62 (catalog SLO failure only); query
integration 17/17. No skipped tests or test errors. The approved readiness
correction is verified; full CI promotion remains blocked by the catalog SLO.
The implementation diff was reviewed, `git diff --check` passed, and generated
older-phase reports were preserved under ignored build storage and restored.

## GitOps bootstrap publication follow-up (2026-10-27)

At the owner's explicit request, cloned the GitOps repository into the sibling
checkout `/Users/ngng/working/projects/personal/hyper-scale-commerce-gitops`,
added 28 bootstrap files and pushed initial main commit `c0563ec`. Includes
both charts, local values, Argo Applications and setup documentation. Both
charts passed Helm lint/render; staged diff checks and a key/token pattern
scan passed. The commerce Application's automatic sync is held, and image
tags are explicitly `pending-ci`, preserving the failed CI promotion gate.
No Argo sync or application release was performed.

## Deploy-key and local Jenkins provisioning follow-up (2026-10-27)

The owner registered both dedicated public keys in GitHub. Both keys read main
commit c0563ec successfully. Dry-run receive-pack authorization succeeds for
Jenkins and is rejected for Argo, confirming intended write/read-only roles.
No repository content was changed by those checks. GitHub's unauthenticated
repository metadata confirms the application source is public.

Argo's private key was supplied directly to a server-side-applied Kubernetes
repository Secret through stdin; no plaintext manifest or last-applied annotation
was saved. Both Applications were registered. `hyperscale-local-platform` is
Synced and Healthy. `hyperscale-local` is OutOfSync/Missing as expected: auto-sync
is absent and pending-ci workload images have not been promoted.

Jenkins was updated from its initial 2.541.3 image to official stable 2.580.1
because the former's compatible plugin set reported published security advisories.
The newer required plugins installed successfully from the official archive
endpoint, with no installer security warnings; 73 installed plugin versions are
recorded in `local-ci/jenkins/plugins.lock`. The failed earlier mirror attempt
is retained in ignored build logs and the append-only issue history.

Using the existing generated admin credential without displaying it, initial
onboarding was completed, encrypted SSH credential `gitops-write` imported,
controller executors set to zero, the exclusive one-executor `local-arm64` node
created, and Pipeline-from-SCM job `hyperscale-local-ci` configured. Node secret
was written only to ignored mode-0600 local-ci/.env. A temporary key import
file was removed from the controller after credential storage. Private key
source files remain ignored and mode 0600; their directory is mode 0700.
Docker build contexts explicitly exclude credentials.

The agent is online. A temporary Jenkins pipeline used `sshagent` with the
stored credential on the actual agent to execute a pinned-host-key GitOps
ls-remote check. Result **SUCCESS**; no application build or deployment ran.
The diagnostic job was deleted, and its console is stored in ignored
`build/local-ci-jenkins-key-check.log`. Controller reports the credential and
source job present with zero failed plugins. This verifies real encrypted-key
retrieval and SSH use, beyond a host-only credential check.

The owner selected polling instead of a public webhook endpoint. The
application-side CI source files are still uncommitted, and the catalog SLO
gate still fails.
No application promotion was made during this provisioning follow-up.

### Owner-selected polling trigger and final configuration checks

The owner reported no public URL and selected local Git polling, explicitly
replacing the webhook requirement for this local variant. Jenkinsfile and the
configured source job use `H/2 * * * *`. No tunnel or public endpoint was created.
The job is disabled until source main contains the CI definition; the bounded
configuration helper checks source availability before enabling it. This does
not bypass the failing catalog SLO gate. A first manual build establishes the
checkout baseline after publishing the source CI files.

The running controller's Declarative Pipeline validator accepted Jenkinsfile.
Static release checks, Helm lint/render, local bare-Git publication tests and
Python syntax checks passed. The protected local environment and secret directory
permissions were checked. The Argo repository Secret has no client-side
last-applied annotation containing a duplicate private key. Commerce is held;
platform remains Synced/Healthy.

Controller verification after applying the polling selection: job disabled=true,
agent online=true, trigger list contains exactly SCMTrigger with `H/2 * * * *`;
no GitHubPushTrigger remains. The updated polling Jenkinsfile passed the running
controller's Declarative Pipeline validation.

## Grafana login repair (2026-10-27)

The owner reported failed login. The generated password Secret contained a
trailing newline; it was normalized, and the bootstrap generator now emits
no newline. The admin password was synchronized through Grafana CLI stdin using
the live `/var/lib/grafana` data path. Authentication logs identified a temporary
failed-login lockout, which was allowed to expire without disabling protection.
POST /login then returned HTTP 200 Logged in; the resulting browser session
accessed /api/user as the Grafana admin with HTTP 200. Private passwords and
session cookies were not printed. No pod restart or data removal was performed.

## Replica repair — 2026-10-27

Owner authorized recovery of accidental single-replica quorum configuration.
GitOps commit `2f7c54b` restores Kafka/etcd/PostgreSQL to three and sets local
CPU-only app/query HPA 1–3; local singleton PDB minimum zero. Existing deployed
image tags retained. Source templates and release seed updated for future builds.
Helm lint/render and `bash local-ci/scripts/validate.sh` passed. Live recovery
verification is recorded below after reconciliation.

Live verification: Kafka/etcd/PostgreSQL each 3/3 ready. Kafka voters [1,2,3],
leader 3, high watermark 22488, maximum follower lag 0. Kafka CLI reported no
under-replicated or unavailable partitions. All three etcd endpoints successfully
committed health proposals. Patroni: primary plus synchronous standby and replica,
both streaming at zero lag. App 1/1, query 2/2 during HPA stabilization; both HPAs
CPU-only, min 1/max 3. Singleton local PDBs minimum 0; quorum PDBs minimum 2.
Argo Synced to 2f7c54b6fe80383c38573832266a87115b70b8b1, health Progressing
because pgbackrest-repo-pvc is pending. No backup readiness claim is made.

Storage anomaly: before/after PVC inventory detected kafka-data-kafka-0 UID
changed from 542d7693-9eee-4386-85e1-e5242480f6fd to
d070575d-c271-49be-8a49-063ef993c496 (created 10:58:46 UTC). No repair command
issued a PVC deletion or storage reset; actor/cause unverified. Kafka-1/2 and
other existing PVC identities unchanged. Surviving quorum is healthy and partition
checks pass, but these checks do not independently prove historical data loss zero.
