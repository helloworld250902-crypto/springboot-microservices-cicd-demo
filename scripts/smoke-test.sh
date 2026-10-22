#!/usr/bin/env bash
set -euo pipefail

ORDER_SERVICE_URL=${ORDER_SERVICE_URL:-http://localhost:8082}
ORDER_SERVICE_URL=${ORDER_SERVICE_URL%/}
PRODUCT_SERVICE_URL=${PRODUCT_SERVICE_URL:-http://localhost:8081}
PRODUCT_SERVICE_URL=${PRODUCT_SERVICE_URL%/}
HEALTH_BODY=$(mktemp)
ORDER_BODY=$(mktemp)
PRODUCT_BODY=$(mktemp)

cleanup() {
  rm -f "$HEALTH_BODY" "$ORDER_BODY" "$PRODUCT_BODY"
}
trap cleanup EXIT

read_stock() {
  curl --fail-with-body --silent --show-error \
    "$PRODUCT_SERVICE_URL/api/products/1" \
    --output "$PRODUCT_BODY"
  sed -n 's/.*"stock"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$PRODUCT_BODY"
}

curl --fail-with-body --silent --show-error \
  "$ORDER_SERVICE_URL/actuator/health" \
  --output "$HEALTH_BODY"

if ! grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$HEALTH_BODY"; then
  echo "order service health response is not UP" >&2
  exit 1
fi

STOCK_BEFORE=$(read_stock)
if ! [[ "$STOCK_BEFORE" =~ ^[0-9]+$ ]]; then
  echo "product response does not contain an integer stock" >&2
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

EXPECTED_STOCK=$((STOCK_BEFORE - 2))
if [ "$EXPECTED_STOCK" -lt 0 ]; then
  echo "stock $STOCK_BEFORE is too low for smoke order quantity 2" >&2
  exit 1
fi

STOCK_AFTER=""
for _ in {1..30}; do
  STOCK_AFTER=$(read_stock)
  if [ "$STOCK_AFTER" = "$EXPECTED_STOCK" ]; then
    break
  fi
  if [[ "$STOCK_AFTER" =~ ^[0-9]+$ ]] && [ "$STOCK_AFTER" -lt "$EXPECTED_STOCK" ]; then
    echo "stock decreased by more than 2: before=$STOCK_BEFORE after=$STOCK_AFTER" >&2
    exit 1
  fi
  sleep 0.5
done

if [ "$STOCK_AFTER" != "$EXPECTED_STOCK" ]; then
  echo "stock did not decrease by exactly 2: before=$STOCK_BEFORE after=$STOCK_AFTER" >&2
  exit 1
fi

echo "smoke test OK: order HTTP 201 and stock $STOCK_BEFORE -> $STOCK_AFTER"
