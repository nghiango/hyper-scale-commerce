# Runbook: PostgreSQL High Availability, Patroni Failover & Fencing

## Overview

In Phase 15 (ADR-0024), PostgreSQL operates as a 3-node streaming replication cluster (`postgres-1`, `postgres-2`, `postgres-3`) managed by Patroni daemons using a 3-member `etcd` Distributed Configuration Store (DCS).

This runbook provides on-call engineers with procedures to diagnose leader loss, resolve split-brain alerts, perform controlled switchovers, and reinitialize out-of-sync nodes.

---

## 1. Quick Diagnostics

```bash
# 1. Discover current active primary and published port
bash performance/scripts/get-primary-db.sh

# 2. Inspect Patroni cluster status from any node
curl -s http://localhost:8008/patroni | jq .

# 3. Check etcd DCS endpoint health
docker exec hyperscale-etcd-1 etcdctl endpoint health http://etcd-1:2379 http://etcd-2:2379 http://etcd-3:2379

# 4. Run automated database cluster preflight
make ha-db-preflight
```

---

## 2. Alert Response Procedures

### Alert: `PostgreSQLNoLeader`
- **Impact:** Write operations fail; `POST /orders` and Flyway migrations will block or timeout.
- **Triage:**
  1. Check etcd health: `docker exec hyperscale-etcd-1 etcdctl endpoint health`. If etcd has lost quorum ($< 2$ members running), restart failed etcd containers (`docker start hyperscale-etcd-1`).
  2. Inspect Patroni container logs: `docker logs hyperscale-postgres-1`.
  3. Verify PostgreSQL process health on standby nodes. Once etcd quorum is restored, Patroni will automatically promote the standby with the most advanced WAL offset.

---

### Alert: `PostgreSQLSplitBrain`
- **Impact:** Critical integrity risk. (Note: In Phase 15, strict etcd leases and local watchdog demotions prevent split brain).
- **Triage:**
  1. Immediately check which nodes report `is_leader: true`:
     ```bash
     curl -s http://localhost:8008/patroni | jq '.role'
     curl -s http://localhost:8009/patroni | jq '.role'
     curl -s http://localhost:8010/patroni | jq '.role'
     ```
  2. If an isolated node failed to demote, forcefully stop it:
     ```bash
     docker stop hyperscale-postgres-isolated
     ```
  3. Reinitialize the out-of-sync node using `pg_rewind` upon restart to rejoin as standby.

---

### Alert: `PostgreSQLSyncStandbyMissing`
- **Impact:** If `synchronous_mode_strict` is active, write transactions will block until a synchronous standby acknowledges commits.
- **Triage:**
  1. Check replication connection status on primary:
     ```bash
     docker exec hyperscale-postgres-1 psql -U hyperscale -d hyperscale -c "SELECT * FROM pg_stat_replication;"
     ```
  2. Check why standbys disconnected: inspect standby container logs (`docker logs hyperscale-postgres-2`).
  3. Restart down standby containers (`docker start hyperscale-postgres-2`).

---

## 3. Maintenance Procedures

### Performing a Controlled Manual Switchover
To gracefully transfer primary leadership (e.g. for host maintenance):
```bash
# Execute graceful switchover via Patroni REST API
docker exec hyperscale-postgres-1 patronictl -c /var/lib/postgresql/patroni.yml switchover
```
*Patroni gracefully demotes the current primary and promotes a healthy synchronous standby with zero data loss ($\text{RPO} = 0$).*
