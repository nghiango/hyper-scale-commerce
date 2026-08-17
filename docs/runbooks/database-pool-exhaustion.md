# Runbook: PostgreSQL Connection Pool Exhaustion & Latency Remediation

**Severity:** P1 / Critical  
**Alert References:**
- `HikariCpConnectionTimeout` (`hikaricp_connections_timeout_total > 0`)
- `HikariCpConnectionLeakDetected` (`hikaricp_connections_leak_total > 0`)
- `CatalogApiP95LatencyBreached` / `OrderCreationApiP95LatencyBreached`  
**Components:** PostgreSQL 16, HikariCP Connection Pools (`app`, `order-query`)

---

## 1. Trigger & Overview

HikariCP connection pool exhaustion occurs when all pooled database connections are actively leased and incoming threads wait longer than the 5,000ms `connection-timeout` budget. This causes HTTP 500/503 errors and p95 latency breaches.

---

## 2. Step-by-Step Diagnostics

### Step 2.1: Check Active Connections & Lock Contention in PostgreSQL
Execute the following diagnostic query in PostgreSQL:
```sql
SELECT 
    pid,
    usename,
    client_addr,
    application_name,
    state,
    now() - state_change AS state_duration,
    now() - query_start AS query_duration,
    wait_event_type,
    wait_event,
    query
FROM pg_stat_activity
WHERE state <> 'idle' AND pid <> pg_backend_pid()
ORDER BY query_start ASC;
```

### Step 2.2: Check for Blocked Queries & Locks
```sql
SELECT 
    blocked_locks.pid     AS blocked_pid,
    blocked_activity.usename  AS blocked_user,
    blocking_locks.pid    AS blocking_pid,
    blocking_activity.usename AS blocking_user,
    blocked_activity.query    AS blocked_statement,
    blocking_activity.query   AS current_statement_in_blocking_process
FROM  pg_catalog.pg_locks         blocked_locks
JOIN pg_catalog.pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
JOIN pg_catalog.pg_locks         blocking_locks 
    ON blocking_locks.locktype = blocked_locks.locktype
    AND blocking_locks.database IS NOT DISTINCT FROM blocked_locks.database
    AND blocking_locks.relation IS NOT DISTINCT FROM blocked_locks.relation
    AND blocking_locks.page IS NOT DISTINCT FROM blocked_locks.page
    AND blocking_locks.tuple IS NOT DISTINCT FROM blocked_locks.tuple
    AND blocking_locks.virtualxid IS NOT DISTINCT FROM blocked_locks.virtualxid
    AND blocking_locks.transactionid IS NOT DISTINCT FROM blocked_locks.transactionid
    AND blocking_locks.classid IS NOT DISTINCT FROM blocked_locks.classid
    AND blocking_locks.objid IS NOT DISTINCT FROM blocked_locks.objid
    AND blocking_locks.objsubid IS NOT DISTINCT FROM blocked_locks.objsubid
    AND blocking_locks.pid != blocked_locks.pid
JOIN pg_catalog.pg_stat_activity blocking_activity ON blocking_activity.pid = blocking_locks.pid
WHERE NOT blocked_locks.granted;
```

---

## 3. Remediation Actions

### Action A: Terminate Rogue / Stalled Transactions
If a specific transaction is holding locks or stalled:
```sql
-- Gracefully cancel query:
SELECT pg_cancel_backend(<BLOCKING_PID>);

-- Forcefully terminate connection if cancel does not respond:
SELECT pg_terminate_backend(<BLOCKING_PID>);
```

### Action B: Review Leak Detection Logs
Search application container logs for HikariCP leak detection warnings:
```bash
docker logs hyperscale-app 2>&1 | grep -i "Apparent connection leak"
docker logs hyperscale-order-query 2>&1 | grep -i "Apparent connection leak"
```
HikariCP emits stack traces showing exactly which method opened the unclosed connection.

### Action C: Connection Pool Tuning
If genuine traffic spikes exceed current pool capacity:
1. Ensure PostgreSQL `max_connections` is sufficient:
   ```sql
   SHOW max_connections;
   ```
2. Verify HikariCP `maximum-pool-size` (default: 50) and adjust if needed:
   ```yaml
   spring:
     datasource:
       hikari:
         maximum-pool-size: 75
         connection-timeout: 5000
   ```
