# P5-07 — Flyway config collision between `app` and `order-query`

## Symptom

- `app` integration tests (e.g. `CatalogControllerIntegrationTest`) failed with `BadSqlGrammarException: relation "catalog.products" does not exist`.
- `make verify` failed on catalog/insert errors because Flyway did not create the monolith catalog schema.

## Root cause

- `app/build.gradle.kts` adds `integrationTestImplementation(project(":order-query"))`, which puts `order-query/src/main/resources/application.yml` on the monolith's integration-test classpath.
- Spring Boot's `ConfigFileApplicationListener` loads the **first** `application.yml` found on the classpath. In the integration-test classpath order, `order-query/application.yml` was loaded first.
- That file sets `spring.flyway.locations=classpath:db/migration-order-query` and `spring.flyway.schemas=order_query`, so the monolith's Flyway ran only the `order-query` migration and created only the `order_query` schema.
- The `catalog`, `order`, and `inventory` tables were therefore missing, causing `catalog.products` insert failures.

## Fix applied

1. Renamed `order-query/src/main/resources/application.yml` → `orderquery.yml` so the monolith never accidentally loads it.
2. Set `spring.config.name=orderquery` for the `order-query` application in:
   - `OrderQueryApplication.main` (default property)
   - `order-query/build.gradle.kts` `Test.systemProperty`
   - `ServiceExtractionE2ETest.startOrderQuery`
   - `compose.yaml` `order-query` service environment
3. Applied `spotlessApply` to `ServiceExtractionE2ETest`.

## Verification

- `./gradlew :app:integrationTest --tests "CatalogControllerIntegrationTest"` PASSED.
- `make verify` BUILD SUCCESSFUL; `ServiceExtractionE2ETest > order flows across the extracted services()` PASSED.
- Evidence captured in `docs/bootcamp/evidence/p5-service-extraction.md`.

## Questions for reviewer

- Is the `orderquery.yml` naming acceptable, or would you prefer `application-orderquery.yml` with an active profile?
- Should this cross-module `application.yml` collision be written up as an ADR or added to the phase-05 plan?
- Should `SPRING_CONFIG_NAME=orderquery` also be set in `order-query/Dockerfile` for extra clarity?
