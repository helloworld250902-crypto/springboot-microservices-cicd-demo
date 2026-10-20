# Spring Boot 微服务 CI/CD 完整示例

这是一个可以逐层学习的双微服务项目：先用 Maven 构建 Spring Boot，再用
Docker 封装镜像，用 Kubernetes 部署，最后由 Jenkins 串起 GitHub、GHCR 和两套
环境。测试环境由 GitHub Push 自动触发，生产环境必须手动输入参数触发。

## 一眼看懂各工具的作用

| 工具 | 本项目中的职责 | 主要入口 |
| --- | --- | --- |
| Maven | 管理依赖、编译、测试、打包两个可运行 JAR | 根目录 `pom.xml`、各模块 `pom.xml`、`mvnw` |
| Spring Boot | 实现产品服务和订单服务 | `product-service`、`order-service` |
| Docker | 把 JAR 和 Java 21 运行时封装成不可变镜像 | 两个 `Dockerfile`、`docker-compose.yml` |
| Kubernetes | 运行镜像，提供服务发现、探针、副本和环境隔离 | `k8s/base`、`k8s/overlays` |
| Jenkins | 按规则调用 Maven、Docker、GHCR、Kubernetes 和冒烟脚本 | `jenkins/Jenkinsfile.*` |
| GitHub | 保存代码、发出 Push Webhook、托管 GHCR 镜像 | `main`、`develop`、Packages |

完整的文件调用链和两条时序图见
[`docs/configuration-flow.md`](docs/configuration-flow.md)。

## 项目结构

```text
.
├── pom.xml                         # Maven 父工程，聚合两个模块
├── product-service/                # GET /api/products/{id}，端口 8081
├── order-service/                  # POST /api/orders，端口 8082
├── docker-compose.yml              # 本地双容器编排
├── k8s/
│   ├── base/                       # 环境无关的 Deployment/Service
│   └── overlays/{test,production}/ # 命名空间、标签、镜像标签、副本数
├── jenkins/
│   ├── Jenkinsfile.test            # develop Push 自动发布测试环境
│   └── Jenkinsfile.production      # main 上手动晋级生产环境
└── scripts/                        # 冒烟测试及流水线合同检查
```

服务调用关系：客户端调用订单服务；订单服务读取 `PRODUCT_SERVICE_URL`，通过
HTTP 调用产品服务。Compose 和 Kubernetes 都使用 `product-service` 作为内部
DNS，所以同一份应用代码不需要感知运行平台。

## 先决条件

- JDK 21；项目自带 Maven Wrapper，不要求全局安装 Maven。
- 本地容器演示需要 Docker Engine 或 Docker Desktop，并支持 Compose v2。
- Kubernetes 演示需要 `kubectl`，且当前 context 指向你有权使用的集群。
- Jenkins Agent 需要 JDK 21、Docker CLI/daemon、`kubectl`、`curl` 和 Git。
- Jenkins 建议安装 Pipeline、Git、GitHub、Credentials Binding 插件。

先确认工具：

```bash
java -version
./mvnw -version
docker version
docker compose version
kubectl version --client
```

## 1. Maven：编译、测试与打包

根 `pom.xml` 先加载父级 Spring Boot 版本，再按顺序构建 `product-service` 和
`order-service`。模块 POM 声明 Web、Validation、Actuator 和测试依赖；
`spring-boot-maven-plugin` 把普通 JAR 重打包为可直接运行的 Boot JAR。

```bash
./mvnw clean verify
```

验证通过后会产生：

```text
product-service/target/product-service-1.0.0-SNAPSHOT.jar
order-service/target/order-service-1.0.0-SNAPSHOT.jar
```

直接运行时开两个终端：

```bash
java -jar product-service/target/product-service-1.0.0-SNAPSHOT.jar
```

```bash
PRODUCT_SERVICE_URL=http://localhost:8081 \
java -jar order-service/target/order-service-1.0.0-SNAPSHOT.jar
```

调用接口：

```bash
curl http://localhost:8081/api/products/1
curl --request POST http://localhost:8082/api/orders \
  --header 'Content-Type: application/json' \
  --data '{"productId":1,"quantity":2}'
```

第二个响应应为 HTTP 201，且 `totalPrice` 为 `1198.00`。

## 2. Docker：从 JAR 到本地双容器

Dockerfile 不负责编译；它只复制 Maven 已产生的 JAR。这样本地和 Jenkins 都是
先验证源码，再封装已经通过测试的制品。

```bash
./mvnw clean package
docker compose config
docker compose up --build --detach
bash scripts/smoke-test.sh
docker compose down
```

`docker-compose.yml` 先等待产品服务健康，再启动订单服务，并注入：

```text
PRODUCT_SERVICE_URL=http://product-service:8081
```

## 3. Kubernetes：base 与环境 overlay

`k8s/base` 定义两个 Deployment、两个 ClusterIP Service、Actuator 探针、资源
限制和 `ghcr-pull-secret`。overlay 只表达环境差异：

| 环境 | Namespace | 副本数 | 配置中的初始标签 |
| --- | --- | ---: | --- |
| 测试 | `microservices-test` | 1 | `test` |
| 生产 | `microservices-production` | 2 | `production` |

先只渲染检查，不修改集群：

```bash
kubectl kustomize k8s/overlays/test
kubectl kustomize k8s/overlays/production
```

手动部署示例：

```bash
kubectl apply -k k8s/overlays/test
kubectl -n microservices-test rollout status deployment/product-service
kubectl -n microservices-test rollout status deployment/order-service
```

Jenkins 不会提交临时标签修改，而是渲染 overlay 后把 `test` 或 `production`
替换为同一个不可变 Git SHA，再把渲染结果交给 `kubectl apply`。

## 4. GHCR Token 与 Kubernetes 拉取 Secret

创建 GitHub Token 时按最小权限授权：Jenkins 推送镜像需要 `write:packages`，
拉取需要 `read:packages`；私有仓库可能还需要相应仓库访问权。Token 只保存到
Jenkins Credentials 或当前终端环境，绝不能写入仓库。

在两个命名空间分别创建同名 Secret。下面的占位变量由你在当前终端设置：

```bash
kubectl create namespace microservices-test --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace microservices-production --dry-run=client -o yaml | kubectl apply -f -

kubectl -n microservices-test create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username="$GHCR_USERNAME" \
  --docker-password="$GHCR_TOKEN"

kubectl -n microservices-production create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username="$GHCR_USERNAME" \
  --docker-password="$GHCR_TOKEN"
```

如果 Secret 已存在，应使用你们的 Secret 管理流程更新，不要把导出的 Secret
YAML 提交到 Git。

## 5. Jenkins 凭证

在 **Manage Jenkins → Credentials** 创建：

| ID | 类型 | 内容 |
| --- | --- | --- |
| `github-container-registry` | Username with password | GitHub 用户名 + GHCR Token |
| `kubeconfig-test` | Secret file | 只允许访问测试环境的 kubeconfig |
| `kubeconfig-production` | Secret file | 只允许访问生产环境的 kubeconfig |

Jenkinsfile 只引用凭证 ID；实际用户名、Token、密码和 kubeconfig 都不在 Git 中。

## 6. 自动触发：测试环境 Job

新建 **Pipeline from SCM** Job `microservices-test`：

1. SCM 选择 Git，仓库填写本仓库 GitHub URL。
2. Branch Specifier 填 `*/develop`。
3. Script Path 填 `jenkins/Jenkinsfile.test`。
4. 保存后至少手动运行一次，确认 Agent 工具和凭证正常。
5. GitHub 仓库 **Settings → Webhooks** 添加
   `https://你的-Jenkins地址/github-webhook/`，Content type 选
   `application/json`，事件选 Push。

之后每次 Push 到 `develop`，`githubPush()` 触发以下流程：

```text
checkout → Maven verify → Docker build → GHCR push
         → test overlay + Git SHA → rollout → smoke test
```

镜像标签来自当前提交的短 SHA，例如
`ghcr.io/helloworld250902-crypto/order-service:1a2b3c4d5e6f`。

## 7. 手动触发：生产环境 Job

新建另一个 **Pipeline from SCM** Job `microservices-production`：

1. Branch Specifier 填 `*/main`。
2. Script Path 填 `jenkins/Jenkinsfile.production`。
3. 不配置 GitHub Push 触发器。

点击 **Build with Parameters**，填写：

- `IMAGE_TAG`：已经在测试流水线通过的 7 至 40 位十六进制 Git SHA。
- `CONFIRM_DEPLOYMENT`：必须严格输入 `DEPLOY`。

生产流水线只执行参数校验、两个 `docker manifest inspect`、production overlay
部署、rollout 和冒烟测试。它不会重新运行 Maven，也不会重新构建镜像，因此
生产使用的就是测试环境已经验证过的同一制品。

## 8. 验证命令

```bash
./mvnw clean verify
bash -n scripts/*.sh
bash scripts/smoke-test-self-test.sh
bash scripts/verify-pipelines.sh
docker compose config
kubectl kustomize k8s/overlays/test
kubectl kustomize k8s/overlays/production
```

`scripts/smoke-test.sh` 默认访问 `http://localhost:8082`，也可指定：

```bash
ORDER_SERVICE_URL=https://test.example.com bash scripts/smoke-test.sh
```

## 9. 常见问题

- **订单服务返回 503**：检查 `PRODUCT_SERVICE_URL`、产品服务健康状态和容器或
  集群 DNS；在 Pod 中它应为 `http://product-service:8081`。
- **镜像拉取失败**：确认两个 namespace 都有 `ghcr-pull-secret`，Token 仍有效，
  GHCR package 可访问，Deployment 引用的 SHA 标签存在。
- **Webhook 不触发**：查看 GitHub Webhook Recent Deliveries、Jenkins 公网可达性、
  Job 的 SCM 分支与 `githubPush()` 配置。
- **Jenkins 能构建但不能部署**：分别检查测试/生产 kubeconfig 凭证权限，避免把
  生产 kubeconfig 配给测试 Job。
- **本机 Java 版本错误**：设置 `JAVA_HOME` 指向 JDK 21 后重新执行 Wrapper。
- **端口占用**：停止占用 8081、8082 或 18082 的进程，再重试。

## 10. 清理

```bash
docker compose down --volumes --remove-orphans
kubectl delete -k k8s/overlays/test
kubectl delete -k k8s/overlays/production
```

删除 Kubernetes 资源会影响对应环境，生产环境必须按团队变更流程执行。

## 分支约定

- `develop`：供自动测试环境 Job 跟踪。
- `main`：供手动生产 Job 跟踪。
- 功能改动先进入 `develop`，测试通过后再评审合并到 `main`。

这个仓库只演示完整且最小的交付链路，不包含数据库、服务注册中心、API 网关、
Helm 或云厂商专属服务。
