#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "deployment config contract failed: $1" >&2
  exit 1
}

for deployment in k8s/base/product-deployment.yml k8s/base/order-deployment.yml; do
  grep -Fq 'path: /actuator/health/readiness' "$deployment" \
    || fail "$deployment must use the readiness probe group"
  grep -Fq 'path: /actuator/health/liveness' "$deployment" \
    || fail "$deployment must use the liveness probe group"
done

for application in product-service/src/main/resources/application.yml order-service/src/main/resources/application.yml; do
  grep -Fq 'probes:' "$application" || fail "$application must enable health probes"
done

grep -Fq 'image: rabbitmq:4.1.8-management' docker-compose.yml \
  || fail 'Compose must pin RabbitMQ 4.1.8'
grep -Fq 'image: redis:7.4.11-alpine' docker-compose.yml \
  || fail 'Compose must pin Redis 7.4.11'
grep -Fq 'image: rabbitmq:4.1.8-management' k8s/base/rabbitmq-deployment.yml \
  || fail 'Kubernetes must pin RabbitMQ 4.1.8'
grep -Fq 'image: redis:7.4.11-alpine' k8s/base/redis-deployment.yml \
  || fail 'Kubernetes must pin Redis 7.4.11'

grep -Fq 'max-retries:' product-service/src/main/resources/application.yml \
  || fail 'Spring Boot 4 retry property must be max-retries'
if grep -Fq 'max-attempts:' product-service/src/main/resources/application.yml; then
  fail 'obsolete max-attempts property must not remain'
fi

echo "deployment config contracts OK"
