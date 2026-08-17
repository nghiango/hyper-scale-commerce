# HyperScale Commerce — JVM Diagnostics & Operational Runbook

## 1. Scope & Objective

This runbook defines operational standards, non-disruptive live diagnostics, incident troubleshooting playbooks, and profiling workflows for HyperScale Commerce JVM workloads running on JDK 21.

---

## 2. Standard Container JVM Runtime Configuration

All containerized deployments (`app`, `order-query`) must execute with container-aware ergonomics:

```bash
JAVA_TOOL_OPTIONS="
  -XX:+UseG1GC
  -XX:+ExitOnOutOfMemoryError
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/diagnostics/heapdump.hprof
  -XX:NativeMemoryTracking=summary
  -XX:MaxRAMPercentage=75.0
  -XX:InitialRAMPercentage=50.0
"
```

### Rationale
- **`-XX:+UseG1GC`:** Low-latency garbage collection with predictable pause targets for p95 $\le 200\text{ms}$ critical SLOs.
- **`-XX:MaxRAMPercentage=75.0`:** Automatically dimensions max heap based on cgroup memory limits (leaving 25% for Metaspace, thread stacks, off-heap buffers, and OS overhead).
- **`-XX:+ExitOnOutOfMemoryError`:** Ensures failed pods restart immediately to prevent lingering in a corrupted, half-dead state.
- **`-XX:+HeapDumpOnOutOfMemoryError`:** Generates post-mortem diagnostic artifacts prior to container exit.

---

## 3. Live Diagnostics & Profiling Procedures

### 3.1 Bounded diagnostic bundle

The container images use the JDK runtime because the operational contract
requires `jcmd`, `jstack`, and `jfr`. Do not assume a fixed profiling overhead;
measure it for the exact workload and environment.

Capture a 60-second bundle from an explicit pod and container:

```bash
performance/jvm/capture-jvm-diagnostics.sh \
  --pod <pod-name> \
  --container <app-or-order-query> \
  --namespace hyperscale \
  --duration 60
```

The command fails when the target is ambiguous, a required tool is missing, or
an expected artifact is empty. It writes raw artifacts only under the ignored
`build/phase18/jvm/` directory and generates a bounded summary.

Verify the complete local capture path, deadlock detection, retained-thread
detection, allocation histogram, and JFR parsing with:

```bash
make jvm-diagnostics-verify
```

### 3.2 Thread Dump Capture & Deadlock Detection
```bash
# Capture full thread stack dump with lock state inside an explicitly selected pod:
jcmd 1 Thread.print -l > /diagnostics/thread-dump.txt

# Or using jstack:
jstack -l 1 > /diagnostics/thread-dump.txt

# Inspect for deadlocks or thread pool saturation:
grep -E "State: |BLOCKED|WAITING|TIMED_WAITING" /tmp/thread-dump.txt | sort | uniq -c
```

### 3.3 Live Heap Inspection & Class Histogram
```bash
# Top 30 memory-consuming classes (fast, non-disruptive):
jcmd 1 GC.class_histogram | head -n 35

# On-demand heap dump:
jcmd 1 GC.heap_dump /diagnostics/manual-heapdump.hprof
```

---

## 4. Incident Troubleshooting Playbooks

### Playbook 1: Memory Leak or OutOfMemoryError (OOM)
- **Symptoms:** Container restarts with exit code 137 (OOMKilled by Linux cgroups) or `java.lang.OutOfMemoryError: Java heap space`.
- **Diagnosis:**
  1. Copy `/diagnostics/heapdump.hprof` from the restarted container before
     deleting the pod. The Kubernetes `emptyDir` survives container restart
     but not pod replacement.
  2. Open in Eclipse Memory Analyzer (MAT) or VisualVM.
  3. Calculate Dominator Tree and Leak Suspects Report.
- **Common HyperScale Signatures:**
  - Unbounded cache sizes in Caffeine L1 (verify `maximumSize` in `NearCache`).
  - Unclosed database result sets or large JPA/jOOQ batch fetches.

### Diagnostic data handling

- Treat JFR, heap dumps, system properties, and thread dumps as sensitive.
- Never commit raw captures or copy them to an external service without
  explicit approval.
- Redact credentials, authorization headers, tokens, customer identifiers,
  SQL literals, and payload fragments before committing a summary.
- Delete the pod-local artifact after the local copy is verified.

### Playbook 2: Database Connection Pool Starvation (HikariCP)
- **Symptoms:** Latency spikes to $5000\text{ms}$ followed by `SQLTransientConnectionException: Connection is not available, request timed out after 5000ms`.
- **Diagnosis:**
  1. Check metric `datasource.connections.active{pool="primary"}`.
  2. If active connections == 30 continuously, inspect slow queries via `pg_stat_activity`:
     ```sql
     SELECT pid, query_start, state, query FROM pg_stat_activity WHERE state != 'idle' ORDER BY query_start ASC;
     ```
- **Remediation:**
  - Verify queries use indexes on `sku`, `order_id`, and `created_at`.
  - Ensure transactions do not make slow network calls (e.g. synchronous Kafka or HTTP calls inside `@Transactional`).

### Playbook 3: Replication Lag Monitor Trip / Read Fallback
- **Symptoms:** Replica read traffic falls back to primary database; metric `postgres.replication.lag.seconds` exceeds $0.100\text{s}$.
- **Diagnosis:**
  1. Check replication lag on PostgreSQL:
     ```sql
     SELECT slot_name, active, pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) AS lag_bytes FROM pg_replication_slots;
     ```
  2. Verify network bandwidth between primary and standby replicas.
- **Remediation:**
  - System automatically fences replica and routes read-only queries to primary (`TransactionRoutingDataSource`).
  - When replication lag drops below $100\text{ms}$, replica reads automatically resume.

### Playbook 4: Dead Letter Queue (DLQ) Flood & Redrive
- **Symptoms:** Metric `events_dlq_total` increasing rapidly; message processing errors on `order-placed` topic.
- **Diagnosis:**
  1. Inspect DLQ messages on topic `order-placed-dlq`.
  2. Check `X-Dlq-Redrive-Count` and root exception in logs.
- **Remediation:**
  1. Fix consumer root cause or projection bug.
  2. Trigger redrive via DLQ Replay Admin API:
     ```bash
     curl -X POST "http://localhost:8081/admin/dlq/replay?dlqTopic=order-placed-dlq&targetTopic=order-placed&maxRecords=500"
     ```
  3. Messages exceeding `MAX_REDRIVES = 3` are permanently skipped and logged to prevent poison pills.
