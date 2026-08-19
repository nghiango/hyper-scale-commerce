# HyperScale Commerce

A commerce platform that evolves incrementally from a modular monolith into a
resilient cloud-native distributed system, following the HyperScale Commerce
Engineering BootCamp phases.

Current stage: **Phase 5 — Service Extraction** (in progress).

## Catalog API

The Catalog endpoints are available once the application is running:

- `GET /catalog/products` — list products with optional `query`, `page`, and `size`
- `GET /catalog/products/{id}` — get a product by id
- `GET /catalog/products/sku/{sku}` — get a product by SKU
- `GET /catalog/products/{id}/availability` — get product availability

OpenAPI documentation is generated at `/v3/api-docs` and rendered by Swagger UI
at `/swagger-ui.html`.

## Documentation

- `AGENTS.md` — engineering rules for implementation work
- `docs/constitution.md` — mission, targets, architectural evolution rules
- `docs/requirements.md` — business domain requirements
- `docs/architecture.md` — current architecture
- `docs/bootcamp/` — BootCamp phase definitions and plans
- `docs/adr/` — architecture decision records
- `docs/tutorials/linear-tickets-from-bootcamp-plans.md` — converting BootCamp phase plans into Linear projects and issues

## Prerequisites

- JDK 21
- Docker (for PostgreSQL and Testcontainers-based integration tests)
- Make

No global Gradle installation is required; the Gradle wrapper is used.

## Local infrastructure

PostgreSQL 16 runs via Docker Compose:

```sh
docker compose up -d    # start PostgreSQL (waits for healthcheck)
docker compose ps       # check status
docker compose down     # stop (data persists in the postgres-data volume)
```

Connection defaults (local development only, overridable via environment):

| Setting | Default | Environment variable |
|---|---|---|
| Host | `localhost` | `POSTGRES_HOST` |
| Port | `5432` | `POSTGRES_PORT` |
| Database | `hyperscale` | `POSTGRES_DB` |
| User | `hyperscale` | `POSTGRES_USER` |
| Password | `hyperscale` | `POSTGRES_PASSWORD` |

## Development setup

```sh
git clone <repository-url>
cd hyper-scale-commerce

make up        # start PostgreSQL via Docker Compose
make test      # run unit and integration tests
make verify    # formatting check, static analysis, build, all tests
make run       # run the app with the local profile (human-readable logs)
make down      # stop infrastructure
make clean     # remove build outputs
```

Without the `local` profile, application logs are emitted as JSON for
aggregation (see `app/src/main/resources/logback-spring.xml`).

## Running both services

Phase 5 extracts the Order query side into the `order-query` service. The
monolith (`app`) serves the command side and Catalog/Inventory; `order-query`
serves `GET /orders*` from its own read model.

### Locally via bootRun (two terminals)

```sh
./gradlew :app:bootRun          # monolith on http://localhost:8080
./gradlew :order-query:bootRun  # order-query on http://localhost:8081
```

Start PostgreSQL and Kafka first with `make up`.

### As containers via Compose

```sh
make services   # build bootJars, build images, start both services
```

Or directly:

```sh
./gradlew :app:bootJar :order-query:bootJar
docker compose --profile services up -d --build
```

Both services wait for healthy PostgreSQL and Kafka, then expose:

| Service | Port | Key endpoints |
|---|---|---|
| `app` | 8080 | `POST /orders`, Catalog, Inventory |
| `order-query` | 8081 | `GET /orders`, `GET /orders/{id}` |

Stop everything (including the services) with `make down`.

See `docs/bootcamp/phase-00-plan.md` and `docs/bootcamp/phase-01-plan.md` for the approved implementation plans.
