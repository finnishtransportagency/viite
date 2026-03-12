#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  db_restore.sh --file <dumpfile>
  db_restore.sh --latest

Required env:
  DB_TARGET_ACCESS=ssm|direct
  DB_TARGET_NAME DB_TARGET_USER DB_TARGET_PASSWORD
  CONFIRM_RESTORE=yes

If DB_TARGET_ACCESS=ssm:
  DB_TARGET_LOCAL_PORT
  (DB_TARGET_RDS_HOST optional; for logging/anti-prod gate)

If DB_TARGET_ACCESS=direct:
  DB_TARGET_RDS_HOST
  DB_TARGET_PORT

Optional:
  DB_LOG_DIR=.db/logs
  DB_SOURCE_LABEL (else DB_HOST, else 'unknown')
  DB_EXPECT_FLYWAY_VERSION=<version>   (optional gate)
  DB_TRUNCATE_CASCADE=1|0              (default 1)
  DB_VERBOSE=1                         (extra diagnostics)

Whitelist tables:
  tables.txt must exist in the same directory as this script.
  One table name per line (public schema). Lines starting with # are ignored.
EOF
}

die(){ echo "ERROR: $*" >&2; exit 1; }

# -------------------------
# Args
# -------------------------
DUMPFILE=""
[[ $# -gt 0 ]] || { usage; exit 2; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)   DUMPFILE="${2:-}"; shift 2 ;;
    --latest) DUMPFILE="__LATEST__"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown arg: $1" ;;
  esac
done

if [[ "$DUMPFILE" == "__LATEST__" ]]; then
  DUMPFILE="$(ls -1t .db/*.dump 2>/dev/null | head -1 || true)"
fi
[[ -n "$DUMPFILE" ]] || die "No dump file specified/found."
[[ -f "$DUMPFILE" ]] || die "Dump file not found: $DUMPFILE"
SEQFILE="${DUMPFILE%.dump}.sequences.sql"
SEQFILE_EXISTS=0
[[ -s "$SEQFILE" ]] && SEQFILE_EXISTS=1
[[ "$SEQFILE_EXISTS" -eq 1 ]] || die "Missing sequence sidecar file: $SEQFILE (run backup with current db_backup.sh)"

# Parse sidecar file once to a VALUES list for readable apply output.
SEQ_VALUES_SQL="$({
  awk -F"'" '
    /^SELECT[[:space:]]+setval\(/ {
      seq_name = $2
      if (seq_name == "public.db_restore_log_id_seq") next
      if (match($0, /,[[:space:]]*([0-9]+)[[:space:]]*,[[:space:]]*true\)[[:space:]]*;/, m)) {
        printf "(\047%s\047, %s),\n", seq_name, m[1]
      }
    }
  ' "$SEQFILE"
} )"
SEQ_VALUES_SQL="${SEQ_VALUES_SQL%,}"
[[ -n "$SEQ_VALUES_SQL" ]] || die "No applicable sequence values found in sidecar: $SEQFILE"

# -------------------------
# Env + safety gates
# -------------------------
: "${DB_TARGET_ACCESS:?missing DB_TARGET_ACCESS (ssm|direct)}"
: "${DB_TARGET_NAME:?missing DB_TARGET_NAME}"
: "${DB_TARGET_USER:?missing DB_TARGET_USER}"
: "${DB_TARGET_PASSWORD:?missing DB_TARGET_PASSWORD}"
: "${CONFIRM_RESTORE:?Set CONFIRM_RESTORE=yes to allow restore}"
[[ "$CONFIRM_RESTORE" == "yes" ]] || die "Refusing: CONFIRM_RESTORE must be yes"

SCHEMA="public"
DB_TRUNCATE_CASCADE="${DB_TRUNCATE_CASCADE:-1}"
DB_VERBOSE="${DB_VERBOSE:-0}"

# Tools
PSQL_BIN="${PSQL_BIN:-/usr/lib/postgresql/13/bin/psql}"
PG_RESTORE_BIN="${PG_RESTORE_BIN:-/usr/lib/postgresql/13/bin/pg_restore}"
command -v "$PSQL_BIN" >/dev/null 2>&1 || PSQL_BIN="psql"
command -v "$PG_RESTORE_BIN" >/dev/null 2>&1 || PG_RESTORE_BIN="pg_restore"

# Script dir + tables.txt
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TABLES_FILE="${TABLES_FILE:-$SCRIPT_DIR/tables.txt}"
[[ -f "$TABLES_FILE" ]] || die "Missing tables file: $TABLES_FILE"

# Read tables (ignore empty + comments)
mapfile -t TABLES < <(grep -vE '^\s*($|#)' "$TABLES_FILE" | sed 's/^\s*//;s/\s*$//')
[[ "${#TABLES[@]}" -gt 0 ]] || die "No tables found in $TABLES_FILE"

# Logging
DB_LOG_DIR="${DB_LOG_DIR:-.db/logs}"
mkdir -p "$DB_LOG_DIR"
TS="$(date +%Y%m%d_%H%M%S)"
LOGFILE="${DB_LOG_DIR}/db_restore_${DB_TARGET_NAME}_${TS}_$(basename "$DUMPFILE").log"
exec > >(tee -a "$LOGFILE") 2>&1

on_err(){
  echo
  echo "❌ Restore failed. Log: $LOGFILE"
}
trap on_err ERR

# -------------------------
# Target connection resolve
# -------------------------
TARGET_HOST=""
TARGET_PORT=""
case "$DB_TARGET_ACCESS" in
  ssm)
    : "${DB_TARGET_LOCAL_PORT:?missing DB_TARGET_LOCAL_PORT (ssm mode)}"
    TARGET_HOST="127.0.0.1"
    TARGET_PORT="$DB_TARGET_LOCAL_PORT"
    ;;
  direct)
    : "${DB_TARGET_RDS_HOST:?missing DB_TARGET_RDS_HOST (direct mode)}"
    : "${DB_TARGET_PORT:?missing DB_TARGET_PORT (direct mode)}"
    TARGET_HOST="$DB_TARGET_RDS_HOST"
    TARGET_PORT="$DB_TARGET_PORT"
    ;;
  *) die "Invalid DB_TARGET_ACCESS='$DB_TARGET_ACCESS' (use ssm|direct)" ;;
esac

# -------------------------
# Anti-prod gate: refuse if host contains "prod"/"production" (case-insensitive)
# Works in both modes:
# - direct: DB_TARGET_RDS_HOST is required
# - ssm: DB_TARGET_RDS_HOST optional; if set, gate applies
# -------------------------
if [[ -n "${DB_TARGET_RDS_HOST:-}" ]]; then
  _host_lc="$(printf '%s' "$DB_TARGET_RDS_HOST" | tr '[:upper:]' '[:lower:]')"
  if [[ "$_host_lc" == *prod* ]] || [[ "$_host_lc" == *production* ]]; then
    die "Refusing: DB_TARGET_RDS_HOST looks like production: '$DB_TARGET_RDS_HOST'"
  fi
fi

SOURCE_LABEL="${DB_SOURCE_LABEL:-${DB_HOST:-unknown}}"

psql_tgt() {
  PGPASSWORD="$DB_TARGET_PASSWORD" "$PSQL_BIN" -X -P pager=off \
    -h "$TARGET_HOST" -p "$TARGET_PORT" \
    -U "$DB_TARGET_USER" -d "$DB_TARGET_NAME" \
    -v ON_ERROR_STOP=1 "$@"
}

echo "== DB RESTORE =="
echo "  log     = $LOGFILE"
echo "  tables  = $TABLES_FILE (${#TABLES[@]} tables)"
echo "  target  = $DB_TARGET_NAME @ $TARGET_HOST:$TARGET_PORT (user=$DB_TARGET_USER)"
echo "  access  = $DB_TARGET_ACCESS"
echo "  dump    = $DUMPFILE"
echo "  seqfile = $SEQFILE"
echo "  source  = $SOURCE_LABEL"
echo "  tools   = $($PSQL_BIN --version) | $($PG_RESTORE_BIN --version)"
echo

# Build helpers
TABLE_NAMES_SQL="$(printf "'%s'," "${TABLES[@]}")"; TABLE_NAMES_SQL="${TABLE_NAMES_SQL%,}"
TRUNC_LIST="$(printf "${SCHEMA}.%s," "${TABLES[@]}")"; TRUNC_LIST="${TRUNC_LIST%,}"

# -------------------------
# Optional Flyway gate
# -------------------------
if [[ -n "${DB_EXPECT_FLYWAY_VERSION:-}" ]]; then
  echo "== Preflight: Flyway gate (expect ${DB_EXPECT_FLYWAY_VERSION}) =="
  latest="$(psql_tgt -tA -c "
    SELECT version
    FROM ${SCHEMA}.flyway_schema_history
    WHERE success
    ORDER BY installed_rank DESC
    LIMIT 1;
  " 2>/dev/null || true)"
  [[ -n "$latest" ]] || die "Refusing: cannot read ${SCHEMA}.flyway_schema_history"
  [[ "$latest" == "$DB_EXPECT_FLYWAY_VERSION" ]] || die "Refusing: Flyway mismatch (expected=$DB_EXPECT_FLYWAY_VERSION actual=$latest)"
  echo "  OK"
  echo
fi

# -------------------------
# Safety: dump must contain only whitelisted TABLE DATA entries
# -------------------------
echo "== Safety: dump TABLE DATA whitelist =="
DUMP_TABLES="$("$PG_RESTORE_BIN" -l "$DUMPFILE" \
  | awk -F';' '
      /TABLE DATA/ {
        line=$2
        gsub(/^[ \t]+/, "", line)
        n=split(line, a, /[ \t]+/)
        for (i=1; i<=n; i++) {
          if (a[i]=="TABLE" && a[i+1]=="DATA") {
            if (i+3 <= n) print a[i+2] "." a[i+3]
          }
        }
      }
    ' | sort -u || true)"

if [[ -z "$DUMP_TABLES" ]]; then
  echo "  ⚠️  Could not detect TABLE DATA entries (continuing)."
else
  for dt in $DUMP_TABLES; do
    schema="${dt%%.*}"; table="${dt##*.}"
    [[ "$schema" == "$SCHEMA" ]] || die "Refusing: dump contains non-${SCHEMA} schema table: $dt"
    ok=0
    for t in "${TABLES[@]}"; do [[ "$table" == "$t" ]] && ok=1 && break; done
    [[ "$ok" -eq 1 ]] || die "Refusing: dump contains non-whitelisted table: $dt"
  done
  echo "  OK"
fi
echo

# -------------------------
# Snapshot FK drop/create/validate touching whitelist
# -------------------------
echo "== Snapshot FK constraints touching whitelist =="
FK_DROP_SQL="$(psql_tgt -tA -c "
  SELECT format('ALTER TABLE %I.%I DROP CONSTRAINT %I;', n_src.nspname, c_src.relname, con.conname)
  FROM pg_constraint con
  JOIN pg_class c_src     ON c_src.oid = con.conrelid
  JOIN pg_namespace n_src ON n_src.oid = c_src.relnamespace
  JOIN pg_class c_ref     ON c_ref.oid = con.confrelid
  JOIN pg_namespace n_ref ON n_ref.oid = c_ref.relnamespace
  WHERE con.contype='f'
    AND (
      (n_src.nspname='${SCHEMA}' AND c_src.relname IN (${TABLE_NAMES_SQL}))
      OR
      (n_ref.nspname='${SCHEMA}' AND c_ref.relname IN (${TABLE_NAMES_SQL}))
    )
  ORDER BY 1;
")"

FK_CREATE_SQL="$(psql_tgt -tA -c "
  SELECT format('ALTER TABLE %I.%I ADD CONSTRAINT %I %s NOT VALID;',
    n_src.nspname, c_src.relname, con.conname, pg_get_constraintdef(con.oid))
  FROM pg_constraint con
  JOIN pg_class c_src     ON c_src.oid = con.conrelid
  JOIN pg_namespace n_src ON n_src.oid = c_src.relnamespace
  JOIN pg_class c_ref     ON c_ref.oid = con.confrelid
  JOIN pg_namespace n_ref ON n_ref.oid = c_ref.relnamespace
  WHERE con.contype='f'
    AND (
      (n_src.nspname='${SCHEMA}' AND c_src.relname IN (${TABLE_NAMES_SQL}))
      OR
      (n_ref.nspname='${SCHEMA}' AND c_ref.relname IN (${TABLE_NAMES_SQL}))
    )
  ORDER BY 1;
")"

FK_VALIDATE_SQL="$(psql_tgt -tA -c "
  SELECT format('ALTER TABLE %I.%I VALIDATE CONSTRAINT %I;', n_src.nspname, c_src.relname, con.conname)
  FROM pg_constraint con
  JOIN pg_class c_src     ON c_src.oid = con.conrelid
  JOIN pg_namespace n_src ON n_src.oid = c_src.relnamespace
  JOIN pg_class c_ref     ON c_ref.oid = con.confrelid
  JOIN pg_namespace n_ref ON n_ref.oid = c_ref.relnamespace
  WHERE con.contype='f'
    AND (
      (n_src.nspname='${SCHEMA}' AND c_src.relname IN (${TABLE_NAMES_SQL}))
      OR
      (n_ref.nspname='${SCHEMA}' AND c_ref.relname IN (${TABLE_NAMES_SQL}))
    )
  ORDER BY 1;
")"

FK_COUNT="$(printf "%s\n" "$FK_DROP_SQL" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
echo "  FK count = $FK_COUNT"
echo

# -------------------------
# Restore (single transaction, single psql connection)
# -------------------------
echo "== Restore: single transaction =="
TRUNC_SQL="TRUNCATE TABLE $TRUNC_LIST RESTART IDENTITY"
[[ "$DB_TRUNCATE_CASCADE" == "1" ]] && TRUNC_SQL="$TRUNC_SQL CASCADE"
TRUNC_SQL="$TRUNC_SQL;"

export PGAPPNAME="db_restore_stream"

{
  echo "BEGIN;"

  if [[ "$FK_COUNT" -gt 0 ]]; then
    echo "\echo 'Drop FKs...'"
    printf "%s\n" "$FK_DROP_SQL"
  fi

  echo "\echo 'Truncate whitelist...'"
  echo "$TRUNC_SQL"

  echo "\echo 'pg_restore data-only...'"
  "$PG_RESTORE_BIN" --verbose --data-only --no-owner --no-privileges -f - "$DUMPFILE"

  # pg_restore may set search_path; restore it for subsequent DDL
  echo "SET search_path = ${SCHEMA}, \"\$user\";"

  echo "\echo 'Apply source sequence values from sidecar...'"
  cat <<SQL
WITH seqs(seq_name, seq_value) AS (
  VALUES
  $SEQ_VALUES_SQL
),
applied AS (
  SELECT seq_name, setval(seq_name::regclass, seq_value, true) AS applied_value
  FROM seqs
)
SELECT
  row_number() OVER (ORDER BY seq_name) || '. ' || seq_name || ': ' || applied_value AS sequence_value
FROM applied
ORDER BY seq_name;
SQL

  if [[ "$FK_COUNT" -gt 0 ]]; then
    echo "\echo 'Recreate FKs NOT VALID...'"
    printf "%s\n" "$FK_CREATE_SQL"
    echo "\echo 'Validate FKs...'"
    printf "%s\n" "$FK_VALIDATE_SQL"
  fi

  echo "COMMIT;"
} | PGPASSWORD="$DB_TARGET_PASSWORD" "$PSQL_BIN" \
      -X -q \
      -h "$TARGET_HOST" -p "$TARGET_PORT" \
      -U "$DB_TARGET_USER" -d "$DB_TARGET_NAME" \
      -v ON_ERROR_STOP=1

echo
echo "== Post: write restore log entry =="
psql_tgt -c "
  CREATE TABLE IF NOT EXISTS ${SCHEMA}.db_restore_log (
    id bigserial PRIMARY KEY,
    restored_at timestamptz NOT NULL DEFAULT now(),
    backup_file text NOT NULL,
    source_label text NULL
  );
  SELECT setval(
    pg_get_serial_sequence('${SCHEMA}.db_restore_log', 'id'),
    COALESCE((SELECT MAX(id) FROM ${SCHEMA}.db_restore_log), 0) + 1,
    false
  );
  INSERT INTO ${SCHEMA}.db_restore_log(backup_file, source_label)
  VALUES ('${DUMPFILE//\'/\'\'}', '${SOURCE_LABEL//\'/\'\'}');
"

if [[ "$DB_VERBOSE" == "1" ]]; then
  echo
  echo "== Verify (verbose) =="
  psql_tgt -c "SELECT id, restored_at, source_label, backup_file FROM ${SCHEMA}.db_restore_log ORDER BY restored_at DESC LIMIT 5;"
  psql_tgt -c "SELECT conrelid::regclass AS table, conname, convalidated FROM pg_constraint WHERE contype IN ('f','c') AND NOT convalidated ORDER BY 1,2;"
fi

echo
echo "✅ Restore complete. Log: $LOGFILE"