# HyperScale Commerce

A commerce platform that evolves incrementally from a modular monolith into a
resilient cloud-native distributed system, following the HyperScale Commerce
Engineering BootCamp phases.

Current stage: **Phase 0 — Engineering Foundation** (in progress).

## Documentation

- `AGENTS.md` — engineering rules for implementation work
- `docs/constitution.md` — mission, targets, architectural evolution rules
- `docs/requirements.md` — business domain requirements
- `docs/architecture.md` — current architecture
- `docs/bootcamp/` — BootCamp phase definitions and plans
- `docs/adr/` — architecture decision records

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

See `docs/bootcamp/phase-00-plan.md` for the approved implementation plan.
