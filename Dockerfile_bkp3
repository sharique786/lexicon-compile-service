# =============================================================================
# Dockerfile — Lexicon Compile Service
# Base image : eclipse-temurin:21-jre-jammy  (slim JRE 21, Ubuntu 22.04, ~270 MB)
# No Maven   : JAR must be pre-built before docker build
#              Run:  mvn package -DskipTests
#              Then: docker build -t lexicon-compile-service .
#
# Why eclipse-temurin:21-jre-jammy (not Alpine)?
#   com.gliwka.hyperscan 5.4.0-2.0.0 bundles a native .so linked against
#   glibc. Alpine uses musl-libc — the bundled .so would fail to load.
#   eclipse-temurin:21-jre-jammy is glibc-based and loads the native library
#   correctly, while still being a minimal JRE-only image (no JDK, no compiler,
#   no Maven).
# =============================================================================

FROM eclipse-temurin:21-jre-jammy

# ── Runtime dependencies ──────────────────────────────────────────────────────
# Required by com.gliwka.hyperscan native library (extracted from the JAR to
# java.io.tmpdir at JVM startup — no manual .so deployment needed):
#   libstdc++6  → C++ standard library  (Hyperscan is written in C++)
#   libgomp1    → OpenMP               (used internally by Hyperscan)
#   curl        → used by HEALTHCHECK below
RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends \
        libstdc++6 \
        libgomp1 \
        curl \
    && rm -rf /var/lib/apt/lists/*

# ── Non-root user ─────────────────────────────────────────────────────────────
RUN groupadd -r appuser && useradd -r -g appuser -d /app appuser
WORKDIR /app

# ── Copy pre-built JAR ────────────────────────────────────────────────────────
# Build the JAR on the host before running docker build:
#   mvn package -DskipTests
# The wildcard matches the versioned filename (e.g. lexicon-compile-service-1.0.0-SNAPSHOT.jar)
COPY target/lexicon-compile-service-*.jar app.jar

RUN chown appuser:appuser app.jar
USER appuser

# ── Configuration ─────────────────────────────────────────────────────────────
ENV PORT=8080

# JVM flags for JDK 21 running in a container (Cloud Run / Docker / K8s):
#   UseContainerSupport      — reads cgroup CPU/memory limits (not host values)
#   MaxRAMPercentage=75.0    — use 75% of container memory for JVM heap
#   ExitOnOutOfMemoryError   — crash fast so the orchestrator can restart
#   UseG1GC                  — best general-purpose GC for JDK 21
#   --enable-preview         — required: project uses JDK 21 preview features
#                              (sealed interfaces, pattern matching switch, etc.)
#   spring.profiles.active   — activates Cloud Run profile in application.yml
ENV JAVA_TOOL_OPTIONS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+UseG1GC \
  --enable-preview \
  -Dspring.profiles.active=cloud-run"

EXPOSE ${PORT}

# Spring Boot fat JAR is self-contained — java -jar reads Main-Class from
# MANIFEST.MF (set to org.springframework.boot.loader.launch.JarLauncher by
# the spring-boot-maven-plugin during mvn package).
ENTRYPOINT ["java", "-jar", "app.jar"]

# Health check for local docker run / docker-compose testing.
# Cloud Run uses /actuator/health via its own health probes (configured separately).
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:${PORT}/actuator/health || exit 1