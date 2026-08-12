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