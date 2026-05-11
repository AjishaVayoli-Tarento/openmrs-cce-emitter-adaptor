# syntax=docker/dockerfile:1
# ---- Stage 1: Build ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

COPY src/ src/
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system appuser && useradd --system --gid appuser --shell /bin/false appuser \
    && mkdir -p /app/data && chown -R appuser:appuser /app
WORKDIR /app

COPY --from=build /workspace/build/libs/openmrs-cce-emitter-adaptor.jar app.jar

USER appuser
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
