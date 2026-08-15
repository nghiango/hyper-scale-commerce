# ADR-0016: Production Hardening, Security, Lifecycle Management, and Operational Alerting Strategy

## Status

Accepted

## Context

In Phases 1 through 9, HyperScale Commerce was designed, built, and empirically verified as an event-driven, CQRS distributed system with transactional outbox publishing and schema-isolated data models. 

Phase 9 proved that the platform gracefully tolerates network partitions, broker outages, database degradations, poison messages, and process terminations. However, operating this distributed platform safely in enterprise production requires addressing operational concerns that fall outside pure functional and chaos logic:

1. **Process Termination & In-Flight Request Drops:** Abrupt container terminations (`SIGTERM`) without graceful drain can terminate in-flight HTTP requests and leave open database transactions in an uncoordinated state, resulting in 502/504 errors during rolling releases.
2. **Actuator Attack Surface & Information Disclosure:** Unrestricted Spring Boot Actuator endpoints (e.g. `/actuator/env`, `/actuator/beans`, `/actuator/heapdump`) expose environment variables, database credentials, and internal JVM architecture.
3. **Database Connection Pool Exhaustion:** Production connection pools need strict configuration for maximum lifetime, acquisition timeouts, and leak detection to prevent silent pool starvation.
4. **Actionable Operational Alerting:** While Prometheus metrics and OpenTelemetry traces are exported, production operations require standardized alerting rules mapping directly to constitutional SLIs/SLOs.
5. **Operational Runbooks for Recovery:** Operators need verified, step-by-step playbooks for DLQ event triage, outbox backlog draining, consumer group lag remediation, and cross-schema data audit.

---

## Decision

We adopt a comprehensive Production Hardening Strategy across `app` and `order-query` with the following pillars:

### 1. Graceful Lifecycle Management & Shutdown
- Configure Spring Boot graceful shutdown (`server.shutdown: graceful`) with a 30-second drain timeout (`spring.lifecycle.timeout-per-shutdown-phase: 30s`).
- During `SIGTERM`, Tomcat stops accepting new connections, drains all active in-flight requests, allows background outbox publish loops to finish current batches, and cleanly closes HikariCP connection pools.

### 2. Actuator Security & Granular Probes
- Restrict web exposure to only operational endpoints:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health, info, prometheus
    endpoint:
      health:
        show-details: when_authorized
        probes:
          enabled: true
        group:
          liveness:
            include: livenessState
          readiness:
            include: readinessState, db, diskSpace
  ```
- Sensitive endpoints (`/actuator/env`, `/actuator/beans`, `/actuator/configprops`, `/actuator/heapdump`) are disabled and return 404.

### 3. Database Connection Pool Hardening
- Standardize HikariCP production parameters:
  - `connection-timeout: 5000` (Fail fast after 5s)
  - `max-lifetime: 1800000` (30 minutes to prevent stale socket accumulation)
  - `idle-timeout: 600000` (10 minutes)
  - `leak-detection-threshold: 5000` (Log warning if connection held $>5\text{s}$)
  - `maximum-pool-size: 50`

### 4. Production SLI/SLO Alerting Rules
- Define formal Prometheus alerting rules (`performance/monitoring/alerts.yml`) covering:
  - **Critical API Latency Breach:** `http_req_duration_p95_seconds > 0.2` for $> 1\text{m}$.
  - **HTTP 5xx Error Spike:** Rate of 5xx responses $> 0.1\%$ over 5m.
  - **Outbox Relay Backlog:** Oldest unpublished event age $> 5.0\text{s}$ for $> 2\text{m}$.
  - **Kafka Consumer Lag:** Lag records $> 100$ for $> 2\text{m}$.
  - **Dead-Letter Queue Spillage:** `events_dlq_total > 0` instant alert.
  - **HikariCP Connection Starvation:** Pool acquisition timeouts $> 0$.

### 5. Standardized Operational Disaster Recovery Runbooks
- Author step-by-step operational runbooks under `docs/runbooks/`:
  - `dlq-triage-and-replay.md`: Classifying non-retryable vs transient DLQ messages, inspecting failure headers, fixing root causes, and replaying to `order-placed`.
  - `outbox-backlog-recovery.md`: Diagnosing outbox lock contention, tuning batch sizes, and clearing stalled published events.
  - `database-pool-exhaustion.md`: Diagnosing long-running transactions, connection leaks, and adjusting pool parameters.
  - `cross-schema-data-reconciliation.md`: Executing automated audit queries across `order`, `inventory`, and `order_read_model`.

---

## Alternatives Considered

1. **Immediate Kubernetes / Istio Service Mesh Migration:**
   - *Rejected:* Violates BootCamp precedence rules. Container-level hardening, graceful shutdown, and standardized alert rules must be verified first on the core runtime before cloud orchestration abstractions are introduced.
2. **Unmanaged Shutdown with External Load Balancer Healthchecks Only:**
   - *Rejected:* Without in-process graceful drain (`server.shutdown=graceful`), abrupt SIGKILLs drop in-flight requests that were already accepted by the TCP socket.
3. **Open Actuator with Reverse Proxy Filtering:**
   - *Rejected:* Defense-in-depth requires the application runtime itself to restrict exposed endpoints, preventing accidental exposure in internal VPCs or misconfigured gateways.

---

## Consequences

### Positive
- Zero 502/504 errors during rolling container updates or process restarts.
- Strict defense-in-depth protection against configuration and environment disclosure.
- Actionable operational alerts mapped directly to constitutional SLOs.
- Clear, tested playbooks for production on-call engineers.

### Negative / Tradeoffs
- Graceful shutdown introduces a bounded shutdown latency (up to 30s during active traffic).
- Leak detection logging adds minimal overhead during connection acquisition/release.

---

## Operational & Verification Impact

- Verified via graceful shutdown drain tests, actuator security audits, Prometheus rule linting, and the final 10,000 concurrent user qualification test.
