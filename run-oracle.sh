#!/bin/bash
# FinanceOS Backend — run against LOCAL ORACLE (prod parity).
# Start the database first:
#   docker compose -f docker-compose.oracle.yml up -d
# Then:
#   ./run-oracle.sh

cd "$(dirname "$0")"

# Load .env for ENCRYPTION_KEY / GMAIL_* etc. (strip Windows CRs, skip comments/blanks)
if [ -f .env ]; then
    export $(sed 's/\r$//' .env | grep -v '^#' | grep -v '^\s*$' | xargs)
fi

# Oracle connection — independent of the DB_* vars in .env (those target Postgres)
export ORACLE_HOST="${ORACLE_HOST:-localhost}"
export ORACLE_PORT="${ORACLE_PORT:-1521}"
export ORACLE_SERVICE="${ORACLE_SERVICE:-FREEPDB1}"
export ORACLE_USER="${ORACLE_USER:-financeos}"
export ORACLE_PASSWORD="${ORACLE_PASSWORD:-financeos}"

# Java
if ! command -v java &> /dev/null; then
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        export PATH="$JAVA_HOME/bin:$PATH"
    else
        echo "ERROR: Java 21 not found. Install it or set JAVA_HOME."
        exit 1
    fi
fi

if [ -z "$ENCRYPTION_KEY" ]; then
    echo "⚠️  ENCRYPTION_KEY not set. Add it to .env (generate: openssl rand -base64 32)."
    exit 1
fi

echo "🚀 Starting FinanceOS Backend on ORACLE (profile: oracle-local)"
echo "   DB: $ORACLE_USER@$ORACLE_HOST:$ORACLE_PORT/$ORACLE_SERVICE"
echo ""

chmod +x mvnw 2>/dev/null
./mvnw spring-boot:run -Dspring-boot.run.profiles=oracle-local
