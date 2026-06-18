# =============================================================================
# Dockerfile — Lexicon Compile Service (eclipse-temurin:21-jre-jammy runtime)
#
# FIXES all three root causes of the Cloud Run startup error:
#
#   Caused by: UnsatisfiedLinkError: PointerPointer.allocateArray(long)
#   Caused by: RuntimeException: No native JavaCPP library in memory
#
# ROOT CAUSE 1 — Wrong architecture (most common on Apple Silicon / ARM CI)
#   JavaCPP bundles platform-specific native .so files inside the fat JAR.
#   If Maven runs on linux/arm64 (default on M1/M2 Mac or ARM CI runners
#   without --platform), the fat JAR gets linux-arm64 native libraries.
#   Cloud Run is always linux/amd64 (x86-64). An arm64 .so throws
#   UnsatisfiedLinkError immediately when the JVM tries to load it.
#   FIX: --platform=linux/amd64 on every FROM + in docker buildx build.
#
# ROOT CAUSE 2 — Missing -Djava.io.tmpdir=/tmp
#   JavaCPP Loader.load() extracts libjavacpp.so from the fat JAR to
#   java.io.tmpdir, then loads it with System.load(). Cloud Run has a
#   read-only root filesystem — only /tmp is writable. Without this flag
#   extraction fails and the library is never loaded.
#   FIX: -Djava.io.tmpdir=/tmp in JAVA_TOOL_OPTIONS.
#
# ROOT CAUSE 3 — Missing libgomp1 (GNU OpenMP)
#   Hyperscan's native .so dynamically links libgomp.so.1 for parallel
#   pattern scanning. eclipse-temurin:21-jre-jammy does NOT include it.
#   libstdc++6 IS already present (JRE dependency on Ubuntu 22.04).
#   FIX: apt-get install libgomp1 inside Docker.
#
# BUILD:
#   docker buildx build \
#     --platform linux/amd64 \
#     --push \
#     -t gcr.io/<PROJECT_ID>/lexicon-compile-service:latest \
#     .
# =============================================================================


# ── Stage 1: Maven build ──────────────────────────────────────────────────────
# --platform=linux/amd64 forces Maven JVM to x86-64, so the JavaCPP platform
# resolver downloads linux-x86_64 native JARs into the fat JAR.
# Without this on an ARM host it picks linux-aarch64 — which Cloud Run rejects.
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-21 AS maven-builder

WORKDIR /build

COPY pom.xml ./
RUN mvn dependency:go-offline --no-transfer-progress --quiet

COPY src ./src
RUN mvn package --no-transfer-progress -DskipTests && \
    cp target/lexicon-compile-service-*.jar target/app.jar && \
    echo "JAR built:" && ls -lh target/app.jar


# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# Repeat --platform=linux/amd64 — each FROM has its own independent platform.
FROM --platform=linux/amd64 eclipse-temurin:21-jre-jammy

# Install libgomp1 — GNU OpenMP runtime, required by Hyperscan's native .so.
# eclipse-temurin:21-jre-jammy does NOT include it (it is not a JRE dependency).
# libstdc++6 is already present; no need to install it separately.
# This apt-get runs inside Docker build, not on the runner itself — it works
# even when apt-get is restricted on the GitHub Actions runner.
RUN apt-get update && \
    apt-get install -y --no-install-recommends libgomp1 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=maven-builder /build/target/app.jar app.jar

EXPOSE 8080

# MANDATORY JVM FLAGS for Cloud Run + Hyperscan
#
# -Djava.io.tmpdir=/tmp
#   Cloud Run's root filesystem is read-only; /tmp is the only writable path.
#   JavaCPP Loader extracts native .so files from inside the fat JAR to this
#   directory at JVM startup before loading them. Without this flag the
#   extraction fails and produces "No native JavaCPP library in memory".
#
# -XX:+UseContainerSupport
#   JVM reads container CPU/memory limits from cgroups, not the host totals.
#
# -XX:MaxRAMPercentage=75.0
#   Cap heap at 75% of the container limit, leaving room for Hyperscan's
#   native heap, JVM overhead, and OS buffers.
#
# --enable-preview
#   Required by Spring Boot 4 on Java 21. Remove for Spring Boot 3.x.
ENV JAVA_TOOL_OPTIONS="\
  -Djava.io.tmpdir=/tmp \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  --enable-preview"

# exec-form ENTRYPOINT — the JVM becomes PID 1 and receives SIGTERM directly.
# Shell-form would make /bin/sh PID 1, causing Cloud Run graceful shutdown
# to be ignored and the JVM to be killed hard after the timeout.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]