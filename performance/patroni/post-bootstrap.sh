#!/usr/bin/env sh
set -eu

connection_string="$1"

psql "$connection_string" \
  --set=ON_ERROR_STOP=1 \
  --set=app_user="$POSTGRES_USER" \
  --set=app_password="$POSTGRES_PASSWORD" \
  --set=app_database="$POSTGRES_DB" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L SUPERUSER CREATEDB',
    :'app_user',
    :'app_password'
)
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'app_user')
\gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L SUPERUSER CREATEDB',
    :'app_user',
    :'app_password'
)
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'app_database', :'app_user')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_database WHERE datname = :'app_database')
\gexec
SQL
