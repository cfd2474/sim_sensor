# Sim Sensor server image: the Ktor server plus the built instructor console.
# Build context is the repo root:  docker build -f server/Dockerfile .

# --- Console (static web app) ---------------------------------------------------------------
FROM node:22-alpine AS console
WORKDIR /src/console
COPY console/package.json console/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY console/ ./
RUN npm run build

# --- Server ---------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS server
WORKDIR /src
# The Android module needs an SDK; it is not part of the server image.
ENV SIMSENSOR_SKIP_ANDROID=1
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties VERSION ./
COPY gradle/ gradle/
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon --version
COPY shared/ shared/
COPY server/ server/
RUN ./gradlew --no-daemon :server:installDist

# --- Runtime --------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy
RUN useradd --system --uid 10001 --no-create-home simsensor
WORKDIR /app
COPY --from=server /src/server/build/install/simsensor-server/ /app/
COPY --from=console /src/console/dist/ /app/console/
ENV SIM_PORT=8080 \
    SIM_CONSOLE_DIR=/app/console
USER simsensor
EXPOSE 8080
CMD ["/app/bin/simsensor-server"]
