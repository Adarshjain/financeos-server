#!/bin/bash
# FinanceOS Backend Runner
# Usage: ./run.sh
#        DEBUG=1 ./run.sh   (starts with JDWP debugger on port 5005)

# Change to script directory
cd "$(dirname "$0")"

# Load .env if exists (handling Windows line endings)
if [ -f .env ]; then
    # Use sed to remove \r (CR) and grep to skip comments/empty lines
    export $(sed 's/\r$//' .env | grep -v '^#' | grep -v '^\s*$' | xargs)
fi

# Defaults
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-financeos}"
export DB_USERNAME="${DB_USERNAME:-financeos}"
export DB_PASSWORD="${DB_PASSWORD:-financeos}"
export CORS_ORIGINS="${CORS_ORIGINS:-http://localhost:3001}"

# Check for Java
if ! command -v java &> /dev/null; then
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        export PATH="$JAVA_HOME/bin:$PATH"
    else
        echo "ERROR: Java is not installed or not in PATH!"
        echo "Please install Java 21."
        exit 1
    fi
fi

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

# Ensure mvnw is executable
chmod +x mvnw 2>/dev/null

if [ -n "$DEBUG" ]; then
    echo "🐛 Debug mode: JDWP listening on port 5005"
    ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
else
    ./mvnw spring-boot:run
fi

