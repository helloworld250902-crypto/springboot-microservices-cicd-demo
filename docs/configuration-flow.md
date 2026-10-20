# 配置文件调用关系

本文只解释“谁调用谁”。具体命令和凭证配置见根目录 `README.md`。

## 从源码到运行环境

```mermaid
flowchart LR
    GH[GitHub main / develop] -->|Webhook| JT[Jenkinsfile.test]
    GH -->|手动 Build with Parameters| JP[Jenkinsfile.production]

    POM[根 pom.xml] --> PM[product-service/pom.xml]
    POM --> OM[order-service/pom.xml]
    JT -->|./mvnw clean verify| POM

    PM --> PJ[product-service target JAR]
    OM --> OJ[order-service target JAR]
    PJ --> PDF[product-service/Dockerfile]
    OJ --> ODF[order-service/Dockerfile]
    JT --> PDF
    JT --> ODF

    PDF --> PI[GHCR product-service:GitSHA]
    ODF --> OI[GHCR order-service:GitSHA]
    PI --> KT[k8s test overlay]
    OI --> KT
    PI --> KP[k8s production overlay]
    OI --> KP

    JT -->|kubeconfig-test| KT
    JP -->|验证既有镜像 + kubeconfig-production| KP
    KT --> ST[scripts/smoke-test.sh]
    KP --> ST
```

## 本地配置调用链

```mermaid
flowchart TD
    MW[mvnw] --> ROOT[pom.xml]
    ROOT --> PRODUCT[product-service/pom.xml]
    ROOT --> ORDER[order-service/pom.xml]
    PRODUCT --> PCFG[product-service/application.yml]
    ORDER --> OCFG[order-service/application.yml]

    COMPOSE[docker-compose.yml] --> PDF[product-service/Dockerfile]
    COMPOSE --> ODF[order-service/Dockerfile]
    COMPOSE -->|PRODUCT_SERVICE_URL| OCFG

    KBASE[k8s/base] --> KTEST[k8s/overlays/test]
    KBASE --> KPROD[k8s/overlays/production]
    KBASE -->|PRODUCT_SERVICE_URL| OCFG
```

关键边界只有一个：订单服务从 `PRODUCT_SERVICE_URL` 读取产品服务地址。本地
直接运行时是 `localhost:8081`；Compose 和 Kubernetes 内部都是
`product-service:8081`。

## 自动测试环境时序

```mermaid
sequenceDiagram
    participant Dev as 开发者
    participant GitHub
    participant Jenkins as Jenkins test Job
    participant GHCR
    participant K8s as microservices-test

    Dev->>GitHub: push develop
    GitHub->>Jenkins: Push Webhook
    Jenkins->>Jenkins: checkout + Maven verify
    Jenkins->>Jenkins: 构建两个 Docker 镜像
    Jenkins->>GHCR: 推送两个 Git SHA 标签
    Jenkins->>K8s: 渲染 test overlay 并 apply
    Jenkins->>K8s: 等待两个 rollout
    Jenkins->>K8s: port-forward + smoke-test.sh
```

任何一步失败，后续阶段不会继续；失败提交不会进入测试环境的“已验证制品”集合。

## 手动生产环境时序

```mermaid
sequenceDiagram
    participant Operator as 发布人员
    participant Jenkins as Jenkins production Job
    participant GHCR
    participant K8s as microservices-production

    Operator->>Jenkins: IMAGE_TAG + CONFIRM_DEPLOYMENT=DEPLOY
    Jenkins->>Jenkins: 校验 SHA 和确认文字
    Jenkins->>GHCR: manifest inspect 两个既有镜像
    Note over Jenkins: 不运行 Maven，不构建新镜像
    Jenkins->>K8s: 用相同 SHA 渲染 production overlay
    Jenkins->>K8s: apply + 等待两个 rollout
    Jenkins->>K8s: port-forward + smoke-test.sh
```

## 凭证在哪里生效

| 凭证 ID | 读取者 | 用途 | 不应出现的位置 |
| --- | --- | --- | --- |
| `github-container-registry` | 两份 Jenkinsfile | 登录 GHCR、推送或检查镜像 | Git、日志明文、Dockerfile |
| `kubeconfig-test` | `Jenkinsfile.test` | 部署测试 namespace | 生产 Job、Git |
| `kubeconfig-production` | `Jenkinsfile.production` | 部署生产 namespace | 测试 Job、Git |
| `ghcr-pull-secret` | Kubernetes Deployment | Pod 从 GHCR 拉取镜像 | 仓库中的明文 Secret YAML |

Jenkins 的 file/usernamePassword binding 只在对应步骤临时注入；Kubernetes Secret
在集群中预先创建。仓库保存的是凭证名称和调用关系，不保存凭证值。
