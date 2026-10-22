# RabbitMQ、Redis 库存扣减实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有双微服务示例中加入 RabbitMQ 订单事件、Redis 共享库存、Redisson 分布式锁和幂等扣减，并把它们完整接入 Compose、Kubernetes 与测试自动/生产手动 Jenkins 流程。

**Architecture:** 新增一个只包含消息 DTO 的 Maven 契约模块。订单服务成功查询商品后发布订单事件；商品服务消费事件，在商品级 Redisson 锁内通过 Redis Lua 脚本原子完成库存校验、扣减和幂等标记。Redis 与 RabbitMQ 作为本地 Compose 和 Kubernetes 基础设施运行，业务镜像仍由 Jenkins 构建并存入 GHCR。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring AMQP、Redis、Redisson 4.8.0、RabbitMQ、Maven、Docker Compose、Kustomize、Jenkins

**Spec:** `docs/superpowers/specs/2026-10-22-rabbitmq-redis-inventory-design.md`

## Global Constraints

- `develop` Push 只能自动触发 `jenkins/Jenkinsfile.test` 测试部署。
- `jenkins/Jenkinsfile.production` 不得包含自动触发器，只接受人工输入的 Git SHA 与 `CONFIRM_DEPLOYMENT=DEPLOY`。
- 生产流水线复用测试通过的两个业务镜像，不执行 Maven、`docker build` 或 `docker push`。
- 商品名称和价格保留在代码中，库存与订单幂等标记存入 Redis。
- RabbitMQ、Redis 在教学部署中为单副本、无持久卷；文档必须说明生产边界。
- Dockerfile 继续只复制 Maven 已生成的 JAR。
- Redisson 使用官方支持 Spring Boot 4.1 的 `redisson-spring-boot-starter:4.8.0`。

## Review Focus

- 同一 `orderId` 重复投递时必须只扣减一次；Task 2 的重复消息测试固定此行为。
- 同一商品的并发消息必须使用同一个锁名；Task 2 的锁名测试固定此行为。
- 库存恰好等于购买数量时允许扣到零，低于数量时不得变负；Task 2 的边界测试固定此行为。
- RabbitMQ 不可用或发布异常时订单接口不得返回 201；Task 1 的发布失败测试固定此行为。
- 冒烟测试多次运行时不得依赖固定库存；Task 3 的自测以“前值减去购买数量”断言。

---

### Task 1: 共享事件契约与订单事件发布

**Files:**
- Create: `event-contracts/pom.xml`
- Create: `event-contracts/src/main/java/com/example/events/OrderCreatedEvent.java`
- Modify: `pom.xml`
- Modify: `order-service/pom.xml`
- Create: `order-service/src/main/java/com/example/order/OrderEventPublisher.java`
- Create: `order-service/src/main/java/com/example/order/RabbitOrderEventPublisher.java`
- Create: `order-service/src/main/java/com/example/order/RabbitOrderConfiguration.java`
- Create: `order-service/src/main/java/com/example/order/OrderEventPublishException.java`
- Modify: `order-service/src/main/java/com/example/order/OrderService.java`
- Modify: `order-service/src/main/java/com/example/order/ApiExceptionHandler.java`
- Modify: `order-service/src/main/resources/application.yml`
- Modify: `order-service/src/test/java/com/example/order/OrderServiceTest.java`
- Modify: `order-service/src/test/java/com/example/order/OrderControllerTest.java`

**Interfaces:**
- Produces: `OrderCreatedEvent(UUID orderId, long productId, int quantity)`.
- Produces: `OrderEventPublisher.publish(OrderCreatedEvent event)`.
- Produces: RabbitMQ exchange `orders.exchange` and routing key `order.created`.

- [ ] **Step 1: Write failing order event tests**

Add tests proving `OrderService.createOrder` publishes one event containing the returned order ID, product ID and quantity, and proving a publisher exception maps to HTTP 503 instead of 201.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `./mvnw -pl order-service -am test -Dtest=OrderServiceTest,OrderControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because the event contract and publisher dependency do not exist.

- [ ] **Step 3: Add the event contract and minimal publisher implementation**

Add `event-contracts` before both business modules in the root reactor. Add Spring AMQP and the contract dependency to `order-service`. Implement the publisher with `RabbitTemplate` and a JSON message converter; enable publisher confirms and convert AMQP publish failures into `OrderEventPublishException`.

- [ ] **Step 4: Publish only after product lookup and order calculation succeed**

Construct one UUID, use it in both `OrderResponse` and `OrderCreatedEvent`, then publish before returning the response. Map `OrderEventPublishException` to a Problem Detail with HTTP 503.

- [ ] **Step 5: Run focused and module tests and verify GREEN**

Run: `./mvnw -pl order-service -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: all contract and order tests pass.

- [ ] **Step 6: Commit**

```bash
git add pom.xml event-contracts order-service
git commit -m "feat: publish order-created events"
```

### Task 2: Redis 库存、Redisson 锁与 RabbitMQ 消费者

**Files:**
- Modify: `product-service/pom.xml`
- Modify: `product-service/src/main/java/com/example/product/Product.java`
- Modify: `product-service/src/main/java/com/example/product/ProductCatalog.java`
- Create: `product-service/src/main/java/com/example/product/InventoryStore.java`
- Create: `product-service/src/main/java/com/example/product/InventoryDeductionResult.java`
- Create: `product-service/src/main/java/com/example/product/RedisInventoryStore.java`
- Create: `product-service/src/main/java/com/example/product/InventoryInitializer.java`
- Create: `product-service/src/main/java/com/example/product/InventoryMessageListener.java`
- Create: `product-service/src/main/java/com/example/product/RabbitInventoryConfiguration.java`
- Create: `product-service/src/main/java/com/example/product/InventoryLockUnavailableException.java`
- Modify: `product-service/src/main/resources/application.yml`
- Modify: `product-service/src/test/java/com/example/product/ProductControllerTest.java`
- Create: `product-service/src/test/java/com/example/product/ProductCatalogTest.java`
- Create: `product-service/src/test/java/com/example/product/RedisInventoryStoreTest.java`
- Create: `product-service/src/test/java/com/example/product/InventoryMessageListenerTest.java`

**Interfaces:**
- Consumes: `OrderCreatedEvent` and RabbitMQ queue `inventory.order-created`.
- Produces: `InventoryStore.initialize(long productId, int stock)`, `getStock(long productId)` and `deductOnce(UUID orderId, long productId, int quantity)`.
- Produces: `InventoryDeductionResult` values `DEDUCTED`, `DUPLICATE`, `INSUFFICIENT_STOCK`, `PRODUCT_NOT_FOUND`.
- Produces: product JSON field `stock`.

- [ ] **Step 1: Write failing product and consumer tests**

Add tests for Redis-backed stock in product responses, initialization without overwrite, exact deduction, exact-stock-to-zero, insufficient stock, missing product, duplicate order ID, lock name `lock:inventory:1`, lock timeout, interruption and unlock only when held by the current thread.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./mvnw -pl product-service -am test -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL because inventory interfaces and listener do not exist and `Product` has no stock field.

- [ ] **Step 3: Implement the Redis inventory store**

Use `StringRedisTemplate` for initialization and reads. Use one `DefaultRedisScript<Long>` to check `processed-order:{orderId}`, validate `inventory:{productId}`, decrement it, and write the processed marker atomically. Map script result codes to `InventoryDeductionResult`.

- [ ] **Step 4: Implement the product-level distributed lock and listener**

Use `RedissonClient.getLock("lock:inventory:" + productId)` and `tryLock` with configured wait and lease durations. Duplicate messages return normally; inventory failures throw `AmqpRejectAndDontRequeueException`; temporary lock failures throw `InventoryLockUnavailableException` for configured retry.

- [ ] **Step 5: Declare RabbitMQ topology and retry policy**

Declare durable main/DLX exchanges, main/DLQ queues and bindings. Configure listener retry with three attempts and `default-requeue-rejected: false`, so exhausted or explicitly rejected messages reach the DLQ.

- [ ] **Step 6: Run focused and full Maven tests and verify GREEN**

Run: `./mvnw -pl product-service -am test -Dsurefire.failIfNoSpecifiedTests=false`

Run: `./mvnw clean verify`

Expected: all tests pass and three reactor modules build successfully.

- [ ] **Step 7: Commit**

```bash
git add product-service pom.xml event-contracts
git commit -m "feat: deduct shared inventory with Redis lock"
```

### Task 3: Docker Compose 与库存端到端冒烟测试

**Files:**
- Modify: `docker-compose.yml`
- Modify: `scripts/smoke-test.sh`
- Modify: `scripts/SmokeTestServer.java`
- Modify: `scripts/smoke-test-self-test.sh`

**Interfaces:**
- Consumes: product endpoint with `stock`, order endpoint, RabbitMQ and Redis application properties.
- Produces: repeatable smoke contract “下单数量 2 后库存恰好减少 2”。

- [ ] **Step 1: Extend the fake server and write the failing smoke self-test**

The fake server must expose product stock and decrement it when receiving an order. Update the shell test to read `PRODUCT_SERVICE_URL`, capture stock before ordering, poll after ordering and assert the difference is exactly two.

- [ ] **Step 2: Run self-test and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) bash scripts/smoke-test-self-test.sh`

Expected: FAIL until the fake product endpoint and stock polling behavior are implemented together.

- [ ] **Step 3: Add Redis and RabbitMQ to Compose**

Add health-checked `redis` and `rabbitmq` services with demo-only credentials. Inject RabbitMQ into both business services and Redis into product service; express health-based startup dependencies. Keep application Dockerfiles unchanged.

- [ ] **Step 4: Verify scripts and Compose**

Run: `bash -n scripts/*.sh`

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) bash scripts/smoke-test-self-test.sh`

Run: `docker compose config`

If Docker is available, run: `./mvnw clean package && docker compose up --build --detach && bash scripts/smoke-test.sh && docker compose down --volumes --remove-orphans`

Expected: static checks pass; with Docker, four services become healthy and stock decreases exactly by two.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml scripts
git commit -m "test: verify asynchronous inventory flow"
```

### Task 4: Kubernetes 中间件与业务连接配置

**Files:**
- Create: `k8s/base/rabbitmq-deployment.yml`
- Create: `k8s/base/rabbitmq-service.yml`
- Create: `k8s/base/rabbitmq-secret.yml`
- Create: `k8s/base/redis-deployment.yml`
- Create: `k8s/base/redis-service.yml`
- Modify: `k8s/base/kustomization.yml`
- Modify: `k8s/base/product-deployment.yml`
- Modify: `k8s/base/order-deployment.yml`
- Modify: `k8s/overlays/test/replicas.yml`
- Modify: `k8s/overlays/production/replicas.yml`

**Interfaces:**
- Produces: cluster DNS names `rabbitmq:5672` and `redis:6379`.
- Consumes: existing test and production namespaces and GHCR business images.

- [ ] **Step 1: Add RabbitMQ and Redis Kubernetes resources**

Use single-replica Deployments, ClusterIP Services, probes and resource limits. Store only documented demo credentials in `rabbitmq-secret.yml`; do not place real credentials in Git.

- [ ] **Step 2: Wire business Deployments**

Set RabbitMQ host/credentials on both business services and Redis host on product service. Test overlay keeps all workloads at one replica; production keeps business services at two and middleware at one.

- [ ] **Step 3: Render and inspect both overlays**

Run: `kubectl kustomize k8s/overlays/test`

Run: `kubectl kustomize k8s/overlays/production`

Expected: each render contains four Deployments, four Services, one RabbitMQ Secret, correct namespace, expected business replicas, connection environment variables and the environment-specific business image tags.

- [ ] **Step 4: Commit**

```bash
git add k8s
git commit -m "deploy: add RabbitMQ and Redis resources"
```

### Task 5: Jenkins 环境触发与完整调用文档

**Files:**
- Modify: `jenkins/Jenkinsfile.test`
- Modify: `jenkins/Jenkinsfile.production`
- Modify: `scripts/verify-pipelines.sh`
- Modify: `README.md`
- Modify: `docs/configuration-flow.md`

**Interfaces:**
- Consumes: all Maven, Docker, GHCR, Kubernetes and smoke-test behavior from Tasks 1–4.
- Produces: `develop` 自动测试发布和 `main` 手动生产晋级的完整教学流程。

- [ ] **Step 1: Extend the failing pipeline contract checks**

Require both pipelines to wait for Redis and RabbitMQ rollout, port-forward both product and order services, pass both URLs to the smoke test and clean up both processes. Preserve checks that production has no trigger, Maven or Docker build/push.

- [ ] **Step 2: Run the contract checker and verify RED**

Run: `bash scripts/verify-pipelines.sh`

Expected: FAIL because current pipelines do not wait for middleware or expose the product endpoint.

- [ ] **Step 3: Update both pipelines**

Test remains GitHub Push triggered and performs Maven/build/push/deploy/smoke. Production remains parameterized and only verifies existing images/deploys/smokes. Both wait for four Deployments and use separate product/order port-forward processes with liveness checks and cleanup traps.

- [ ] **Step 4: Update Chinese documentation**

Document the complete request/event/lock/inventory path, every configuration file's caller, local Compose commands, GHCR role, Kubernetes middleware resources, RabbitMQ management UI, Redis inspection commands, test automatic trigger, production manual trigger, DLQ inspection and production limitations.

- [ ] **Step 5: Run all available verification**

Run: `./mvnw clean verify`

Run: `bash -n scripts/*.sh`

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) bash scripts/smoke-test-self-test.sh`

Run: `bash scripts/verify-pipelines.sh`

Run when available: `docker compose config`, Compose end-to-end smoke, both `kubectl kustomize` commands, and a Jenkins Declarative Pipeline linter.

Expected: every installed-tool check passes; unavailable Docker/Kubernetes/Jenkins runtime checks are reported as skipped, never represented as passed.

- [ ] **Step 6: Commit**

```bash
git add jenkins scripts README.md docs/configuration-flow.md
git commit -m "docs: explain message-driven inventory delivery"
```

### Task 6: 全分支复核与发布

**Files:**
- Review: all files changed since `08bc41a`

**Interfaces:**
- Consumes: completed feature branch.
- Produces: reviewed commits ready for `main`、`develop` and GitHub.

- [ ] **Step 1: Review the complete diff against the spec**

Check for lost existing behavior, committed credentials, image tag drift, missing trigger protections, unsafe unlock, non-idempotent deduction and documentation/config mismatches.

- [ ] **Step 2: Run final verification from a clean build**

Run all Task 5 verification commands and inspect `git status --short`.

Expected: all available checks pass and only intentional files differ.

- [ ] **Step 3: Integrate and publish after review**

Fast-forward the reviewed branch into `main`, update `develop` to the same verified commit, push both branches, and confirm the remote refs. Do not publish if any required available check is failing.
