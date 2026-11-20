FROM eclipse-temurin:27-jdk AS builder
WORKDIR /workspace

COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN chmod +x mvnw

COPY src src
RUN ./mvnw --batch-mode clean package -Dmaven.test.skip=true

FROM eclipse-temurin:27-jre-alpine
WORKDIR /app

RUN addgroup -g 10001 tenantguard && \
    adduser -u 10001 -G tenantguard -s /bin/sh -D tenantguard

COPY --from=builder --chown=10001:10001 /workspace/target/*.jar /app/app.jar

USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
