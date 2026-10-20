# Spring Boot 微服务 CI/CD 演示项目实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 创建并发布一个可运行的双服务 Spring Boot 教学仓库，完整演示 Maven 构建、Docker 本地联调、Jenkins 自动/手动流水线、GHCR 镜像晋级和 Kubernetes 测试/生产部署。

**架构：** `order-service` 通过 HTTP 调用内存型 `product-service`；根 Maven Reactor 统一构建两个模块。Docker Compose 和 Kubernetes 只改变 `PRODUCT_SERVICE_URL`，两条 Jenkins 流水线分别负责自动部署测试环境及手动晋级同一镜像到生产环境。

**技术栈：** Java 21、Spring Boot 4.1.1、Maven Wrapper、Docker/Compose、Kubernetes/Kustomize、Jenkins Declarative Pipeline、GitHub/GHCR。

**规格：** `docs/superpowers/specs/2026-10-20-microservices-cicd-demo-design.md`

## 全局约束

- 使用 Java 21 和 Spring Boot 4.1.1，根工程采用 Maven 多模块 Reactor。
- 只实现 `product-service` 和 `order-service`，不增加数据库、消息队列、注册中心、网关、认证或 Helm。
- 订单金额使用保留两位小数的 `BigDecimal`；服务间 HTTP 客户端使用 Spring `RestClient`。
- 本地、Compose、Kubernetes 通过同一个 `PRODUCT_SERVICE_URL` 配置切换调用地址。
- 测试环境由 GitHub Webhook 自动触发；生产环境只能使用参数手动触发，并复用已测试的 Git SHA 镜像。
- GHCR Token、kubeconfig、密码及 Kubernetes Secret 不得进入 Git。
- README 和用户文档使用中文；代码标识符、文件名、命令和协议字段保留英文。
- Docker、Kubernetes、网络或凭证不可用时必须明确报告跳过，不能报告为通过。

## 重点审查项

- `quantity` 为 `null`、`0` 或负数时必须返回 HTTP 400；Task 2 的 `OrderControllerTest` 固化该行为。
- 商品不存在时商品服务及订单服务都返回 HTTP 404；Task 1、Task 2 的控制器测试固化该行为。
- 商品服务连接失败或超时时，订单服务返回 HTTP 503；Task 2 的真实本地 HTTP 客户端测试固化该行为。
- 生产发布缺少镜像标签或确认文字不是 `DEPLOY` 时必须在部署前失败；Task 6 的流水线静态验证和参数校验覆盖该行为。
- 测试与生产必须使用独立命名空间和 kubeconfig 凭证，并引用 `ghcr-pull-secret`；Task 4 的 Kustomize 渲染检查覆盖该行为。

---

### Task 1：Maven 骨架与商品服务

**文件：**
- Create: `.gitignore`
- Create: `pom.xml`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `product-service/pom.xml`
- Create: `order-service/pom.xml`
- Create: `product-service/src/main/java/com/example/product/ProductServiceApplication.java`
- Create: `product-service/src/main/java/com/example/product/Product.java`
- Create: `product-service/src/main/java/com/example/product/ProductCatalog.java`
- Create: `product-service/src/main/java/com/example/product/ProductController.java`
- Create: `product-service/src/main/resources/application.yml`
- Test: `product-service/src/test/java/com/example/product/ProductControllerTest.java`

**接口：**
- Consumes: 无。
- Produces: `Product(Long id, String name, BigDecimal price)`；`ProductCatalog.findById(long): Optional<Product>`；`GET /api/products/{id}`；`GET /actuator/health`；根目录 `./mvnw`。

- [ ] **Step 1：创建仅包含版本、插件、模块和依赖声明的 Maven 骨架**

根 `pom.xml` 固定 Spring Boot `4.1.1`、Java `21`，声明 `product-service`、`order-service` 两个模块；先为尚未实现的 `order-service` 创建最小 `pom.xml`，保证 Reactor 结构完整。生成 Maven Wrapper `3.9.11`，并让 Unix 脚本可执行。

- [ ] **Step 2：先写商品接口失败测试**

在 `ProductControllerTest` 中使用 MockMvc 断言：商品 `1` 返回 200、名称 `Mechanical Keyboard`、价格 `599.00`；商品 `999` 返回 404、`title=Product not found`，且 `detail` 包含 `999`。

- [ ] **Step 3：运行测试并确认因商品代码不存在而失败**

Run: `./mvnw -pl product-service test`
Expected: FAIL，编译错误指向尚未定义的商品应用或控制器类型，而不是测试语法错误。

- [ ] **Step 4：实现商品服务的最小代码与配置**

`ProductCatalog` 使用不可变 Map 保存少量示例商品；`ProductController` 查找商品并在不存在时抛出带 `ProblemDetail` 的 404。`application.yml` 设置 `server.port: 8081`，仅暴露 actuator `health`。

- [ ] **Step 5：运行模块及 Reactor 测试**

Run: `./mvnw -pl product-service test && ./mvnw clean verify`
Expected: 商品测试通过，完整 Reactor 构建成功。

- [ ] **Step 6：提交**

```bash
git add .gitignore pom.xml .mvn mvnw mvnw.cmd product-service order-service/pom.xml
git commit -m "feat: add Maven build and product service"
```

### Task 2：订单服务与真实 HTTP 调用

**文件：**
- Create: `order-service/src/main/java/com/example/order/OrderServiceApplication.java`
- Create: `order-service/src/main/java/com/example/order/OrderRequest.java`
- Create: `order-service/src/main/java/com/example/order/OrderResponse.java`
- Create: `order-service/src/main/java/com/example/order/ProductResponse.java`
- Create: `order-service/src/main/java/com/example/order/ProductClient.java`
- Create: `order-service/src/main/java/com/example/order/RestProductClient.java`
- Create: `order-service/src/main/java/com/example/order/OrderService.java`
- Create: `order-service/src/main/java/com/example/order/OrderController.java`
- Create: `order-service/src/main/java/com/example/order/ApiExceptionHandler.java`
- Create: `order-service/src/main/java/com/example/order/ProductNotFoundException.java`
- Create: `order-service/src/main/java/com/example/order/ProductServiceUnavailableException.java`
- Create: `order-service/src/main/resources/application.yml`
- Test: `order-service/src/test/java/com/example/order/OrderServiceTest.java`
- Test: `order-service/src/test/java/com/example/order/OrderControllerTest.java`
- Test: `order-service/src/test/java/com/example/order/RestProductClientTest.java`

**接口：**
- Consumes: Task 1 的 `GET /api/products/{id}` JSON 合同。
- Produces: `ProductClient.getProduct(long): ProductResponse`；`OrderService.createOrder(OrderRequest): OrderResponse`；`POST /api/orders`；`PRODUCT_SERVICE_URL` 环境变量。

- [ ] **Step 1：先写订单业务失败测试**

`OrderServiceTest.createsOrderAndCalculatesTotal` 使用一个手写 `ProductClient` fake 返回商品 `1`，断言数量 `2` 时单价 `599.00`、总价 `1198.00`、订单 ID 是有效 UUID。

- [ ] **Step 2：运行测试并确认因订单类型不存在而失败**

Run: `./mvnw -pl order-service -Dtest=OrderServiceTest test`
Expected: FAIL，编译错误指向 `OrderService`、请求或响应类型尚未定义。

- [ ] **Step 3：实现订单领域对象和 `OrderService.createOrder`**

使用 record 表示请求、商品响应和订单响应；使用 `BigDecimal.multiply` 后固定两位小数，不增加持久化层。

- [ ] **Step 4：运行订单业务测试并确认通过**

Run: `./mvnw -pl order-service -Dtest=OrderServiceTest test`
Expected: PASS。

- [ ] **Step 5：先写控制器与 HTTP 客户端失败测试**

`OrderControllerTest` 断言成功返回 201，`quantity` 为 `null`、`0`、`-1` 均返回 400，商品不存在映射为 404。`RestProductClientTest` 启动 JDK `HttpServer`，分别验证真实 JSON 解码、远端 404 映射为 `ProductNotFoundException`、关闭测试服务器后连接失败映射为 `ProductServiceUnavailableException`。

- [ ] **Step 6：运行新增测试并确认缺少控制器和客户端实现**

Run: `./mvnw -pl order-service -Dtest=OrderControllerTest,RestProductClientTest test`
Expected: FAIL，原因是目标控制器、异常映射或 `RestProductClient` 尚未实现。

- [ ] **Step 7：实现控制器、`RestClient` 客户端、异常处理和配置**

`OrderRequest` 使用 Jakarta Validation；`application.yml` 设置端口 `8082`，并将 `product-service.base-url` 绑定到 `${PRODUCT_SERVICE_URL:http://localhost:8081}`。404 和连接错误分别转换为规格规定的 404、503 `ProblemDetail`。

- [ ] **Step 8：运行订单模块及完整 Reactor 测试**

Run: `./mvnw -pl order-service test && ./mvnw clean verify`
Expected: 所有订单测试及完整 Reactor 测试通过。

- [ ] **Step 9：提交**

```bash
git add order-service
git commit -m "feat: add order service and product HTTP client"
```

### Task 3：Docker 镜像与 Compose 本地联调

**文件：**
- Create: `.dockerignore`
- Create: `product-service/Dockerfile`
- Create: `order-service/Dockerfile`
- Create: `docker-compose.yml`

**接口：**
- Consumes: Task 1、2 生成的 `product-service/target/*.jar`、`order-service/target/*.jar` 和 `PRODUCT_SERVICE_URL`。
- Produces: `product-service:8081`、`order-service:8082` 两个 Compose 服务及健康检查。

- [ ] **Step 1：在配置不存在时执行解析命令并记录预期失败**

Run: `docker compose config`
Expected: FAIL，提示找不到 Compose 配置文件。

- [ ] **Step 2：创建两个运行时 Dockerfile 和 Compose 配置**

两个 Dockerfile 从 Eclipse Temurin Java 21 JRE 镜像复制对应 JAR，并以非 root 用户运行；Compose 暴露 `8081`、`8082`，通过健康检查控制启动顺序，并向订单容器注入 `PRODUCT_SERVICE_URL=http://product-service:8081`。

- [ ] **Step 3：验证配置、构建并执行本地端到端调用**

Run: `./mvnw package && docker compose config && docker compose build && docker compose up -d`
Then: `curl --fail http://localhost:8081/actuator/health` 和向 `http://localhost:8082/api/orders` 提交规格中的 JSON。
Expected: 两个健康检查为 UP，订单请求返回 201 和 `totalPrice: 1198.00`。完成后运行 `docker compose down`。若 Docker daemon 不可用，明确记录 Docker 验证跳过。

- [ ] **Step 4：提交**

```bash
git add .dockerignore product-service/Dockerfile order-service/Dockerfile docker-compose.yml
git commit -m "build: add Docker images and local composition"
```

### Task 4：Kubernetes base 与环境 overlay

**文件：**
- Create: `k8s/base/kustomization.yml`
- Create: `k8s/base/product-deployment.yml`
- Create: `k8s/base/product-service.yml`
- Create: `k8s/base/order-deployment.yml`
- Create: `k8s/base/order-service.yml`
- Create: `k8s/overlays/test/kustomization.yml`
- Create: `k8s/overlays/test/namespace.yml`
- Create: `k8s/overlays/test/replicas.yml`
- Create: `k8s/overlays/production/kustomization.yml`
- Create: `k8s/overlays/production/namespace.yml`
- Create: `k8s/overlays/production/replicas.yml`

**接口：**
- Consumes: Task 3 的镜像名、端口、健康端点和 `PRODUCT_SERVICE_URL`。
- Produces: `microservices-test` 一副本部署、`microservices-production` 两副本部署，以及两个环境中的 `product-service`/`order-service` DNS。

- [ ] **Step 1：在清单不存在时执行 Kustomize 渲染并确认失败**

Run: `kubectl kustomize k8s/overlays/test`
Expected: FAIL，提示 overlay 路径或 `kustomization.yml` 不存在。

- [ ] **Step 2：创建 base 和两个 overlay**

base 使用 GHCR 完整镜像名、ClusterIP Service、actuator 存活/就绪探针、资源 requests/limits、`ghcr-pull-secret` 和订单服务环境变量。test、production 分别设置命名空间、标签、镜像初始标签 `test`/`production` 和副本数 1/2。

- [ ] **Step 3：渲染并检查两个环境**

Run: `kubectl kustomize k8s/overlays/test > /tmp/microservices-test.yml` 和 `kubectl kustomize k8s/overlays/production > /tmp/microservices-production.yml`。
Expected: 两条命令成功；输出分别只包含正确命名空间、副本数和镜像标签，并都包含 `ghcr-pull-secret`、`PRODUCT_SERVICE_URL=http://product-service:8081`。若无 `kubectl`，明确记录验证跳过。

- [ ] **Step 4：提交**

```bash
git add k8s
git commit -m "deploy: add Kubernetes test and production overlays"
```

### Task 5：可复用冒烟测试

**文件：**
- Create: `scripts/smoke-test.sh`
- Test: `scripts/SmokeTestServer.java`
- Test: `scripts/smoke-test-self-test.sh`

**接口：**
- Consumes: 环境变量 `ORDER_SERVICE_URL`，默认 `http://localhost:8082`。
- Produces: 对订单服务健康端点和订单创建端点的可重复验证，任一检查失败时返回非零退出码。

- [ ] **Step 1：先写失败的脚本自测**

`SmokeTestServer.java` 使用 JDK `HttpServer` 在参数指定的端口提供固定的 `/actuator/health` 和 `/api/orders` 响应。`smoke-test-self-test.sh` 启动该单文件 Java 程序，断言成功响应时脚本返回 0，再关闭服务器并断言无法连接时脚本返回非零；目标脚本不存在时自测必须先失败。

- [ ] **Step 2：运行 Shell 语法及自测并确认目标脚本缺失**

Run: `bash -n scripts/smoke-test-self-test.sh && bash scripts/smoke-test-self-test.sh`
Expected: FAIL，提示 `scripts/smoke-test.sh` 不存在。

- [ ] **Step 3：实现最小冒烟测试脚本**

脚本使用 `curl --fail-with-body` 检查 `/actuator/health` 并提交规格订单 JSON；使用 Shell trap 清理临时文件，不依赖 `jq`。

- [ ] **Step 4：运行脚本语法、自测和当前可用环境的真实冒烟测试**

Run: `bash -n scripts/*.sh && bash scripts/smoke-test-self-test.sh`。
Expected: 自测通过；若 Compose 正在运行，再执行 `scripts/smoke-test.sh` 并期望通过。

- [ ] **Step 5：提交**

```bash
git add scripts
git commit -m "test: add reusable service smoke test"
```

### Task 6：Jenkins 自动测试与手动生产流水线

**文件：**
- Create: `jenkins/Jenkinsfile.test`
- Create: `jenkins/Jenkinsfile.production`
- Create: `scripts/verify-pipelines.sh`

**接口：**
- Consumes: Task 1 的 Maven Wrapper、Task 3 的 Dockerfile、Task 4 的 overlay、Task 5 的冒烟脚本，以及 Jenkins 凭证 ID `github-container-registry`、`kubeconfig-test`、`kubeconfig-production`。
- Produces: GitHub push 自动测试部署和 `IMAGE_TAG`/`CONFIRM_DEPLOYMENT` 参数化生产晋级。

- [ ] **Step 1：先写流水线静态契约检查并确认失败**

`verify-pipelines.sh` 检查 test 流水线包含 `githubPush()`、Maven verify、两个镜像 build/push、`kubeconfig-test`、test overlay 和 smoke test；检查 production 流水线不包含 trigger，包含两个参数、`CONFIRM_DEPLOYMENT == 'DEPLOY'`、两个 `docker manifest inspect`、`kubeconfig-production`、production overlay，并且不包含 `docker build` 或 Maven package。

Run: `bash scripts/verify-pipelines.sh`
Expected: FAIL，提示两个 Jenkinsfile 尚不存在。

- [ ] **Step 2：实现 `Jenkinsfile.test`**

使用 Git 短 SHA 作为不可变标签；顺序为 checkout、Maven verify、GHCR 登录、两个镜像构建和推送、临时替换 test 渲染结果中的标签、apply、rollout status、port-forward、smoke test。所有凭证通过 `withCredentials` 注入并在 finally/post 中清理登录和后台进程。

- [ ] **Step 3：实现 `Jenkinsfile.production`**

无 `triggers`；校验 `IMAGE_TAG` 符合 7 至 40 位十六进制 Git SHA 且确认文字严格为 `DEPLOY`；只验证并部署现有 GHCR 镜像，不执行编译或构建；使用独立生产 kubeconfig。

- [ ] **Step 4：验证流水线契约和 Groovy 基本结构**

Run: `bash -n scripts/verify-pipelines.sh && bash scripts/verify-pipelines.sh`
Expected: PASS。若可访问 Jenkins Declarative Pipeline Linter，再提交两个文件并记录结果；否则明确记录远程 Jenkins 语义验证跳过。

- [ ] **Step 5：提交**

```bash
git add jenkins scripts/verify-pipelines.sh
git commit -m "ci: add automatic test and manual production pipelines"
```

### Task 7：中文操作文档、整体验证与 GitHub 发布

**文件：**
- Create: `README.md`
- Create: `docs/configuration-flow.md`
- Modify: `.gitignore`

**接口：**
- Consumes: Task 1 至 Task 6 的所有命令、文件名、端口、环境变量和凭证 ID。
- Produces: 从 GitHub 到 Jenkins、Maven、Docker、GHCR、Kubernetes 的中文学习路径，以及公开 GitHub 仓库。

- [ ] **Step 1：编写 README 和配置调用关系说明**

README 覆盖工具职责、先决条件、Maven/JAR/Compose/K8s 操作、GHCR Token、两个 `ghcr-pull-secret`、两个 Jenkins Job、GitHub Webhook、手动生产参数、验证、故障排查和清理。`docs/configuration-flow.md` 用 Mermaid 展示文件调用链和自动/手动两条时序，不重复大段操作命令。

- [ ] **Step 2：检查文档引用和 Secret 泄漏**

Run: `rg -o '`[^`]+`' README.md docs/configuration-flow.md` 辅助核对文件引用；运行 `git grep -nEi '(ghp_|github_pat_|password\s*[:=]|token\s*[:=])' -- ':!docs/superpowers/**'`。
Expected: 所有引用文件存在；Secret 扫描只有说明性变量名，没有真实凭证。

- [ ] **Step 3：执行完整验证矩阵**

Run: `./mvnw clean verify`、`bash -n scripts/*.sh`、`bash scripts/smoke-test-self-test.sh`、`bash scripts/verify-pipelines.sh`、`docker compose config`、两个 `kubectl kustomize`。Docker 镜像构建和 Compose 端到端测试在 daemon 可用时执行；集群部署只在明确可用且不会影响其他资源时执行。
Expected: 可执行项目全部通过；不可用的外部环境逐项列为 SKIP 并说明原因。

- [ ] **Step 4：提交文档及验证修订**

```bash
git add README.md docs/configuration-flow.md .gitignore
git commit -m "docs: explain the complete CI/CD workflow"
```

- [ ] **Step 5：创建并推送 GitHub 仓库**

先运行 `gh auth status`；若未认证，暂停并让用户完成 `gh auth login --web`。检查目标仓库是否存在；不存在则创建 public 仓库，存在则只在确认是目标空仓库后连接。设置 `origin`，推送 `main`，创建并推送 `develop`，不强制覆盖任何远端历史。

Run: `gh repo view helloworld250902-crypto/springboot-microservices-cicd-demo` 和 `git ls-remote origin`。
Expected: GitHub 仓库可访问，远端 `main`、`develop` 均指向本地预期提交，工作树干净。

- [ ] **Step 6：最终提交记录与远端核验**

Run: `git status --short --branch && git log --oneline --decorate -10 && git ls-remote --heads origin main develop`。
Expected: 工作树干净，本地分支已跟踪远端，GitHub 中存在完整代码和中文 README。
