# Dockerfile
# Build multi-stage: compila o fat-jar e roda em imagem JRE leve

# Estagio 1: build com Gradle
FROM gradle:8.10-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle buildFatJar --no-daemon

# Estagio 2: runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/lifeforge-backend.jar /app/lifeforge-backend.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/lifeforge-backend.jar", "-config=application.conf"]
