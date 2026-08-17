# ADR-0019: Event Schema Evolution and Versioning Strategy

## Status

Accepted

## Context

In an event-driven distributed system, domain event contracts published to Kafka evolve over time as new business capabilities are introduced. Deploying new versions of producers and consumers asynchronously requires formal schema compatibility guarantees to prevent deserialization crashes and head-of-line blocking.

---

## Decision

We adopt a **Backward-Compatible, Additive-Only Schema Evolution Strategy**:

1. **Compatibility Guarantee:**
   - All event schemas must maintain **Full Compatibility** (Backward and Forward).
   - Consumers must configure deserializers to ignore unknown fields (`@JsonIgnoreProperties(ignoreUnknown = true)`).
2. **Schema Evolution Rules:**
   - **Additive Only:** New fields added to event payloads must be optional (nullable or have default values).
   - **No Field Renames or Removals:** Fields cannot be renamed or removed from existing event schema versions.
3. **Deprecation Lifecycle (Dual-Write / Dual-Read):**
   - When refactoring fields, producers write both legacy and new fields during a deprecation window. Consumers transition to reading the new field with fallback to legacy.
4. **Major Versioning:**
   - Structural breaking changes require a new event type or version increment (`version: 2`) with a dedicated consumer handler.

---

## Consequences

### Positive
- Independent, zero-downtime rolling deployments of producers and consumers.
- Zero poison message generation caused by unexpected new JSON fields.

### Negative / Tradeoffs
- Requires disciplined schema governance across teams.
- Event payloads may carry deprecated fields temporarily during transition windows.
