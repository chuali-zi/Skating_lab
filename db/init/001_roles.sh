#!/usr/bin/env bash
set -euo pipefail
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 --set=app_password="$APP_DB_PASSWORD" \
  --set=migration_password="$MIGRATION_DB_PASSWORD" <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE ROLE skating_migrator LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'migration_password';
CREATE ROLE skating_app LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'app_password';
GRANT USAGE, CREATE ON SCHEMA public TO skating_migrator;
GRANT USAGE ON SCHEMA public TO skating_app;
ALTER DEFAULT PRIVILEGES FOR ROLE skating_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO skating_app;
ALTER DEFAULT PRIVILEGES FOR ROLE skating_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO skating_app;
SQL
