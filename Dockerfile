# =============================================================================
# Dockerfile — Lexicon Compile Service
# Spring Boot 4.0.6 | JDK 21 | com.gliwka.hyperscan 5.4.0-2.0.0
#
# 2-stage build (no native Hyperscan compilation needed):
#   Stage 1: Build Spring Boot 4 layered JAR on JDK 21
#   Stage 2: Minimal JRE 21 runtime
#
# com.gliwka.hyperscan bundles libhyperscan_jni.so inside the JAR.
# It is extracted to /tmp at JVM startup — no manual .so deployment required.
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS maven-builder

WORKDIR /build

# Cache Maven dependencies (invalidated only when pom.xml changes)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q dependency:go-offline

# Build and extract layered JAR
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q package -DskipTests \
    && echo "JAR size:" && ls -lh target/lexicon-compile-service-*.jar

# Extract Spring Boot 4 layers for Docker cache optimisation
RUN java -Djarmode=layertools \
    -jar target/lexicon-compile-service-*.jar \
    extract --destination target/extracted

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# com.gliwka.hyperscan native runtime requirements:
#   libstdc++6 and libgomp1 (Hyperscan C library dependencies).
# No libhyperscan-dev needed — native .so is bundled inside the JAR.
RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends \
        libstdc++6 \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# Non-root user (Cloud Run security best practice)
RUN groupadd -r appuser && useradd -r -g appuser -d /app appuser
WORKDIR /app

# Copy Spring Boot 4 layered content in Docker-cache-friendly order:
#   1. dependencies         → changes rarely (Hyperscan .so lives here)
#   2. spring-boot-loader   → changes rarely
#   3. snapshot-dependencies → changes on SNAPSHOT bumps
#   4. application          → changes every build
COPY --from=maven-builder /build/target/extracted/dependencies/          ./
COPY --from=maven-builder /build/target/extracted/spring-boot-loader/    ./
COPY --from=maven-builder /build/target/extracted/snapshot-dependencies/ ./
COPY --from=maven-builder /build/target/extracted/application/           ./

RUN chown -R appuser:appuser /app
USER appuser

# ── Environment ───────────────────────────────────────────────────────────────
ENV PORT=8080
ENV SPRING_PROFILES_ACTIVE=cloud-run

# JVM tuning for JDK 21 on Cloud Run:
#   UseContainerSupport      — reads cgroup CPU/memory limits
#   MaxRAMPercentage=75.0    — 75% of container RAM for JVM heap
#   ExitOnOutOfMemoryError   — crash fast; Cloud Run restarts
#   UseG1GC                  — best general-purpose GC for JDK 21
#   Virtual threads          — auto-enabled in Spring Boot 4 + Tomcat (no extra flag)
#
# Note: -Djava.library.path NOT needed.
#       com.gliwka.hyperscan extracts its .so to java.io.tmpdir on first use.
ENV JAVA_TOOL_OPTIONS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+UseG1GC \
  --enable-preview \
  -Dspring.profiles.active=cloud-run"

EXPOSE ${PORT}

# Spring Boot 4 layered JAR launcher
# Note: Spring Boot 4 moved JarLauncher to the new module path
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# Health check for local docker-run testing
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:${PORT}/actuator/health || exit 1
