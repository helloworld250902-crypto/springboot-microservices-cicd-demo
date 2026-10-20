# Spring Boot 微服务 CI/CD 演示项目设计规格

## 项目目标

构建一个简单、可运行的教学代码库，展示 Spring Boot、Maven、Docker、
Jenkins、Kubernetes 和 GitHub 如何组成一条完整的交付流水线。代码库必须
清楚说明各配置文件之间的调用关系，并分别演示自动触发的测试环境部署和
手动触发的生产环境部署。

代码库最终发布为公开 GitHub 仓库：
`helloworld250902-crypto/springboot-microservices-cicd-demo`。

## 项目范围

演示项目包含两个 Spring Boot 服务：

- `product-service`：维护内存中的商品目录，并提供商品查询接口。
- `order-service`：接收下单请求，调用 `product-service`，返回包含商品信息和
  总金额的订单摘要。

项目重点是展示交付流程，因此不引入数据库、消息队列、服务注册中心、
API 网关、身份认证、Helm 或特定云厂商的 Kubernetes 服务。

## 技术基线

- Java 21。
- Spring Boot 4.1.1。
- Maven Wrapper 和 Maven 多模块 Reactor 构建。
- 基于 Eclipse Temurin Java 21 运行时镜像构建 Docker 镜像。
- 使用 Docker Compose 进行本地容器联调。
- 使用 Kustomize base 和 overlay 组织原生 Kubernetes 资源。
- 使用 Jenkins 声明式流水线。
- 使用 GitHub Container Registry（GHCR）存储镜像。

## 代码库结构

```text
springboot-microservices-cicd-demo/
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/wrapper/
├── product-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
├── order-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
├── docker-compose.yml
├── jenkins/
│   ├── Jenkinsfile.test
│   └── Jenkinsfile.production
├── k8s/
│   ├── base/
│   └── overlays/
│       ├── test/
│       └── production/
├── scripts/
│   └── smoke-test.sh
└── README.md
```

## 服务接口约定

### 商品服务

`GET /api/products/{id}` 查询已存在的商品：

```json
{
  "id": 1,
  "name": "Mechanical Keyboard",
  "price": 599.00
}
```

商品 ID 不存在时，以 Spring `ProblemDetail` 格式返回 HTTP 404，其中
`title` 为 `Product not found`，`detail` 指明请求的商品 ID。

`GET /actuator/health` 供 Docker、Kubernetes 和冒烟测试执行健康检查。

### 订单服务

`POST /api/orders` 接收：

```json
{
  "productId": 1,
  "quantity": 2
}
```

订单服务调用 `GET {product-service-url}/api/products/{id}`，成功时返回
HTTP 201：

```json
{
  "orderId": "generated-uuid",
  "productId": 1,
  "productName": "Mechanical Keyboard",
  "quantity": 2,
  "unitPrice": 599.00,
  "totalPrice": 1198.00
}
```

缺少商品 ID 或数量小于 1 时返回 HTTP 400；商品不存在时返回 HTTP 404；
商品服务无法访问时返回 HTTP 503。价格和总金额使用保留两位小数的
`BigDecimal`。订单不持久化。服务间调用使用 Spring `RestClient`，不添加
额外的 HTTP 客户端依赖。

`GET /actuator/health` 供 Docker、Kubernetes 和冒烟测试执行健康检查。

## 配置文件调用关系

根目录 `pom.xml` 声明两个服务模块、Java 21 和 Spring Boot 4.1.1。每个
子模块的 `pom.xml` 继承这些版本，只声明自身需要的依赖。因此执行
`./mvnw clean verify` 会在同一个 Maven Reactor 中构建并测试两个服务。

每个服务的 `Dockerfile` 复制 Maven 在对应 `<service>/target/` 目录生成的
JAR。因此，Docker 镜像构建依赖 Maven package 阶段的产物。

每个服务的 `application.yml` 提供本地默认配置。订单服务读取
`PRODUCT_SERVICE_URL`，默认值为 `http://localhost:8081`。
`docker-compose.yml` 将其覆盖为 `http://product-service:8081`，这里的
`product-service` 是 Compose 服务名。Kubernetes Deployment 设置相同的
地址，此时 `product-service` 是 Kubernetes Service 的 DNS 名称。

Kubernetes base 保存与环境无关的 Deployment 和 Service。`test` 与
`production` overlay 分别设置命名空间、副本数、镜像标签和环境标签。
Jenkins 对目标 overlay 执行 `kubectl apply -k`。

## 本地开发流程

README 按抽象层级从低到高介绍配置调用链：

1. `./mvnw clean verify` 编译并测试两个服务。
2. `./mvnw package` 生成两个可执行 JAR。
3. 直接运行两个 JAR，演示本地应用配置。
4. `docker compose up --build` 演示 Docker 镜像构建、容器 DNS 和服务间调用。
5. `kubectl apply -k k8s/overlays/test` 演示在 Kubernetes 中使用相同的镜像和
   环境变量。

## 自动触发的测试环境流水线

`jenkins/Jenkinsfile.test` 配置在 Jenkins Pipeline Job 中，其 SCM 分支为
`develop`，并使用 GitHub Webhook 触发器。

每次向 `develop` 分支推送代码后，Jenkins 执行：

1. 检出 GitHub 通知中对应的准确 Git 提交。
2. 运行 `./mvnw clean verify`。
3. 构建两个 Docker 镜像，并以短 Git 提交 SHA 作为标签。
4. 使用 Jenkins 中保存的用户名/密码类型凭证登录 GHCR。
5. 推送两个镜像：
   - `ghcr.io/helloworld250902-crypto/product-service:<git-sha>`
   - `ghcr.io/helloworld250902-crypto/order-service:<git-sha>`
6. 将 test overlay 的镜像替换为该不可变标签，但不提交临时替换结果。
7. 使用测试环境 kubeconfig 凭证应用 `k8s/overlays/test`。
8. 等待两个 Deployment 完成滚动发布。
9. 对测试环境执行冒烟测试。

测试、镜像构建、镜像推送、滚动发布或冒烟测试中的任何一步失败，都会终止
流水线，并阻止后续阶段运行。

## 手动触发的生产环境流水线

`jenkins/Jenkinsfile.production` 不配置自动触发器。它通过 Jenkins 的
`Build with Parameters` 提供两个参数：

- `IMAGE_TAG`：已经通过测试环境流水线的 Git SHA 镜像标签。
- `CONFIRM_DEPLOYMENT`：值必须为 `DEPLOY`。

生产环境流水线执行：

1. 校验输入参数。
2. 登录 GHCR，并确认指定标签的两个镜像都存在。
3. 直接使用现有镜像，不重新编译代码，也不重新构建镜像。
4. 使用独立的生产环境 kubeconfig 凭证应用 production overlay。
5. 等待两个 Deployment 完成滚动发布。
6. 对生产环境执行冒烟测试。

生产环境复用同一个不可变镜像标签，确保部署的是已经在测试环境验证过的
制品。

## Jenkins 与 GitHub 配置

README 将说明如何创建两个来自 SCM 的 Pipeline Job：

- `microservices-test`：指向 `jenkins/Jenkinsfile.test`，跟踪 `develop`
  分支，并接收 GitHub Webhook。
- `microservices-production`：指向 `jenkins/Jenkinsfile.production`，跟踪
  `main` 分支，由用户手动启动。

README 将说明以下 Jenkins 凭证 ID：

- `github-container-registry`：GitHub 用户名，以及具有 package 读写权限的
  Token。
- `kubeconfig-test`：测试集群或测试命名空间使用的 secret file。
- `kubeconfig-production`：生产集群或生产命名空间使用的 secret file。

任何凭证值、GitHub Token、kubeconfig、密码或生成的 Secret 都不得提交到
Git。首次发布 GitHub 仓库时，使用 GitHub CLI 浏览器登录或其他交互式认证
方式，由用户根据提示完成授权。

## Kubernetes 运行设计

每个服务包含一个 Deployment 和一个 ClusterIP Service。商品服务的存活及
就绪探针访问 `/actuator/health`。订单服务使用相同的探针地址，并通过环境
变量接收 `PRODUCT_SERVICE_URL`。

test overlay 在 `microservices-test` 命名空间中为每个服务部署一个副本。
production overlay 在 `microservices-production` 命名空间中为每个服务部署
两个副本。由于镜像标签使用不可变的 Git SHA，因此设置
`imagePullPolicy: IfNotPresent`。

两个命名空间都引用在集群外部创建的 `ghcr-pull-secret`。README 提供
`kubectl create secret docker-registry` 命令，但不在 Git 中保存 Secret 内容。
即使 GHCR package 尚未设置为公开，Kubernetes 也能通过该 Secret 拉取镜像。

项目不包含 Ingress。验证时使用 `kubectl port-forward`，或者向冒烟测试脚本
提供可配置的外部基础 URL。

自动 Webhook 示例要求 Jenkins 具有 GitHub 可访问的 HTTPS 地址。README
说明此前提，并展示 Jenkins GitHub Webhook 地址的填写位置，但不指定具体的
内网穿透服务商。

## 测试与验证

自动测试覆盖：

- 商品查询成功及未知商品返回 404。
- 订单请求参数校验。
- 成功创建订单并正确计算总金额。
- 商品不存在错误的传递。
- 商品服务不可用时映射为 HTTP 503。

代码库级别验证包括：

- Maven Reactor 完整测试套件。
- Docker Compose 配置解析。
- Docker 可用时构建镜像。
- 渲染 test 和 production 两套 Kustomize overlay。
- 检查冒烟测试脚本的 Shell 语法。
- 所需运行环境可用时，通过订单服务执行本地端到端请求。

Docker daemon、Kubernetes 集群、镜像仓库登录或网络访问不可用时，必须明确
报告对应验证被跳过，不得将其表述为验证成功。

## 文档要求

README 必须解释每种工具的职责，并包含配置文件调用关系图。README 必须
提供可复制执行的 Maven、直接运行 JAR、Docker Compose、Kubernetes、
Jenkins Job 创建、GitHub Webhook 配置、手动生产发布、凭证配置、故障排查和
清理命令。

README 必须区分提交到 Git 的配置和存储在 Jenkins 或 Kubernetes 中的凭证，
还必须解释自动测试环境部署与手动生产环境发布之间的区别。

## 验收标准

- 初学者能够追踪一次源代码推送如何依次经过 GitHub、Jenkins、Maven、
  Docker、GHCR 和 Kubernetes。
- 在 Java 21 环境执行 `./mvnw clean verify` 能够通过。
- 订单接口能够演示两个服务之间真实的 HTTP 调用。
- Docker Compose 能够启动两个服务，订单请求能够成功完成。
- 两套 Kubernetes overlay 均能渲染，且不存在未替换的占位符。
- 自动测试流水线和手动生产流水线清楚展示不同的触发方式与制品晋级行为。
- 代码库中不包含任何 Secret。
- 最终代码库推送至
  `https://github.com/helloworld250902-crypto/springboot-microservices-cicd-demo`。
