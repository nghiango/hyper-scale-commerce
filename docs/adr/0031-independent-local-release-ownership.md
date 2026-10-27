# ADR-0031: Independent local application and infrastructure delivery

Status: Proposed
Date: 2026-10-27
Task: LOCAL-SPLIT-01

## Context and authorization

Owner requested separating application services from their infrastructure.
Previously one Argo Application owned all runtime resources and every pipeline
release also rebuilt/retagged Patroni. This local exception does not advance the
phase. Implementation is owner-authorized; formal architectural review pending.

## Alternatives

Keep one chart/Application: simplest, but couples stateful lifecycle to business
releases. Gate enabled flags in two copies: risks overlaps/shared ownership.
Maintain manually forked charts: duplicates templates. Generate disjoint chart
partitions from the existing baseline: maintains one template source while
separating live ownership and release scopes. Separate repositories are not
needed for this local lifecycle boundary.

## Decision

Generate application (app/order-query/HPA), infrastructure (all other commerce
resources including shared namespace, ServiceAccount/network policies, ingress,
backup/storage), and existing platform charts. Keep hyperscale-local as the
application Argo name to minimize renaming risk. Add infrastructure Application;
platform retains its name. Default release scope is application and must not
modify infrastructure/platform files. Explicit infrastructure scope rebuilds
Patroni and updates its chart/values. Platform scope changes observability only;
bootstrap initializes all scopes. Baseline performance Helm use is unchanged.

## Migration and failure modes

Pause commerce auto-sync, stage disjoint charts with identical rendered resources,
change its source to application, then let infrastructure adopt its existing
resources. Pruning stays disabled; never cascade-delete the old resources/PVCs.
Compare exact pod specs and PVC UIDs. Shared infrastructure must be available
before applications; separate Argo apps do not create cross-app health ordering.
A normal deployment must not be treated as infrastructure bootstrap.

An old Jenkins definition still writes the legacy commerce directory, which new
Applications no longer watch. Publish source changes to its deployment branch
before expecting automatic application releases with the new layout. Do not
silently overwrite the owner's java branch or force-push main.

## Verification and rollback

Verify resource identity sets and semantic render equality before migration,
application-only release leaves infrastructure/platform Git trees unchanged,
Argo owner annotations, running pod UIDs and PVC UIDs. Rollback requires pausing
both owners and transferring resource ownership back before restoring the old
commerce path. Reverting Git alone is insufficient for a safe ownership rollback.
Never enable pruning during transfer. Retain legacy charts temporarily as a
migration/rollback snapshot. Evidence is LOCAL-SPLIT-01-verification.md.
