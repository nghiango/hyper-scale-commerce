# P17-08 Error and Completion Record

## 2026-08-17 — Formal review initially failed

- **Symptom:** The existing phase review reported unwired Redis L2 caching,
  cache invalidation, datasource routing, and inadmissible qualification data.
- **Cause:** P17-08 had been run before the P17-03 through P17-07 remediation
  and runtime qualification were complete.
- **Correction:** Re-reviewed production wiring, task evidence, Kubernetes
  manifests, raw qualification artifacts, and phase exit criteria after the
  remediation was implemented.

## 2026-08-17 — Aggregate integration suite exposed shared-state failures

- **Symptom:** Tests passed in isolation but failed in the full suite with stale
  cached order state, duplicate catalog fixtures, datasource misrouting, and
  exact single-pool health assertions.
- **Cause:** Cross-service tests reused a developer Redis endpoint; fixture
  inserts assumed globally empty databases; routing configuration overrode
  Testcontainers' dynamic datasource URL; health checks assumed one datasource.
- **Correction:** Added per-suite Redis containers, idempotent fixtures,
  Boot-managed connection details with a manual-bootstrap property fallback,
  composite readiness assertions, and stable pagination expectations.
  `./gradlew test integrationTest` then completed successfully.

## 2026-08-17 — Helm verifier false failures under `pipefail`

- **Symptom:** Valid rendered manifests failed checks when `grep -q` closed a
  Helm pipeline early.
- **Cause:** Upstream Helm received SIGPIPE while `set -o pipefail` was active.
- **Correction:** Captured rendered manifests before checking their content.
  Stateful, stateless, security, resource-budget, and Redis static checks pass.

## 2026-08-17 — Full build exposed static-analysis and isolation gaps

- **Symptom:** `./gradlew build` initially failed Detekt and Spotless, then
  revealed that routing tests could accidentally use a developer PostgreSQL on
  `localhost:5432`.
- **Cause:** New fail-open code lacked narrow lint suppressions, several values
  lacked named constants, and `@ServiceConnection` was incorrectly assumed to
  populate `spring.datasource.url`.
- **Correction:** Applied repository formatting, corrected all Detekt findings,
  and made both routing configurations prefer optional Spring Boot
  `JdbcConnectionDetails` with a property fallback for manual bootstraps. The
  final `./gradlew build --no-daemon` passed all 42 tasks, including architecture,
  unit, and integration gates.

## Completion

- **Status:** COMPLETED
- **Review:** Phase 17 PASSED.
- **Evidence:** `docs/bootcamp/evidence/p17-phase-review.md`
