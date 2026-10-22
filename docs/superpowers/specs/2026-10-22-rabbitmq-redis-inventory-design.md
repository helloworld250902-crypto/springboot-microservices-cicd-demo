# RabbitMQ、Redis 库存扣减扩展设计

## 1. 目标

在现有 Maven 多模块 Spring Boot 微服务示例中增加 RabbitMQ 和 Redis，完整展示订单创建后的异步库存扣减、Redis 分布式锁、重复消息幂等处理，以及这些组件如何进入 Docker Compose、Kubernetes 和 Jenkins CI/CD 流程。

必须保持现有环境触发约定：

- `develop` 分支 Push 后，由 `jenkins/Jenkinsfile.test` 自动构建并部署测试环境。
- `main` 分支的生产环境只能通过 `jenkins/Jenkinsfile.production` 参数化任务手动触发。
- 生产环境复用测试环境已经验证过的 Git SHA 镜像，不重新构建业务镜像。

## 2. 总体架构

```text
客户端
  │ POST /api/orders
  ▼
order-service
  ├─ HTTP 查询 product-service 的商品信息
  ├─ 创建订单编号
  └─ 发布 OrderCreatedEvent
              │
              ▼
         RabbitMQ
              │ order.created
              ▼
product-service 消费者
  ├─ 获取 lock:inventory:{productId}
  ├─ 检查 processed-order:{orderId}
  ├─ 读取 inventory:{productId}
  ├─ 原子扣减库存并写入幂等标记
  └─ 释放 Redis 分布式锁
```

商品名称和价格仍由 `product-service` 的示例商品目录管理；库存移入 Redis，使多个商品服务副本共享同一份库存。RabbitMQ 负责订单事件传递，Redisson 负责按商品 ID 加锁。

## 3. 消息模型与 RabbitMQ 拓扑

订单服务发布 `OrderCreatedEvent`：

```text
orderId: UUID
productId: long
quantity: int
```

RabbitMQ 使用以下固定名称：

| 类型 | 名称 |
| --- | --- |
| Topic Exchange | `orders.exchange` |
| Routing Key | `order.created` |
| 主队列 | `inventory.order-created` |
| 死信 Exchange | `orders.dlx` |
| 死信队列 | `inventory.order-created.dlq` |

Exchange、Queue 和 Binding 由 Spring Bean 声明为持久化资源。消费者临时失败时有限重试；超过次数或库存不足时拒绝消息并进入死信队列，避免无限重投。

## 4. 库存、锁与幂等

Redis Key 约定：

```text
inventory:{productId}       商品共享库存
lock:inventory:{productId}  Redisson 分布式锁
processed-order:{orderId}   已成功处理的订单标记
```

商品服务启动时使用 `SETNX` 思路初始化演示库存，不覆盖已经存在的库存。查询商品接口增加 `stock` 字段，库存以 Redis 中的当前值为准。

消费订单事件时：

1. 使用 Redisson 获取对应商品的分布式锁。
2. 在锁内执行 Redis 原子脚本。
3. 如果订单幂等标记已存在，返回重复结果，不再次扣减。
4. 如果商品不存在或库存不足，返回失败结果并将消息送入死信队列。
5. 否则扣减库存并写入订单幂等标记。
6. 仅由实际持锁线程释放锁。

Redis 原子脚本用于消除“库存已扣减、幂等标记尚未写入时进程崩溃”的窗口。分布式锁保留用于演示多个商品服务副本对同一商品的串行处理；即使锁租约或消费者进程异常，原子脚本仍保证单条订单不会重复扣减。

## 5. API 行为

`GET /api/products/{id}` 返回：

```json
{
  "id": 1,
  "name": "Mechanical Keyboard",
  "price": 599.00,
  "stock": 100
}
```

`POST /api/orders` 保持返回 HTTP 201。它表示订单事件已经提交给消息系统，不表示库存已经同步扣减。库存采用最终一致性，客户端或测试通过商品查询接口观察结果。

订单服务可以使用查询时库存进行早期校验，但消费者中的 Redis 原子扣减是并发情况下的最终判断。当前教学范围不增加订单状态查询和库存失败后的业务补偿；库存不足事件进入死信队列并记录清晰日志。

RabbitMQ 不可用或事件发布失败时，订单接口返回服务不可用错误，不返回成功订单。

## 6. 本地 Docker Compose

`docker-compose.yml` 增加：

- `rabbitmq`：使用带管理界面的官方镜像，提供 AMQP 端口和管理端口，并配置健康检查。
- `redis`：使用官方 Redis 镜像并配置健康检查。
- `product-service`：依赖 RabbitMQ 和 Redis 健康，注入两者连接地址。
- `order-service`：依赖 RabbitMQ 和商品服务健康，注入 RabbitMQ 与商品服务地址。

业务服务的 Dockerfile 继续只复制 Maven 已验证并打包好的 JAR，不在镜像构建中重复执行 Maven。

## 7. Kubernetes

`k8s/base` 增加 RabbitMQ、Redis 的 Deployment 和 ClusterIP Service，并给业务 Deployment 增加连接环境变量。RabbitMQ 和 Redis 使用就绪、存活探针以及明确的资源请求和限制。

测试和生产 overlay 继续复用同一个 base：

| 环境 | 触发方式 | Namespace | 业务服务副本 |
| --- | --- | --- | --- |
| 测试 | `develop` Push 自动触发 | `microservices-test` | 各 1 个 |
| 生产 | Jenkins 参数化任务手动触发 | `microservices-production` | 各 2 个 |

RabbitMQ 和 Redis 在本项目中保持单副本、无持久卷，仅用于学习配置与调用关系。生产系统应使用托管服务或高可用集群、持久卷和备份策略。

## 8. Jenkins 与 GHCR 流程

### 测试环境自动流水线

```text
develop Push / GitHub Webhook
→ Maven clean verify
→ 构建 product-service 和 order-service 镜像
→ 以 Git SHA 推送到 GHCR
→ 渲染 test Kustomize overlay
→ Kubernetes 部署 Redis、RabbitMQ 和两个业务服务
→ 等待全部 rollout
→ 冒烟测试验证下单后库存减少
```

RabbitMQ 和 Redis 使用公开的官方镜像，不由 Jenkins 重新构建或推送到 GHCR。

### 生产环境手动流水线

```text
人工输入 IMAGE_TAG 和 CONFIRM_DEPLOYMENT=DEPLOY
→ 验证两个 Git SHA 业务镜像存在
→ 渲染 production Kustomize overlay
→ Kubernetes 部署
→ 等待全部 rollout
→ 冒烟测试验证库存扣减
```

生产流水线不得配置 GitHub Push 自动触发，也不得重新执行 Maven 或构建新镜像。

## 9. 测试与验收

自动化测试至少覆盖：

- 订单服务成功发布 `OrderCreatedEvent`。
- RabbitMQ 发布失败时订单接口不返回成功。
- 商品查询返回 Redis 中的库存。
- 正常消息精确扣减指定数量。
- 相同 `orderId` 重复投递只扣减一次。
- 库存不足不产生负库存。
- 获取分布式锁失败不会执行库存修改。
- Maven 全量测试继续通过。

配置与端到端验证：

- `docker compose config` 能正确解析。
- Docker Compose 启动四个组件后，冒烟测试确认库存减少。
- test 和 production Kustomize overlay 均能渲染。
- Jenkins 静态契约检查同时覆盖自动测试触发、手动生产触发、RabbitMQ/Redis rollout 和库存冒烟测试。

冒烟测试读取下单前库存，提交数量为 2 的订单，然后轮询商品接口，直到库存准确减少 2；不依赖固定初始库存，因此可重复执行。

## 10. 非目标与生产边界

本次不增加 MySQL、支付、用户认证、完整订单生命周期、Outbox/Inbox、消息追踪平台或中间件高可用部署。这些能力会掩盖本示例的核心学习路径。

教学示例不能直接视为生产级电商方案。真实生产系统至少需要持久化订单数据库、可靠事件表或事务消息、库存失败补偿、中间件高可用、监控告警、密钥管理和灾难恢复。
