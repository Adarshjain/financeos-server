#!/bin/bash
# FinanceOS — seed the database with a demo user + sample data via the REST API.
#
# Works against whichever DB the running app uses (Postgres or Oracle) since it
# only talks HTTP. Start the app first (./run.sh or ./run-oracle.sh), then:
#
#   ./seed.sh                       # seeds http://localhost:8080
#   BASE_URL=http://host:port ./seed.sh
#
# Re-runnable: signup is ignored if the demo user already exists. It will add
# more sample transactions on each run.

set -u
BASE="${BASE_URL:-http://localhost:8080}/api/v1"
EMAIL="${SEED_EMAIL:-demo@financeos.local}"
PASS="${SEED_PASSWORD:-demo1234}"
JAR="$(mktemp)"

json() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))" 2>/dev/null; }

echo "Seeding $BASE as $EMAIL"

# 1. Ensure the demo user exists (201 created, or 409 if already there)
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/signup" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
echo "  signup -> $code"

# 2. Log in and store the session cookie
curl -s -c "$JAR" -o /dev/null -w "  login  -> %{http_code}\n" -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}"

post() { curl -s -b "$JAR" -X POST "$BASE/$1" -H 'Content-Type: application/json' -d "$2"; }

# 3. Accounts
CHECKING=$(post accounts '{"name":"HDFC Checking","type":"bank_account","openingBalance":50000.00,"last4":"4321"}' | json id)
CARD=$(post accounts '{"name":"Amex Gold","type":"credit_card","last4":"1009","creditLimit":200000,"paymentDueDay":5,"gracePeriodDays":21}' | json id)
STOCK=$(post accounts '{"name":"Brokerage","type":"stock","instrumentCode":"AAPL","lastTradedPrice":195.50}' | json id)
echo "  accounts: checking=$CHECKING card=$CARD stock=$STOCK"

# 4. Transactions (negative = expense/DEBIT, positive = income/CREDIT)
post transactions "{\"accountId\":\"$CHECKING\",\"date\":\"2026-06-01\",\"amount\":85000.00,\"description\":\"Salary\",\"source\":\"manual\",\"metadata\":{\"category\":\"income\"}}" >/dev/null
post transactions "{\"accountId\":\"$CHECKING\",\"date\":\"2026-06-02\",\"amount\":-1500.00,\"description\":\"Groceries\",\"source\":\"manual\"}" >/dev/null
post transactions "{\"accountId\":\"$CARD\",\"date\":\"2026-06-03\",\"amount\":-3200.50,\"description\":\"Flight booking\",\"source\":\"manual\"}" >/dev/null
post transactions "{\"accountId\":\"$CHECKING\",\"date\":\"2026-06-05\",\"amount\":-899.00,\"description\":\"Internet bill\",\"source\":\"manual\"}" >/dev/null
echo "  created 4 transactions"

# 5. Investment buys (feeds the FIFO position calc)
post investments/transactions "{\"accountId\":\"$STOCK\",\"type\":\"buy\",\"quantity\":10,\"price\":190.00,\"date\":\"2026-05-01\"}" >/dev/null
post investments/transactions "{\"accountId\":\"$STOCK\",\"type\":\"buy\",\"quantity\":5,\"price\":200.00,\"date\":\"2026-05-15\"}" >/dev/null
echo "  created 2 investment buys"

echo
echo "Done. Explore it:"
echo "  curl -s -c j -X POST $BASE/auth/login -H 'Content-Type: application/json' -d '{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}'"
echo "  curl -s -b j $BASE/accounts | python3 -m json.tool"
echo "  curl -s -b j '$BASE/transactions' | python3 -m json.tool"
echo "  curl -s -b j $BASE/investments/position | python3 -m json.tool"
rm -f "$JAR"
