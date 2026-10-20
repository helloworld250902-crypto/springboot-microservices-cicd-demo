FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app app

WORKDIR /app
COPY product-service/target/product-service-*.jar app.jar

USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
