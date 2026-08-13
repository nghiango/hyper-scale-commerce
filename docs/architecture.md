# Architecture

## Current Stage

Phase 0 — Engineering Foundation

## Target Initial Architecture

Modular monolith.

```text
                Client
                  |
                  v
              REST API
                  |
        +---------+---------+
        |         |         |
     Catalog    Cart      Order
        |         |         |
        +---------+---------+
                  |
                  v
              PostgreSQL
```

## Phase 1 Target: Catalog Package Module

When Phase 1 is implemented, the `app` module will remain a single deployable
and the first bounded context, Catalog, will be isolated by package:

```text
com.hyperscale.commerce
  modules
    catalog
      domain          # entities, value objects, repository interfaces
      application     # services, DTOs
      infrastructure  # repository implementations, row mappers
      api             # REST controllers
```

Allowed dependency direction:

```text
catalog.api -> catalog.application -> catalog.domain
catalog.infrastructure -> catalog.domain (implements interfaces)
```

Each bounded context will own a dedicated PostgreSQL schema. Catalog will own
the `catalog` schema and its `products` table. ArchUnit tests will enforce the
package and dependency rules. See ADR-0002 for the full decision record.