# ADR-0024: PostgreSQL High Availability, Fencing, and Disaster Recovery Strategy

## Status

Accepted

---

## Context

Through Phases 1 to 14, HyperScale Commerce evolved from a single-container monolith into a horizontally replicated, event-driven distributed system:
- Multi-replica application services (`app-1`, `app-2`) and query projections (`order-query-1`, `order-query-2`) behind a health-aware HAProxy ingress (ADR-0023).
- Deterministic 3-node Kafka KRaft cluster with replication factor 3, `min.insync.replicas=2`, and `acks=all` producer durability (ADR-0023).
- Non-blocking outbox worker coordination (`SKIP LOCKED`), monotonic version guards, and ingress-level rate limiting.

However, Phase 14 left **PostgreSQL as the sole remaining persistent single failure domain**:
1. **Single Primary Persistence:** PostgreSQL runs as a single primary instance (`hyperscale-postgres`) on a single Docker volume.
2. **Total Write/Read Unavailability on Primary Loss:** As verified in Phase 14's negative control (`P14-06`), termination or crash of the PostgreSQL primary renders all application commands and projections unready and inoperative.
3. **Data Loss & Split-Brain Risks:** Naive promotion or uncoordinated failover scripts without consensus leases risk catastrophic split-brain scenarios where two database nodes accept concurrent writes, irrecoverably corrupting transactional data, inventory balances, and outbox event streams.
4. **Lack of Independent Disaster Recovery:** Live streaming replication alone does not protect against catastrophic corruption, data truncation, or accidental drop errors; a verifiable physical backup and Point-in-Time Recovery (PITR) mechanism is required.

To achieve cloud-native resilience, Phase 15 must introduce a consensus-governed, self-fencing, zero-data-loss PostgreSQL high-availability and disaster recovery topology within the local qualification environment.

---

## Decision

We adopt a comprehensive PostgreSQL High Availability, Fencing, and Disaster Recovery Strategy for Phase 15 comprising the following architectural pillars:

```text
                         HAProxy HTTP Ingress (Ports 8080 & 8081)
                                   /             \
                       app replicas (2)       order-query replicas (2)
                                   \             /
                         Multi-Host JDBC Connection URL
                        targetServerType=primary (libpq/Hikari)
                             /          |          \
                       postgres-1   postgres-2   postgres-3
                        (Primary)  (Sync Standby) (Standby)
                             \          |          /
                               Patroni Agents (3.x)
                                        |
                             3-Node etcd DCS Cluster
                                        |
                          (Leader Lease & Cluster State)

         PostgreSQL WAL Archive ───► Dedicated pgBackRest Volume
                                                │
                                    Isolated PITR Target Volume
```

---

### 1. Consensus-Based Leader Election and Patroni DCS Architecture
- **Consensus Store (etcd):** Deploy a dedicated 3-member `etcd` cluster (`etcd-1`, `etcd-2`, `etcd-3`) as the Distributed Configuration Store (DCS). An odd-sized cluster ensures majority quorum ($Q = \lfloor N/2 \rfloor + 1 = 2$) and split-brain immunity during network partitions.
- **Patroni Management Daemon:** Each PostgreSQL 16 node runs alongside a Patroni daemon (`patroni:3.x`). Patroni acquires and maintains a TTL-bounded leader key in `etcd` (`loop_wait=10`, `ttl=30`, `retry_timeout=10`).
- **Primary Demotion & Fencing:** If a primary node loses its `etcd` lease (e.g. due to process stall, network partition, or isolation), Patroni immediately fences the node by executing a local graceful demotion to read-only standby (`pg_ctl demote`). If the demotion hangs, watchdog timers force immediate process termination.

---

### 2. Durability Contract & Strict Synchronous Replication
- **Replication Topology:** 3 PostgreSQL nodes (`postgres-1`, `postgres-2`, `postgres-3`) configured with streaming physical replication and physical replication slots (`max_replication_slots=10`).
- **Zero Data Loss Contract ($\text{RPO} = 0$):**
  - Configured with `synchronous_mode: true` and `synchronous_commit: on`.
  - Patroni dynamically manages `synchronous_standby_names = 'ANY 1 (postgres-1, postgres-2, postgres-3)'`.
  - Every committed transaction must be durably written to the primary WAL and confirmed on at least one synchronous standby before acknowledging the client.
- **Standby Loss Behavior:** If all standbys are lost, writes safely block to prevent un-replicated data divergence. Standby recovery automatically unblocks write transactions without data loss.

---

### 3. Primary-Aware Client Routing (Multi-Host JDBC)
- **Direct Multi-Host JDBC Discovery:** Applications and Flyway connect via standard PostgreSQL JDBC driver multi-host connection URLs:
  ```properties
  spring.datasource.url=jdbc:postgresql://postgres-1:5432,postgres-2:5432,postgres-3:5432/hyperscale?targetServerType=primary&connectTimeout=5&socketTimeout=30
  ```
- **Transparent Failover & Pool Recovery:**
  - HikariCP connection pools detect connection loss upon primary demotion/termination.
  - The JDBC driver iterates through the host list, discovers the newly promoted primary via `SHOW transaction_read_only` (`OFF`), and transparently re-establishes the connection pool.
  - Eliminates the operational complexity, latency overhead, and single point of failure of an intermediate database proxy layer (e.g. ProxySQL or PgBouncer failover wrappers).
- **Migration Primary Boundary:** Flyway migrations execute exclusively against the single writable primary discovered via `targetServerType=primary`, strictly maintaining separate schema histories (`catalog`, `order`, `inventory`, `order_query`).

---

### 4. Physical Backup, Continuous WAL Archiving & Point-In-Time Recovery (PITR)
- **Tooling:** Adopt `pgBackRest` as the dedicated disaster recovery engine.
- **Continuous WAL Archiving:** Configured with PostgreSQL `archive_mode = on` and `archive_command = 'pgbackrest --stanza=hyperscale archive-push %p'`. WAL segments are streamed continuously to a dedicated, persistent storage volume (`pgbackrest-repo`).
- **Backup Schedule & Verification:**
  - Automated full and differential backups with checksum validation.
  - Automated Disaster Recovery verification: Restores to an isolated test volume, recovers to a specific target timestamp / restore sentinel transaction, and verifies 100% data integrity without impacting the active cluster.

---

### 5. Quantitative Reliability Targets & Recovery Contracts

| Reliability Vector | Metric / Invariant | Target |
|---|---|---|
| **Recovery Point Objective (RPO)** | Data loss for acknowledged commits | **$\text{RPO} = 0$** (Strict synchronous standby) |
| **Recovery Time Objective (RTO)** | Time from primary crash to writable recovery | **$\text{RTO} \le 30\text{s}$** (Lease expiration + promotion + JDBC reconnect) |
| **Split-Brain Invariant** | Simultaneous writable primaries | **$0$ (Strictly forbidden by etcd quorum and fencing)** |
| **Steady-State Critical API Latency** | `GET /catalog/products`, `POST /orders`, `GET /orders` | **$\text{p95} < 200\text{ms}$** |
| **Cross-Schema Data Reconciliation** | Consistency across `order`, `inventory`, `order_query` | **100% Match (0 discrepancies)** |

---

## Alternatives Considered

1. **Manual / Native PostgreSQL Promotion without DCS:**
   - *Rejected:* Lacks automated failure detection, introduces high RTO (minutes to hours of downtime waiting for human intervention), and risks operator split-brain error.
2. **Intermediate TCP / Database Proxy Layer (HAProxy TCP / PgBouncer / ProxySQL):**
   - *Rejected:* Adds an extra network hop, requires health check polling synchronization, and introduces a single point of failure unless the proxy layer itself is clustered. Multi-host JDBC URLs natively provide client-side primary discovery without added infrastructure.
3. **Distributed Raft NewSQL (CockroachDB / YugabyteDB):**
   - *Rejected:* Violates technology stack constraints. PostgreSQL is the constitutional source of truth for the platform.
4. **Logical Backups (`pg_dump`) as Primary DR Strategy:**
   - *Rejected:* `pg_dump` takes snapshot dumps without streaming WAL archives, making point-in-time recovery impossible and resulting in significant data loss ($\text{RPO} > 0$) between dump intervals.

---

## Consequences & Operational Impact

### Positive
- **Complete Elimination of Database SPOF:** The database tier survives the abrupt failure of any single node without data loss.
- **Automatic Client Reconnect:** Applications automatically track database primary promotions without requiring container restarts or reconfiguration.
- **Verifiable Disaster Recovery:** Continuous WAL archiving and automated PITR tests guarantee physical recovery capability against logical or catastrophic data corruption.

### Tradeoffs & Operational Constraints
- **Synchronous Write Latency:** Strict synchronous replication adds intra-cluster network round-trip latency to transaction commits (measured and accounted for within the sub-200ms p95 budget).
- **Cluster Resource Footprint:** Requires 3 PostgreSQL containers, 3 etcd containers, and dedicated backup storage volumes.
- **Explicit Failure Domain Boundary:** All nodes in Phase 15 run on a single local Docker host. High availability is proved against container and process failures, not physical host or multi-zone outages.
