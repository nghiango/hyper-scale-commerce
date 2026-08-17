# P17-05 Error History

## 2026-08-17 — Full verification sandbox lock

- **Operation:** Full `./gradlew test` followed by Kubernetes manifest verification.
- **Result:** FAILED before Gradle configuration.
- **Error:** The workspace sandbox denied creation of the Gradle distribution
  lock under `/Users/ngng/.gradle/wrapper/dists`.
- **Root cause:** The Gradle user-home cache is outside the writable workspace;
  this is an execution-environment permission failure, not a product failure.
- **Resolution plan:** Rerun the same test command using the repository's
  previously approved Gradle execution permission, without changing code or
  verification scope.

### Resolution

- The full unit and architecture suite passed after rerunning with access to
  the existing Gradle cache.
- The targeted two-PostgreSQL-container routing integration test passed.
- Helm rendering and the Redis runtime verification harness passed.
- **Status:** RESOLVED.
