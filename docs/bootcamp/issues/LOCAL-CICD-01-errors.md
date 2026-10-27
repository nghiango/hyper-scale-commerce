# LOCAL-CICD-01 error history

## 2026-10-27 — Attempt 1: release invariant verification

Command: `bash local-ci/scripts/validate.sh`. Helm lint passed both charts,
but image-tag assertion failed for an all-numeric 40-character test SHA.
Cause: unquoted YAML tags were treated as numbers. Resolution: quote all
generated commit tags and revision strings. Re-run recorded in evidence.

## 2026-10-27 — Docker access preflight

Sandbox denied access to the Docker socket. Approved escalated Docker preflight
returned aarch64. Host JDK is 26, so verification uses a JDK 21 container.

## 2026-10-27 — Attempt 1: JDK 21 full build

`docker run ... eclipse-temurin:21-jdk-jammy ./gradlew spotlessApply build --no-daemon`
failed at `:app:compileJava` with 69 missing generated jOOQ table references.
The existing DDLDatabase scripts setting points at a directory rather than SQL
files and the generated-source directory is empty. Tests did not execute.
A focused build-configuration correction is required for clean CI builds.

## 2026-10-27 — Attempt 2: build / trace export regression test

The SQL glob correction restored compilation. 82 of 83 app unit tests passed;
`TraceExportTest.completedSpanReachesZipkinReceiver` failed on payload content.
The real HTTP sender compresses payloads; the test receiver must decode gzip
before asserting the Zipkin JSON. The transport must not be changed to satisfy
the test. Integration tests had not yet run.

## 2026-10-27 — Trace test: sender health probe

After gzip decoding, the receiver first saw `[]`, the sender's startup health
probe. The test must ignore this empty probe and await the actual span batch.
This is a different observed failure from compressed payload decoding.

## 2026-10-27 — Registry bootstrap shell lookup

The registry rollout succeeded, but `docker exec -i <node> sh` failed with
an executable lookup error. `/bin/sh` succeeds; bootstrap now uses an absolute
shell path for the piped containerd configuration write.

## 2026-10-27 — Agent Helm checksum verification

Agent image build downloaded Helm under a renamed basename, while its official
checksum file refers to the upstream basename. Checksum validation correctly
failed. Preserve the upstream basename before running sha256sum; do not skip
verification.

### Registry bootstrap diagnosis correction

An absolute shell path did not resolve the error. `kind get nodes` includes the
external load-balancer container in this kind release; it has no shell and no
containerd. Use Kubernetes Node object names as the authoritative node list.
Actual worker and control-plane containers have `/bin/sh` and containerd.

## 2026-10-27 — Full integration suite: PostgreSQL outage fixture

The app's `PostgresOutageIntegrationTest` failed during container initialization.
The retained container log says `FATAL: data directory ... has wrong ownership`.
Both services' outage fixtures bind a JVM-created temporary directory through
Docker Desktop. This assumption is invalid for a containerized Jenkins agent.
Use a unique Docker-managed named volume, retained across the fixture's stop /
start and explicitly removed in afterAll. This is CI fixture portability only;
no application persistence behavior or outage assertion is weakened.

## 2026-10-27 — Existing readiness contract mismatch exposed

With Docker-managed volumes, the outage fixture reaches the business test, then
fails at PostgresOutageIntegrationTest.java:105: it awaits DOWN from
`/actuator/health/readiness` after stopping PostgreSQL. The configured group
contains only `readinessState`; application code does not change that state
on a database outage. Changing deployment readiness semantics requires an
explicit architectural decision and is not silently added to the delivery task.
The full build remains failed; publishing must remain blocked. The attempted
focused Gradle command placed --tests after only the last task, so app tests
were not filtered. The failed attempt was stopped; no green full-build claim.

## 2026-10-27 — Owner-approved readiness resolution

The owner chose: keep readiness independent and change outage tests to inspect
aggregate health. Both fixtures now check the top-level aggregate status, the
`db` component status and HTTP 200/503, and verify readiness remains UP during
the outage. Redis health is disabled only in these PostgreSQL/Kafka fixtures,
which do not start Redis. Query fixture Kafka configuration now uses the
advertised Testcontainers bootstrap address inside the containerized agent.
Focused tests and full-build results are recorded in the verification report.

### Related container-agent bootstrap assumption found during review

The query-side Kafka outage fixture had the same hardcoded localhost broker
address. Its producer now uses the restarted container's advertised address;
its offline-start configuration uses the Docker host plus the intentionally
fixed port (getBootstrapServers cannot be called while the broker is stopped).
The complete build covers this fixture without weakening its outage checks.

## 2026-10-27 — Full build after readiness resolution: catalog SLO failure

Both corrected PostgreSQL outage regressions passed in 1m 56s. The subsequent
full build failed in 6m 33s: 61/62 app integration tests passed, including the
corrected PostgreSQL outage test. The one failure was the pre-existing
CatalogSloVerificationTest: measured p95 163.508334 ms, required <100 ms.
The earlier progress update missed that failure among the full-run output;
final XML reports are authoritative. No threshold, workload or test exclusion
is changed. The order-query integration suite is run separately because the
app task failure prevented it from executing in the full-build invocation.

## 2026-10-27 — Resolution verification

Focused PostgreSQL outage tests: both passed (BUILD SUCCESSFUL in 1m 56s).
Full app integration run: corrected outage test passed; 61/62 tests passed.
Only CatalogSloVerificationTest failed at 163.508334 ms p95 versus <100 ms.
Separate query integration and all-module Spotless: BUILD SUCCESSFUL in 2m 44s;
17/17 integration tests passed, including the corrected Kafka-host fixture and
PostgreSQL outage/recovery fixture. Unit/architecture reports remain 83/83 app
and 29/29 query. No readiness production configuration was changed, no tests
were skipped, and the catalog threshold remains unchanged. The readiness issue
is resolved; the broader LOCAL-CICD-01 task remains incomplete on the catalog
SLO and the previously documented external CI/GitOps/webhook setup.

## 2026-10-27 — Jenkins plugin provisioning follow-up

The initial controller (2.541.3) selects plugin versions with published security
advisories. A latest-compatible dependency attempt also encountered a refusing
Jenkins download mirror (sg.mirror.servanamanaged.com). Official stable version
metadata reports 2.580.1; update the fresh controller before storing credentials
and retry from a reachable official download source. No private keys were
printed. Argo's repository key is configured; its platform Application is Synced
and Healthy, and commerce remains manual-sync with pending-ci values.

### Jenkins readiness polling correction

Immediately after the plugin-loading restart, the local HTTP listener closed a
connection before returning a response. The readiness loop handled HTTP status
and URL errors but not RemoteDisconnected. Extend the bounded startup poll to
handle HTTP transport/disconnect errors before importing any secrets. No key
was imported and no environment secret was written by that failed attempt.

### Jenkins and repository provisioning verified

Controller 2.580.1 and current compatible plugins loaded with no failed plugins.
Dedicated credential import, online agent enrollment and temporary Jenkins
GitOps SSH access-check job succeeded. Temporary import and diagnostic job were
removed; private material was not printed. Argo platform is Synced/Healthy and
commerce remains manual. The owner selected local two-minute Git polling instead
of public exposure; source job stays held until its CI definition is published.
The catalog SLO and source publication remain unresolved; no release is promoted.

### Polling configuration response diagnosis

The owner selected local Git polling. Updating the controller trigger returned
non-JSON script output, so the provisioning helper failed JSON parsing. The
private-key import cleanup still ran in finally. Inspect the job's public API
method signatures and trigger state before retrying; do not assume polling or
a source-publication hold is active until confirmed by the controller.

The installed WorkflowJob API has setTriggers(List), but no removeTrigger method.
Reflection verified the supported signature. Replace the trigger list via
setTriggers rather than removing descriptors individually. Provisioning now
also captures non-JSON diagnostics only in an ignored mode-0600 file instead
of losing the controller-side error. Retry follows this concrete API correction.

Polling API correction succeeded: controller confirms only SCMTrigger at
H/2, the job is held pending source CI publication, and the agent remains online.
The updated Jenkinsfile passed controller-side Declarative validation.

## 2026-10-27 — Grafana admin login repair

The owner could not log in. The stored generated secret had a trailing newline;
raw and trimmed values both returned HTTP 401. Normalized the secret and fixed
the generator to emit no newline. A stdin-only CLI reset reported success but
the live API still returned 401, indicating the CLI's effective configuration
must be checked against the running server data path before another reset.
Private credential values were not printed; the running pod was not restarted.

The running server explicitly uses /var/lib/grafana. A reset with that exact
CLI config override still produced HTTP 401. Authentication logs identify the
actual outstanding reason: the admin user is temporarily blocked for too many
failed login attempts. No third password reset, protection disabling or database
counter manipulation is performed; allow the configured lockout window to
expire before verifying the already-reset password again. The earlier data-path
hypothesis was not sufficient to explain the response.

Grafana login repair verified: after the temporary lockout cleared, POST /login
with the cleaned stored credential returned HTTP 200 Logged in, and the issued
session authenticated GET /api/user as admin (HTTP 200). Login protection remains
enabled. No pod restart or database/dashboard deletion was needed. The bootstrap
password generator now strips its trailing newline for future installations.

## 2026-10-27 — Replica repair verification

Initial state: Kafka 0/1 ready, configured voters [1] conflict with persisted
[1,2,3]; etcd membership query timed out after scale-down. Owner approved restoring
three-member quorums and the local lightweight profile. No storage reset used.
A Patroni verification command initially referenced nonexistent /tmp/patroni.yml;
corrected to /var/lib/postgresql/patroni.yml from the entrypoint. Corrected command
confirmed Leader, Sync Standby and Replica, both standbys streaming at zero lag.

Storage verification found Kafka-0 PVC replaced during reconciliation (UID
542d7693-9eee-4386-85e1-e5242480f6fd → d070575d-c271-49be-8a49-063ef993c496).
Repair commands made no PVC deletion or reset. Cause/actor unresolved; no further
storage mutation attempted. Kafka quorum recovered with zero follower lag and no
unavailable/under-replicated partitions. Pending pgBackRest PVC keeps Argo health
Progressing; backup readiness is outside this replica repair's demonstrated result.
