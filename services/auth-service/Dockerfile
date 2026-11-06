# Stage 1: Build
FROM eclipse-temurin:27-jdk AS builder
WORKDIR /workspace

COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN chmod +x mvnw

COPY src src
RUN ./mvnw clean package -Dmaven.test.skip=true -B

# Stage 2: Minimal non-root runtime
FROM eclipse-temurin:27-jre-alpine
WORKDIR /app

# Run as non-root UID 10001 per documented security architecture
RUN addgroup -g 10001 tenantguard && \
    adduser -u 10001 -G tenantguard -s /bin/sh -D tenantguard

COPY --from=builder --chown=10001:10001 /workspace/target/*.jar /app/app.jar

USER 10001:10001

EXPOSE 8081 9090

# Tuning JVM flags for container environments
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]
