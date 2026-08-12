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

## Development setup

The application skeleton is not yet implemented (Phase 0 tasks P0-02 onward).
Once available, the intended workflow is:

```sh
git clone <repository-url>
cd hyper-scale-commerce

make up        # start PostgreSQL via Docker Compose
make test      # run unit and integration tests
make verify    # formatting check, static analysis, build, all tests
```

Until then, this repository contains documentation only. See
`docs/bootcamp/phase-00-plan.md` for the approved implementation plan.
