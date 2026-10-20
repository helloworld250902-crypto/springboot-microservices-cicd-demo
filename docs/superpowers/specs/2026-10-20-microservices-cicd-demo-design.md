# Spring Boot Microservices CI/CD Demo Design

## Purpose

Build a small, runnable teaching repository that shows how Spring Boot, Maven,
Docker, Jenkins, Kubernetes, and GitHub participate in one delivery flow. The
repository must make the call chain between configuration files explicit and
must demonstrate both an automatically triggered test deployment and a
manually triggered production deployment.

The repository will be published as the public GitHub repository
`helloworld250902-crypto/springboot-microservices-cicd-demo`.

## Scope

The demo contains two Spring Boot services:

- `product-service` owns an in-memory product catalog and exposes product
  lookup endpoints.
- `order-service` accepts an order request, calls `product-service`, and returns
  an order summary containing the selected product and calculated total.

The focus is the delivery flow, so the demo deliberately excludes databases,
message brokers, service registries, API gateways, authentication, Helm, and
cloud-specific Kubernetes services.

## Technology Baseline

- Java 21.
- Spring Boot 4.1.1.
- Maven Wrapper and a Maven multi-module reactor build.
- Docker images based on an Eclipse Temurin Java 21 runtime image.
- Docker Compose for local container integration.
- Plain Kubernetes resources organized with Kustomize bases and overlays.
- Jenkins declarative pipelines.
- GitHub Container Registry (GHCR) for images.

## Repository Structure

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

## Service Contracts

### Product service

`GET /api/products/{id}` returns an existing product:

```json
{
  "id": 1,
  "name": "Mechanical Keyboard",
  "price": 599.00
}
```

An unknown product ID returns HTTP 404 using Spring `ProblemDetail`, with
`title` set to `Product not found` and `detail` identifying the requested ID.

`GET /actuator/health` is used by Docker, Kubernetes, and smoke tests.

### Order service

`POST /api/orders` accepts:

```json
{
  "productId": 1,
  "quantity": 2
}
```

It calls `GET {product-service-url}/api/products/{id}` and returns HTTP 201:

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

The request rejects a missing product ID or a quantity below one with HTTP
400. A missing product returns HTTP 404. An unreachable product service returns
HTTP 503. Prices and totals use `BigDecimal` with two decimal places. Orders
are not persisted. The inter-service client uses Spring `RestClient` without
an additional HTTP-client dependency.

`GET /actuator/health` is used by Docker, Kubernetes, and smoke tests.

## Configuration Call Chain

The root `pom.xml` declares both service modules, Java 21, and Spring Boot
4.1.1. Each child `pom.xml` inherits those versions and declares only its own
dependencies. Running `./mvnw clean verify` therefore builds and tests both
services in one Maven reactor.

Each service `Dockerfile` copies the corresponding JAR created under
`<service>/target/`. Docker image creation is therefore downstream of the
Maven package phase.

Each service `application.yml` supplies a local default. The order service
reads `PRODUCT_SERVICE_URL`, defaulting to `http://localhost:8081`.
`docker-compose.yml` overrides that variable with
`http://product-service:8081`, where `product-service` is the Compose service
name. Kubernetes Deployments set the same variable to
`http://product-service:8081`, where `product-service` is the Kubernetes
Service DNS name.

The Kubernetes base contains namespace-independent Deployments and Services.
The `test` and `production` overlays select their namespace, replica count,
image tag, and environment-specific labels. Jenkins invokes
`kubectl apply -k` against the chosen overlay.

## Local Developer Flow

The README presents the configuration chain in increasing order of
abstraction:

1. `./mvnw clean verify` compiles and tests both services.
2. `./mvnw package` produces both executable JARs.
3. Running both JARs demonstrates local application configuration.
4. `docker compose up --build` demonstrates Docker image construction,
   container DNS, and service-to-service communication.
5. `kubectl apply -k k8s/overlays/test` demonstrates the same images and
   environment variables under Kubernetes.

## Automatic Test Pipeline

`jenkins/Jenkinsfile.test` is configured in a Jenkins Pipeline job whose SCM
branch is `develop`. The job uses the GitHub webhook trigger.

On every push to `develop`, Jenkins:

1. Checks out the exact Git commit delivered by GitHub.
2. Runs `./mvnw clean verify`.
3. Builds both images tagged with the short Git commit SHA.
4. Logs in to GHCR through a Jenkins username/password credential.
5. Pushes both images to:
   - `ghcr.io/helloworld250902-crypto/product-service:<git-sha>`
   - `ghcr.io/helloworld250902-crypto/order-service:<git-sha>`
6. Updates the test overlay to use that immutable tag without committing the
   generated substitution.
7. Applies `k8s/overlays/test` using a test kubeconfig credential.
8. Waits for both Deployments to complete their rollout.
9. Runs the smoke test against the test environment.

Any failed test, image build, image push, rollout, or smoke check stops the
pipeline and prevents subsequent stages.

## Manual Production Pipeline

`jenkins/Jenkinsfile.production` has no automatic trigger. It exposes two
parameters through Jenkins `Build with Parameters`:

- `IMAGE_TAG`: a Git SHA image tag that already passed the test pipeline.
- `CONFIRM_DEPLOYMENT`: must equal `DEPLOY`.

The production pipeline:

1. Validates the parameters.
2. Authenticates to GHCR and verifies that both requested images exist.
3. Uses the existing images; it does not compile code or rebuild them.
4. Applies the production Kustomize overlay using a separate production
   kubeconfig credential.
5. Waits for both Deployments to complete their rollout.
6. Runs the smoke test against the production environment.

Reusing the same immutable image tag ensures that production receives the
artifact already tested in the test environment.

## Jenkins and GitHub Setup

The README will show how to create two Pipeline jobs from SCM:

- `microservices-test`: points at `jenkins/Jenkinsfile.test`, tracks
  `develop`, and receives the GitHub webhook.
- `microservices-production`: points at
  `jenkins/Jenkinsfile.production`, tracks `main`, and is started manually.

It will document these Jenkins credential IDs:

- `github-container-registry`: GitHub username plus a token allowed to write
  and read packages.
- `kubeconfig-test`: secret file for the test cluster or namespace.
- `kubeconfig-production`: secret file for the production cluster or
  namespace.

No credential value, GitHub token, kubeconfig, password, or generated secret
may be committed. GitHub authentication during initial repository publication
will use GitHub CLI browser login or another interactive credential mechanism,
with the user completing the prompt.

## Kubernetes Runtime Design

Each service has one Deployment and one ClusterIP Service. Product service
readiness and liveness probes use `/actuator/health`. Order service uses the
same probe endpoint and receives `PRODUCT_SERVICE_URL` as an environment
variable.

The test overlay deploys one replica of each service to namespace
`microservices-test`. The production overlay deploys two replicas of each
service to namespace `microservices-production`. Images use
`imagePullPolicy: IfNotPresent` because tags are immutable Git SHAs.

Both namespaces reference an externally created `ghcr-pull-secret`. The
README supplies the `kubectl create secret docker-registry` commands, but the
secret contents are never stored in Git. This keeps deployment working even
when GHCR packages have not been made public.

Ingress is intentionally excluded. Verification uses `kubectl port-forward`
or a configurable external base URL supplied to the smoke-test script.

The automatic webhook example requires Jenkins to have an HTTPS URL reachable
by GitHub. The README states this prerequisite and shows where to copy the
Jenkins GitHub webhook endpoint; it does not prescribe a tunneling provider.

## Testing and Verification

Automated tests cover:

- Product lookup success and unknown-product 404 behavior.
- Order request validation.
- Successful order creation and total calculation.
- Product-not-found propagation.
- Product-service-unavailable mapping to HTTP 503.

Repository-level checks cover:

- The Maven reactor test suite.
- Docker Compose configuration parsing.
- Docker image builds when Docker is available.
- Kustomize rendering for test and production overlays.
- Shell syntax for the smoke-test script.
- A local end-to-end request through order service when the required runtime is
  available.

A missing Docker daemon, Kubernetes cluster, registry login, or network access
is reported as an explicit skipped verification rather than a successful one.

## Documentation Requirements

The README must explain each tool's responsibility and include a file-to-file
call-chain diagram. It must give copyable commands for Maven, direct JAR
execution, Docker Compose, Kubernetes, Jenkins job creation, GitHub webhook
setup, manual production deployment, credential setup, troubleshooting, and
cleanup.

The README must distinguish configuration committed to Git from credentials
stored in Jenkins or Kubernetes. It must also explain how automatic test
deployment differs from manual production promotion.

## Acceptance Criteria

- A new learner can trace a source-code push from GitHub through Jenkins,
  Maven, Docker, GHCR, and Kubernetes.
- `./mvnw clean verify` passes on Java 21.
- The order endpoint demonstrates a real HTTP call between the two services.
- Docker Compose starts both services and the order request succeeds.
- Both Kubernetes overlays render without unresolved placeholders.
- The automatic test Jenkinsfile and manual production Jenkinsfile clearly
  demonstrate their different trigger and artifact-promotion behavior.
- The repository contains no secrets.
- The completed repository is pushed to
  `https://github.com/helloworld250902-crypto/springboot-microservices-cicd-demo`.
