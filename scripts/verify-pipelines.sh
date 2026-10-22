#!/usr/bin/env bash
set -euo pipefail

TEST_PIPELINE=jenkins/Jenkinsfile.test
PRODUCTION_PIPELINE=jenkins/Jenkinsfile.production

fail() {
  echo "pipeline contract failed: $1" >&2
  exit 1
}

require_file() {
  [ -f "$1" ] || fail "missing $1"
}

require_text() {
  grep -Fq "$2" "$1" || fail "$1 must contain: $2"
}

reject_text() {
  if grep -Fq "$2" "$1"; then
    fail "$1 must not contain: $2"
  fi
}

require_count() {
  count=$(grep -Fc "$2" "$1" || true)
  [ "$count" -ge "$3" ] || fail "$1 must contain $2 at least $3 times"
}

require_file "$TEST_PIPELINE"
require_file "$PRODUCTION_PIPELINE"

require_text "$TEST_PIPELINE" "githubPush()"
require_text "$TEST_PIPELINE" "./mvnw clean verify"
require_count "$TEST_PIPELINE" "docker build" 2
require_count "$TEST_PIPELINE" "docker push" 2
require_text "$TEST_PIPELINE" "github-container-registry"
require_text "$TEST_PIPELINE" 'DOCKER_CONFIG=$(mktemp -d'
require_text "$TEST_PIPELINE" "kubeconfig-test"
require_text "$TEST_PIPELINE" "k8s/overlays/test"
require_text "$TEST_PIPELINE" "scripts/smoke-test.sh"
require_text "$TEST_PIPELINE" "deployment/rabbitmq"
require_text "$TEST_PIPELINE" "deployment/redis"
require_text "$TEST_PIPELINE" "18081:8081"
require_text "$TEST_PIPELINE" "18082:8082"
require_text "$TEST_PIPELINE" 'PRODUCT_SERVICE_URL=http://127.0.0.1:18081'
require_text "$TEST_PIPELINE" 'ORDER_SERVICE_URL=http://127.0.0.1:18082'
require_text "$TEST_PIPELINE" 'kill -0 "$PRODUCT_PORT_FORWARD_PID"'
require_text "$TEST_PIPELINE" 'kill -0 "$ORDER_PORT_FORWARD_PID"'
require_text "$TEST_PIPELINE" 'kill "$PRODUCT_PORT_FORWARD_PID" "$ORDER_PORT_FORWARD_PID"'

reject_text "$PRODUCTION_PIPELINE" "triggers {"
require_text "$PRODUCTION_PIPELINE" "IMAGE_TAG"
require_text "$PRODUCTION_PIPELINE" "CONFIRM_DEPLOYMENT"
require_text "$PRODUCTION_PIPELINE" "CONFIRM_DEPLOYMENT == 'DEPLOY'"
require_count "$PRODUCTION_PIPELINE" "docker manifest inspect" 2
require_text "$PRODUCTION_PIPELINE" "github-container-registry"
require_text "$PRODUCTION_PIPELINE" 'DOCKER_CONFIG=$(mktemp -d'
require_text "$PRODUCTION_PIPELINE" "kubeconfig-production"
require_text "$PRODUCTION_PIPELINE" "k8s/overlays/production"
require_text "$PRODUCTION_PIPELINE" "scripts/smoke-test.sh"
require_text "$PRODUCTION_PIPELINE" "deployment/rabbitmq"
require_text "$PRODUCTION_PIPELINE" "deployment/redis"
require_text "$PRODUCTION_PIPELINE" "18084:8081"
require_text "$PRODUCTION_PIPELINE" "18083:8082"
require_text "$PRODUCTION_PIPELINE" 'PRODUCT_SERVICE_URL=http://127.0.0.1:18084'
require_text "$PRODUCTION_PIPELINE" 'ORDER_SERVICE_URL=http://127.0.0.1:18083'
require_text "$PRODUCTION_PIPELINE" 'kill -0 "$PRODUCT_PORT_FORWARD_PID"'
require_text "$PRODUCTION_PIPELINE" 'kill -0 "$ORDER_PORT_FORWARD_PID"'
require_text "$PRODUCTION_PIPELINE" 'kill "$PRODUCT_PORT_FORWARD_PID" "$ORDER_PORT_FORWARD_PID"'
reject_text "$PRODUCTION_PIPELINE" "docker build"
reject_text "$PRODUCTION_PIPELINE" "mvnw"

echo "pipeline contracts OK"
