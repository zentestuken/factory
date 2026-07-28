#!/usr/bin/env bash
# Deletes stuck factory executions and their associated reviews and artifacts.
# Audit log is append-only by design and is NOT cleared.
#
# Usage:
#   ./scripts/reset-executions.sh              — delete only non-terminal executions
#                                                (RUNNING / PENDING / PAUSED / PENDING_RETRY / ESCALATED)
#   ./scripts/reset-executions.sh --all        — delete ALL executions regardless of status
#   ./scripts/reset-executions.sh --all [name] — same, with a custom container name

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"
if [ -f "$ENV_FILE" ]; then
  set -a; source "$ENV_FILE"; set +a
fi

CONTAINER="factory-postgres-1"
DELETE_ALL=false

for arg in "$@"; do
  case "$arg" in
    --all) DELETE_ALL=true ;;
    *)     CONTAINER="$arg" ;;
  esac
done

DB_USER=${FACTORY_DB_USER:-factory}
DB_NAME=$(echo "${FACTORY_DB_URL:-factory}" | sed 's|.*\/||')

if [ "$DELETE_ALL" = true ]; then
  WHERE=""
  echo "Deleting ALL executions..."
else
  TERMINAL="('COMPLETED','FAILED_ESCALATED','REJECTED','CANCELLED')"
  WHERE="WHERE execution_id IN (SELECT id FROM pipeline_execution WHERE status NOT IN $TERMINAL)"
  EXEC_WHERE="WHERE status NOT IN $TERMINAL"
  echo "Deleting non-terminal executions (RUNNING / PENDING / PAUSED / ...)..."
fi

podman exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c "
DELETE FROM hitl_review ${WHERE:-};
DELETE FROM artifact   ${WHERE:-};
DELETE FROM pipeline_execution ${EXEC_WHERE:-};
SELECT 'executions: ' || count(*) FROM pipeline_execution
UNION ALL SELECT 'reviews:    ' || count(*) FROM hitl_review
UNION ALL SELECT 'artifacts:  ' || count(*) FROM artifact;
"
