#!/bin/bash
# FinanceOS Backend Runner
# Usage: ./run.sh

cd "$(dirname "$0")"

# Load .env if exists
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi

# Defaults
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-financeos}"
export DB_USERNAME="${DB_USERNAME:-$(whoami)}"
export DB_PASSWORD="${DB_PASSWORD:-}"
export CORS_ORIGINS="${CORS_ORIGINS:-http://localhost:3000}"

# Check encryption key
if [ -z "$ENCRYPTION_KEY" ]; then
    echo "⚠️  ENCRYPTION_KEY not set!"
    echo ""
    echo "Generate one with:"
    echo "  openssl rand -base64 32"
    echo ""
    echo "Then either:"
    echo "  1. Add to .env file: ENCRYPTION_KEY=your_key_here"
    echo "  2. Or export it: export ENCRYPTION_KEY=your_key_here"
    echo ""
    exit 1
fi

echo "🚀 Starting FinanceOS Backend..."
echo "   Database: $DB_NAME@$DB_HOST:$DB_PORT"
echo "   CORS: $CORS_ORIGINS"
echo ""

./mvnw spring-boot:run

