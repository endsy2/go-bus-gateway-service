# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM gradle:8.5-jdk17 AS build
WORKDIR /app

# Copy gradle wrapper and root build files from backend directory
COPY backend/gradlew gradlew
COPY backend/gradle gradle
COPY backend/build.gradle build.gradle
COPY backend/settings.gradle settings.gradle

# Copy gateway-service specific files
COPY backend/gateway-service/build.gradle gateway-service/build.gradle
COPY backend/gateway-service/src gateway-service/src

# Build the service
RUN ./gradlew :gateway-service:build -x test --no-daemon

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl

COPY --from=build /app/gateway-service/build/libs/*.jar app.jar

# Railway provides PORT environment variable
ENV PORT=8080

EXPOSE ${PORT}

# Use Railway's PORT variable and set memory limits
ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT} -Xmx512m -Xms256m -jar app.jar"]
