# Architecture: PostgreSQL 3-Node High Availability & Patroni Topology

## Overview

In Phase 15 (ADR-0024, P15-02), the persistence tier transitions from a single PostgreSQL container to a deterministic 3-node PostgreSQL 16 streaming replication cluster governed by Patroni and an odd-sized 3-member `etcd` Distributed Configuration Store (DCS).

---

## 1. Component Topology & Port Mappings

```text
                     +---------------------------------------+
                     |         3-Member etcd Cluster         |
                     |  etcd-1 (2379)  etcd-2  etcd-3        |
                     +-------------------+-------------------+
                                         |
                                (etcd3 DCS Leases)
                                         |
         +-------------------------------+-------------------------------+
         |                               |                               |
         v                               v                               v
+-----------------+             +-----------------+             +-----------------+
|   postgres-1    |             |   postgres-2    |             |   postgres-3    |
| Patroni (8008)  |             | Patroni (8009)  |             | Patroni (8010)  |
| PostgreSQL:5432 |<--Stream--- | PostgreSQL:5433 |<--Stream--- | PostgreSQL:5434 |
|    (Primary)    |  Sync Rep   |  (Sync Standby) |  Async Rep  |    (Standby)    |
+-----------------+             +-----------------+             +-----------------+
```

| Container | Role | Published Ports | Internal Ports | Health Check |
|---|---|---|---|---|
| `hyperscale-etcd-1` | etcd DCS Member 1 | — | `2379`, `2380` | `etcdctl endpoint health` |
| `hyperscale-etcd-2` | etcd DCS Member 2 | — | `2379`, `2380` | `etcdctl endpoint health` |
| `hyperscale-etcd-3` | etcd DCS Member 3 | — | `2379`, `2380` | `etcdctl endpoint health` |
| `hyperscale-postgres-1` | Patroni / PG Node 1 | `5432`, `8008` | `5432`, `8008` | `curl -fsS http://localhost:8008/health` |
| `hyperscale-postgres-2` | Patroni / PG Node 2 | `5433`, `8009` | `5432`, `8008` | `curl -fsS http://localhost:8008/health` |
| `hyperscale-postgres-3` | Patroni / PG Node 3 | `5434`, `8010` | `5432`, `8008` | `curl -fsS http://localhost:8008/health` |

---

## 2. Replication & Durability Guarantees

- **Consensus Store:** 3-member etcd cluster ensures quorum ($2/3$) during single-node network or node failures.
- **Strict Synchronous Policy:**
  - `synchronous_mode: true` and `synchronous_mode_strict: true`
  - `synchronous_commit: on`
  - Patroni dynamically configures `synchronous_standby_names = 'ANY 1 (postgres-1, postgres-2, postgres-3)'`.
  - Every acknowledged commit is durably persisted on the primary and confirmed on at least one synchronous standby ($\text{RPO} = 0$).
- **Fencing & Anti-Split-Brain:**
  - Primary maintains a 30s TTL lease in etcd (`loop_wait=10s`, `retry_timeout=10s`).
  - If the primary fails to renew its lease before expiry, it immediately self-fences by demoting PostgreSQL to a read-only standby (`pg_ctl demote`).
  - A standby is promoted to primary only after successfully claiming the DCS leader lease.

---

## 3. Fast Diagnostic & Operator Commands

```bash
# Check Patroni cluster status across all nodes
curl -s http://localhost:8008/patroni | jq .
curl -s http://localhost:8009/patroni | jq .
curl -s http://localhost:8010/patroni | jq .

# Run preflight cluster checks
make ha-db-preflight

# Run automated replication and etcd resilience verification
make ha-db-verify
```
