#!/bin/bash
# Database connection script for howfar.nyc
# Usage:
#   ./scripts/db.sh                    # Interactive psql session
#   ./scripts/db.sh "SELECT COUNT(*) FROM intersections"  # Run SQL
#   ./scripts/db.sh -f schema.sql      # Run SQL file

# Source environment file
if [ -f /opt/howfar/howfar.env ]; then
    source /opt/howfar/howfar.env
fi

# Load from environment or use defaults matching config.edn
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-howfar}"
DB_USER="${DB_USER:-howfar}"
DB_PASSWORD="${DB_PASSWORD:-}"

# Build connection string
export PGPASSWORD="$DB_PASSWORD"

if [ "$1" = "-f" ] && [ -n "$2" ]; then
    # Run SQL file
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$2"
elif [ -n "$1" ]; then
    # Run SQL command
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "$1"
else
    # Interactive session
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME"
fi
