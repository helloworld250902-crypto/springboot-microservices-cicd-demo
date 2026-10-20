#!/usr/bin/env bash
set -euo pipefail

ORDER_SERVICE_URL=${ORDER_SERVICE_URL:-http://localhost:8082}
ORDER_SERVICE_URL=${ORDER_SERVICE_URL%/}
HEALTH_BODY=$(mktemp)
ORDER_BODY=$(mktemp)

cleanup() {
  rm -f "$HEALTH_BODY" "$ORDER_BODY"
}
trap cleanup EXIT

curl --fail-with-body --silent --show-error \
  "$ORDER_SERVICE_URL/actuator/health" \
  --output "$HEALTH_BODY"

if ! grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$HEALTH_BODY"; then
  echo "order service health response is not UP" >&2
  exit 1
fi

HTTP_STATUS=$(curl --fail-with-body --silent --show-error \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"productId":1,"quantity":2}' \
  --output "$ORDER_BODY" \
  --write-out '%{http_code}' \
  "$ORDER_SERVICE_URL/api/orders")

if [ "$HTTP_STATUS" != "201" ]; then
  echo "order creation returned HTTP $HTTP_STATUS instead of 201" >&2
  exit 1
fi

if ! grep -Eq '"totalPrice"[[:space:]]*:[[:space:]]*1198\.00' "$ORDER_BODY"; then
  echo "order response does not contain totalPrice 1198.00" >&2
  exit 1
fi

echo "smoke test OK: health UP, order HTTP 201, totalPrice 1198.00"
