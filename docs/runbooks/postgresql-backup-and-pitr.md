# Runbook: PostgreSQL Backup, WAL Archiving & Point-In-Time Recovery (PITR)

## Overview

In Phase 15 (ADR-0024, P15-05), PostgreSQL backup and disaster recovery operate on an independent recovery plane using continuous WAL streaming, physical basebackups, and point-in-time recovery.

---

## 1. Operational Backup Procedures

### Trigger a Full Physical Backup
```bash
make ha-db-backup
```
*Creates a compressed, checksummed physical archive in `build/backups/pgbackrest/` streamed directly from the active primary.*

### Creating a Named Restore Point
Before high-risk operations (e.g. major data migration or batch purge):
```bash
docker exec hyperscale-postgres-1 psql -U postgres -d hyperscale -c "
  SELECT pg_create_restore_point('pre_migration_backup_point');
"
```

---

## 2. Point-In-Time Recovery (PITR) Procedure

To recover the database to a specific named restore point or timestamp in an isolated environment without affecting the active cluster:

1. **Extract Basebackup:**
   ```bash
   tar -xzf build/backups/pgbackrest/hyperscale_full_<timestamp>.tar.gz -C /var/lib/postgresql/isolated_restore_data
   ```
2. **Configure Recovery Target:**
   Create `/var/lib/postgresql/isolated_restore_data/recovery.signal` and add to `postgresql.auto.conf`:
   ```properties
   restore_command = 'cp /var/lib/postgresql/wal_archive/%f %p'
   recovery_target_name = 'pre_migration_backup_point'
   recovery_target_action = 'promote'
   ```
3. **Start Isolated Instance & Verify:**
   Start PostgreSQL pointing to the isolated data directory and query the sentinel tables to verify pre-point records exist and corrupted post-point operations are omitted.

---

## 3. Automated Disaster Recovery Verification

```bash
# Run automated PITR test with pre/post sentinel transaction verification
make ha-db-pitr-test
```
