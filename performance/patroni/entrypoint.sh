#!/usr/bin/env sh
set -e

NODE_NAME="${NODE_NAME:-postgres-1}"
ETCD_HOSTS="${ETCD_HOSTS:-etcd-1:2379,etcd-2:2379,etcd-3:2379}"
POSTGRES_USER="${POSTGRES_USER:-hyperscale}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-hyperscale}"
POSTGRES_DB="${POSTGRES_DB:-hyperscale}"
REPLICATOR_PASSWORD="${REPLICATOR_PASSWORD:-replicator_pass}"
PATRONI_DATA_DIR="${PATRONI_DATA_DIR:-/var/lib/postgresql/data}"
PATRONI_CONNECT_HOST="${PATRONI_CONNECT_HOST:-${NODE_NAME}}"

mkdir -p "${PATRONI_DATA_DIR}"
chmod 700 "${PATRONI_DATA_DIR}"

cat <<EOF > /var/lib/postgresql/patroni.yml
scope: hyperscale-postgres-cluster
namespace: /service
name: ${NODE_NAME}

restapi:
  listen: 0.0.0.0:8008
  connect_address: ${PATRONI_CONNECT_HOST}:8008

etcd3:
  hosts: ${ETCD_HOSTS}

bootstrap:
  dcs:
    ttl: 30
    loop_wait: 10
    retry_timeout: 10
    maximum_lag_on_failover: 1048576
    synchronous_mode: true
    synchronous_mode_strict: true
    synchronous_node_count: 1
    postgresql:
      use_pg_rewind: true
      use_slots: true
      parameters:
        max_connections: 300
        wal_level: replica
        max_wal_senders: 10
        max_replication_slots: 10
        hot_standby: "on"
        synchronous_commit: "on"
        archive_mode: "on"
        archive_command: "/bin/true"
  initdb:
    - encoding: UTF8
    - data-checksums

  post_bootstrap: /post-bootstrap.sh

  pg_hba:
    - host all all 0.0.0.0/0 md5
    - host replication replicator 0.0.0.0/0 md5
    - host all all all trust
postgresql:
  listen: 0.0.0.0:5432
  connect_address: ${PATRONI_CONNECT_HOST}:5432
  data_dir: ${PATRONI_DATA_DIR}
  bin_dir: /usr/local/bin
  pgpass: /tmp/pgpass
  authentication:
    replication:
      username: replicator
      password: ${REPLICATOR_PASSWORD}
    superuser:
      username: postgres
      password: ${POSTGRES_PASSWORD}
  parameters:
    max_connections: 300
    wal_level: replica
    max_wal_senders: 10
    max_replication_slots: 10
    hot_standby: "on"
    synchronous_commit: "on"

tags:
  nofailover: false
  noloadbalance: false
  clonefrom: false
  nosync: false
EOF

chmod 600 /var/lib/postgresql/patroni.yml

exec patroni /var/lib/postgresql/patroni.yml
