#!/usr/bin/env bash
set -euo pipefail

echo "== DB BACKUP (STEP 1: data-only, whitelist tables) =="

# ---- Configuration (from env) ----
: "${DB_SOURCE_ACCESS:?DB_SOURCE_ACCESS missing (ssm|direct)}"
: "${DB_SOURCE_NAME:?DB_SOURCE_NAME missing}"
: "${DB_SOURCE_USER:?DB_SOURCE_USER missing}"
: "${DB_SOURCE_PASSWORD:?DB_SOURCE_PASSWORD missing}"

PGHOST=""
PGPORT=""

case "$DB_SOURCE_ACCESS" in
  ssm)
    : "${DB_SOURCE_LOCAL_PORT:?DB_SOURCE_LOCAL_PORT missing (when DB_SOURCE_ACCESS=ssm)}"
    PGHOST="127.0.0.1"
    PGPORT="$DB_SOURCE_LOCAL_PORT"
    ;;
  direct)
    : "${DB_SOURCE_RDS_HOST:?DB_SOURCE_RDS_HOST missing (when DB_SOURCE_ACCESS=direct)}"
    : "${DB_SOURCE_PORT:?DB_SOURCE_PORT missing (when DB_SOURCE_ACCESS=direct)}"
    PGHOST="$DB_SOURCE_RDS_HOST"
    PGPORT="$DB_SOURCE_PORT"
    ;;
  *)
    echo "Invalid DB_SOURCE_ACCESS='$DB_SOURCE_ACCESS' (use: ssm|direct)"
    exit 1
    ;;
esac

# ---- Derive environment label from hostname (after host is known) ----
ENV_LABEL="unknown"

host_to_env() {
  local h
  h="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  if [[ "$h" == *prod* ]]; then
    echo "prod"
  elif [[ "$h" == *test* ]]; then
    echo "test"
  elif [[ "$h" == *dev* ]]; then
    echo "dev"
  else
    echo "unknown"
  fi
}

# If using SSM tunnel, prefer the real RDS hostname for env detection if provided.
ENV_HOST="${DB_SOURCE_RDS_HOST:-$PGHOST}"
ENV_LABEL="$(host_to_env "$ENV_HOST")"

PGDATABASE="$DB_SOURCE_NAME"
PGUSER="$DB_SOURCE_USER"
PGPASSWORD="$DB_SOURCE_PASSWORD"

SCHEMA="public"
OUTDIR=".db"

# Make TABLES_FILE relative to this script (not to current working dir)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TABLES_FILE="${TABLES_FILE:-$SCRIPT_DIR/tables.txt}"

TS="$(date -u +"%Y%m%dT%H%M%SZ")"

# Include env label in filename
DUMPFILE="$OUTDIR/backup_${ENV_LABEL}_${PGDATABASE}_${SCHEMA}_${TS}.dump"
SEQFILE="${DUMPFILE%.dump}.sequences.sql"

# Prefer PG tools if installed. Fallback to PATH if not found.
PSQL_BIN="${PSQL_BIN:-/usr/lib/postgresql/13/bin/psql}"
PG_DUMP_BIN="${PG_DUMP_BIN:-/usr/lib/postgresql/13/bin/pg_dump}"
command -v "$PSQL_BIN" >/dev/null 2>&1 || PSQL_BIN="psql"
command -v "$PG_DUMP_BIN" >/dev/null 2>&1 || PG_DUMP_BIN="pg_dump"

# ---- Whitelisted tables (EXPLICIT) ----
if [[ ! -f "$TABLES_FILE" ]]; then
  echo "Missing TABLES_FILE: $TABLES_FILE"
  echo "Create it (one table per line) or export TABLES_FILE=/path/to/file"
  exit 1
fi

TABLES=()
while IFS= read -r line; do
  TABLES+=("$line")
done < <(grep -vE '^\s*(#|$)' "$TABLES_FILE" | tr -d '\r')

if [[ "${#TABLES[@]}" -eq 0 ]]; then
  echo "No tables found in TABLES_FILE: $TABLES_FILE"
  exit 1
fi

mkdir -p "$OUTDIR"

echo "Source:"
echo "  env    = $ENV_LABEL"
echo "  env_host = $ENV_HOST"
echo "  access = $DB_SOURCE_ACCESS"
if [[ -n "${DB_SOURCE_RDS_HOST:-}" ]]; then
  echo "  rds_host= $DB_SOURCE_RDS_HOST"
fi
echo "  db     = $PGDATABASE"
echo "  host   = $PGHOST:$PGPORT"
echo "  user   = $PGUSER"
echo "  schema = $SCHEMA"
echo "  tables = ${#TABLES[@]}"
echo "  tables_file = $TABLES_FILE"
echo "Tools:"
echo "  psql   = $($PSQL_BIN --version)"
echo "  pg_dump= $($PG_DUMP_BIN --version)"
echo "Output:"
echo "  file   = $DUMPFILE"
echo "  seqfile= $SEQFILE"
echo

psql_src() {
  PGPASSWORD="$PGPASSWORD" "$PSQL_BIN" -P pager=off \
    -h "$PGHOST" \
    -p "$PGPORT" \
    -U "$PGUSER" \
    -d "$PGDATABASE" \
    -v ON_ERROR_STOP=1 \
    "$@"
}

echo "== DB identity (source) =="
psql_src -c "
  SELECT
    current_database() AS database,
    current_user       AS user,
    inet_server_addr() AS server_ip,
    inet_server_port() AS server_port,
    now()              AS connected_at;
"
echo

# ---- Build pg_dump --table args ----
TABLE_ARGS=()
for t in "${TABLES[@]}"; do
  TABLE_ARGS+=( "--table=${SCHEMA}.${t}" )
done

echo "== Running pg_dump =="
PGPASSWORD="$PGPASSWORD" \
"$PG_DUMP_BIN" \
  -h "$PGHOST" \
  -p "$PGPORT" \
  -U "$PGUSER" \
  -d "$PGDATABASE" \
  --format=custom \
  --data-only \
  --no-owner \
  --no-privileges \
  "${TABLE_ARGS[@]}" \
  --file="$DUMPFILE"

echo
echo "✅ Backup complete"
echo "Dump file:"
echo "  $DUMPFILE"
echo

echo "== Export source sequence values =="
{
  echo "-- Source sequence values snapshot"
  echo "-- source_host=${ENV_HOST} db=${PGDATABASE} captured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  psql_src -tA -c "
    SELECT format(
      'SELECT setval(%L, %s, true);',
      format('%I.%I', schemaname, sequencename),
      COALESCE(last_value::bigint, 1)
    )
    FROM pg_sequences
    WHERE schemaname = '${SCHEMA}'
      AND sequencename <> 'db_restore_log_id_seq'
    ORDER BY schemaname, sequencename;
  "
} > "$SEQFILE"

SEQ_LINES="$(grep -c '^SELECT setval(' "$SEQFILE" || true)"
echo "  sequence statements = $SEQ_LINES"
echo "  sequence file       = $SEQFILE"
echo

echo "== Verify: dump contains TABLE DATA entries =="
PG_RESTORE_BIN="${PG_RESTORE_BIN:-/usr/lib/postgresql/13/bin/pg_restore}"
command -v "$PG_RESTORE_BIN" >/dev/null 2>&1 || PG_RESTORE_BIN="pg_restore"
"$PG_RESTORE_BIN" -l "$DUMPFILE" | grep 'TABLE DATA' | head -20 || true
echo

echo "== Verify: row counts (source) =="
COUNT_SQL="SELECT 'public.calibration_point' AS tbl, count(*)::bigint AS rows FROM public.calibration_point"
for t in "${TABLES[@]:1}"; do
  COUNT_SQL="${COUNT_SQL} UNION ALL SELECT 'public.${t}', count(*)::bigint FROM public.${t}"
done
COUNT_SQL="${COUNT_SQL} ORDER BY tbl;"
psql_src -c "$COUNT_SQL"
echo

echo "== Verify: Flyway schema history (source) =="
psql_src -c "
  SELECT version, description, installed_on
  FROM ${SCHEMA}.flyway_schema_history
  WHERE success
  ORDER BY installed_rank DESC
  LIMIT 1;
" || echo "NOTE: flyway_schema_history not found in ${SCHEMA} (ok if Flyway not used here)."
echo

psql_src -c "
  SELECT installed_rank, version, description, type, script, installed_on, success
  FROM ${SCHEMA}.flyway_schema_history
  ORDER BY installed_rank DESC
  LIMIT 10;
" || true
echo

echo "Dump size:"
ls -lh "$DUMPFILE"
echo
