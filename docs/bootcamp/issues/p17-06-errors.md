# P17-06 Error History

## 2026-08-17 — Pool gauge test initialized a real connection pool

- **Operation:** Focused cache and datasource metric unit tests.
- **Result:** FAILED in `DataSourceMetricsTest`.
- **Error:** Hikari attempted to connect to the deliberately unreachable test
  JDBC URL and raised `PoolInitializationException`.
- **Root cause:** The test called the production pool factory to verify only
  meter registration; constructing `HikariDataSource` eagerly starts the pool.
- **Resolution plan:** Test the metric-registration boundary with a mocked
  Hikari datasource. Preserve production fail-fast pool initialization.

### Resolution

- Focused datasource and cache metric tests passed after narrowing the test to
  meter registration.

## 2026-08-17 — Prometheus image default entrypoint

- **Operation:** Validate `cache-replica-alerts.yml` with the pinned Prometheus
  2.52.0 container.
- **Result:** FAILED before rule parsing with `unexpected promtool`.
- **Root cause:** The image defaults to the `prometheus` executable; the command
  was passed as an argument instead of overriding the entrypoint.
- **Resolution plan:** Rerun the exact rule check with
  `--entrypoint=promtool`.

### Resolution

- `promtool` parsed all four rules successfully with the corrected entrypoint.

## 2026-08-17 — Full Spring context failed after datasource instrumentation

- **Operation:** `ApplicationIntegrationTest` Spring Boot startup verification.
- **Result:** FAILED; the application context reported a missing bean through a
  datasource-related dependency chain.
- **Impact:** This is a product integration failure; P17-06 cannot complete
  until the exact missing dependency is diagnosed and startup passes.
- **Resolution plan:** Inspect the complete test result, correct only the bean
  wiring defect, and rerun the same integration test.

### Diagnosis

- Exposing the replica monitor's `JdbcTemplate` as a bean left more than one
  `JdbcTemplate` candidate. Spring Boot therefore did not create its single
  `NamedParameterJdbcTemplate`, and Spring Data JDBC repository configuration
  backed off.
- **Resolution:** Keep the replica-only template private to `ReplicaLagMonitor`
  construction so the application's primary JDBC auto-configuration remains
  unambiguous.

### Resolution

- App and Order Query Spring startup integration suites passed.
- The actuator contract assertion found the new cache, datasource, and lag
  metric families.
- The full unit and architecture suite passed.
- **Status:** RESOLVED.
