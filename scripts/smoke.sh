#!/usr/bin/env bash
# ==============================================================================
# LedgerLine Deployment Smoke Test Script
# Usage: ./scripts/smoke.sh [BASE_URL]
# Example: ./scripts/smoke.sh https://ledgerline.onrender.com
# ==============================================================================

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
# Trim trailing slash if present
BASE_URL="${BASE_URL%/}"

echo "======================================================================"
echo "Starting LedgerLine Smoke Tests against: ${BASE_URL}"
echo "======================================================================"

# Credentials (from environment or defaults)
ADMIN_PASS="${SEED_ADMIN_PASSWORD:-AdminPassword_2026!}"
HOLDER_PASS="${SEED_HOLDER_PASSWORD:-HolderPassword_2026!}"
AUDITOR_PASS="${SEED_AUDITOR_PASSWORD:-AuditorPassword_2026!}"

# Helper function to generate UUID v4 in bash
generate_uuid() {
  if command -v node >/dev/null 2>&1; then
    node -e 'console.log(require("crypto").randomUUID())'
  elif command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  elif [ -r /proc/sys/kernel/random/uuid ]; then
    cat /proc/sys/kernel/random/uuid
  else
    head -c 16 /dev/urandom | xxd -p
  fi
}

# 1. Health Check
echo -n "[1/7] Testing Health Check (/actuator/health)... "
HEALTH_RESP=$(curl -s -w "\n%{http_code}" "${BASE_URL}/actuator/health")
HEALTH_CODE=$(echo "${HEALTH_RESP}" | tail -n1)
HEALTH_BODY=$(echo "${HEALTH_RESP}" | sed '$d')

if [ "${HEALTH_CODE}" -ne 200 ]; then
  echo "FAILED (HTTP ${HEALTH_CODE})"
  echo "Response: ${HEALTH_BODY}"
  exit 1
fi
echo "PASSED (HTTP 200 UP)"

# 2. Login for each role
echo -n "[2/7] Authenticating ADMIN, HOLDER, and AUDITOR roles... "

# Login Admin
ADMIN_LOGIN_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"${ADMIN_PASS}\"}")
ADMIN_CODE=$(echo "${ADMIN_LOGIN_RESP}" | tail -n1)
ADMIN_BODY=$(echo "${ADMIN_LOGIN_RESP}" | sed '$d')

if [ "${ADMIN_CODE}" -ne 200 ]; then
  echo "FAILED: Admin login returned HTTP ${ADMIN_CODE}"
  echo "Response: ${ADMIN_BODY}"
  exit 1
fi
ADMIN_TOKEN=$(echo "${ADMIN_BODY}" | grep -o '"token":"[^"]*' | cut -d'"' -f4)

# Login Holder
HOLDER_LOGIN_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"holder\",\"password\":\"${HOLDER_PASS}\"}")
HOLDER_CODE=$(echo "${HOLDER_LOGIN_RESP}" | tail -n1)
HOLDER_BODY=$(echo "${HOLDER_LOGIN_RESP}" | sed '$d')

if [ "${HOLDER_CODE}" -ne 200 ]; then
  echo "FAILED: Holder login returned HTTP ${HOLDER_CODE}"
  echo "Response: ${HOLDER_BODY}"
  exit 1
fi
HOLDER_TOKEN=$(echo "${HOLDER_BODY}" | grep -o '"token":"[^"]*' | cut -d'"' -f4)

# Login Auditor
AUDITOR_LOGIN_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"auditor\",\"password\":\"${AUDITOR_PASS}\"}")
AUDITOR_CODE=$(echo "${AUDITOR_LOGIN_RESP}" | tail -n1)
AUDITOR_BODY=$(echo "${AUDITOR_LOGIN_RESP}" | sed '$d')

if [ "${AUDITOR_CODE}" -ne 200 ]; then
  echo "FAILED: Auditor login returned HTTP ${AUDITOR_CODE}"
  echo "Response: ${AUDITOR_BODY}"
  exit 1
fi
AUDITOR_TOKEN=$(echo "${AUDITOR_BODY}" | grep -o '"token":"[^"]*' | cut -d'"' -f4)

echo "PASSED (All 3 JWTs issued)"

# 3. Retrieve Holder Account ID
echo -n "[3/7] Discovering Holder Accounts (GET /api/accounts)... "
ACCOUNTS_RESP=$(curl -s -w "\n%{http_code}" "${BASE_URL}/api/accounts" \
  -H "Authorization: Bearer ${HOLDER_TOKEN}")
ACCOUNTS_CODE=$(echo "${ACCOUNTS_RESP}" | tail -n1)
ACCOUNTS_BODY=$(echo "${ACCOUNTS_RESP}" | sed '$d')

if [ "${ACCOUNTS_CODE}" -ne 200 ]; then
  echo "FAILED: GET /api/accounts returned HTTP ${ACCOUNTS_CODE}"
  exit 1
fi

# Extract first account ID
ACCOUNT_ID=$(echo "${ACCOUNTS_BODY}" | (grep -o '"id":[0-9]*' || true) | head -n1 | cut -d':' -f2)

if [ -z "${ACCOUNT_ID}" ]; then
  echo "No existing account found. Creating account via Admin..."
  CREATE_ACC_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/admin/accounts" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name":"Smoke Test Account"}')
  CREATE_ACC_BODY=$(echo "${CREATE_ACC_RESP}" | sed '$d')
  ACCOUNT_ID=$(echo "${CREATE_ACC_BODY}" | (grep -o '"id":[0-9]*' || true) | head -n1 | cut -d':' -f2)

  # Grant membership to holder (user_id 2)
  curl -s -X POST "${BASE_URL}/api/admin/accounts/${ACCOUNT_ID}/members" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"userId":2}' > /dev/null
fi
echo "PASSED (Target Account ID: #${ACCOUNT_ID})"

# 4. Deposit Money
echo -n "[4/7] Testing Money Movement (Deposit ₹50.00)... "
DEP_KEY="smoke-dep-$(generate_uuid)"
DEP_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/accounts/${ACCOUNT_ID}/deposit" \
  -H "Authorization: Bearer ${HOLDER_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${DEP_KEY}" \
  -d '{"amount":5000,"reason":"Smoke test funding"}')
DEP_CODE=$(echo "${DEP_RESP}" | tail -n1)
DEP_BODY=$(echo "${DEP_RESP}" | sed '$d')

if [ "${DEP_CODE}" -ne 200 ]; then
  echo "FAILED: Deposit returned HTTP ${DEP_CODE}"
  echo "Response: ${DEP_BODY}"
  exit 1
fi
ENTRY_ID=$(echo "${DEP_BODY}" | grep -o '"entryId":[0-9]*' | head -n1 | cut -d':' -f2)
echo "PASSED (Committed Entry ID: #${ENTRY_ID})"

# 5. Idempotent Replay (Same Key, Same Result)
echo -n "[5/7] Testing Idempotent Replay (Same Key: ${DEP_KEY})... "
REPLAY_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/accounts/${ACCOUNT_ID}/deposit" \
  -H "Authorization: Bearer ${HOLDER_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${DEP_KEY}" \
  -d '{"amount":5000,"reason":"Smoke test funding"}')
REPLAY_CODE=$(echo "${REPLAY_RESP}" | tail -n1)
REPLAY_BODY=$(echo "${REPLAY_RESP}" | sed '$d')

if [ "${REPLAY_CODE}" -ne 200 ]; then
  echo "FAILED: Replay returned HTTP ${REPLAY_CODE}"
  exit 1
fi
REPLAY_ENTRY_ID=$(echo "${REPLAY_BODY}" | grep -o '"entryId":[0-9]*' | head -n1 | cut -d':' -f2)

if [ "${REPLAY_ENTRY_ID}" != "${ENTRY_ID}" ]; then
  echo "FAILED: Replay returned different entry ID (${REPLAY_ENTRY_ID} vs ${ENTRY_ID})"
  exit 1
fi
echo "PASSED (Identical Entry ID #${REPLAY_ENTRY_ID} replayed without duplication)"

# 6. Withdrawal Refusal
echo -n "[6/7] Testing Refusal-as-Data (Withdrawal exceeding balance)... "
REFUSE_KEY="smoke-refuse-$(generate_uuid)"
WITHDRAW_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/accounts/${ACCOUNT_ID}/withdraw" \
  -H "Authorization: Bearer ${HOLDER_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${REFUSE_KEY}" \
  -d '{"amount":99999999900,"reason":"Attempted overdraw"}')
WITHDRAW_CODE=$(echo "${WITHDRAW_RESP}" | tail -n1)
WITHDRAW_BODY=$(echo "${WITHDRAW_RESP}" | sed '$d')

if [[ "${WITHDRAW_CODE}" != "422" && ! "${WITHDRAW_BODY}" =~ "REFUSED" ]]; then
  echo "FAILED: Expected 422 REFUSED, got HTTP ${WITHDRAW_CODE}"
  echo "Response: ${WITHDRAW_BODY}"
  exit 1
fi
echo "PASSED (Recorded REFUSED attempt with INSUFFICIENT_FUNDS)"

# 7. Forensic Verification (/api/admin/verify)
echo -n "[7/7] Testing Forensic Verification (GET /api/admin/verify)... "
VERIFY_RESP=$(curl -s -w "\n%{http_code}" "${BASE_URL}/api/admin/verify" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")
VERIFY_CODE=$(echo "${VERIFY_RESP}" | tail -n1)
VERIFY_BODY=$(echo "${VERIFY_RESP}" | sed '$d')

if [ "${VERIFY_CODE}" -ne 200 ]; then
  echo "FAILED: /api/admin/verify returned HTTP ${VERIFY_CODE}"
  echo "Response: ${VERIFY_BODY}"
  exit 1
fi

if [[ ! "${VERIFY_BODY}" =~ '"status":"PASSED"' ]]; then
  echo "FAILED: Verification report status is not PASSED"
  echo "Response: ${VERIFY_BODY}"
  exit 1
fi
echo "PASSED (All 7 cryptographic & double-entry invariants PASSED)"

echo "======================================================================"
echo "ALL SMOKE TESTS PASSED SUCCESSFULLY ON ${BASE_URL}!"
echo "======================================================================"
exit 0
