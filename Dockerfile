# Stage 1: Build Frontend
FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package.json ./
RUN npm install
COPY frontend ./
RUN npm run build

# Stage 2: Build Backend JAR
FROM maven:3.9-eclipse-temurin-21-alpine AS backend-builder
WORKDIR /app/backend
COPY backend/pom.xml ./
COPY backend/src ./src
# Copy frontend build into backend static resources
COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static
RUN mvn clean package -DskipTests -B

# Stage 3: Hardened Non-Root Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Create non-root system group and user
RUN addgroup -S ledger && adduser -S ledger -G ledger

# Copy fat JAR and assign ownership to non-root user
COPY --from=backend-builder --chown=ledger:ledger /app/backend/target/*.jar /app/ledgerline.jar

USER ledger:ledger

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/ledgerline.jar"]

