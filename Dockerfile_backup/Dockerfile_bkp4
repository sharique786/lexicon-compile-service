# =============================================================================
# Dockerfile — Lexicon Compile Service (Chainguard variant)
# Use this if cgr.dev/chainguard is accessible in your organisation's registry.
#
# Chainguard JRE:
#   - Wolfi OS base (glibc, apk) — same package manager as 21-wolfi
#   - Zero-CVE design: Chainguard patches within hours of disclosure
#   - Minimal OS footprint — only JRE + required runtime libs, nothing else
#   - Regularly scored 0 CVEs by Trivy / JFrog Xray
#   - apk-compatible → works with your org's existing apk mirror
#
# If cgr.dev is not accessible: use Dockerfile.debian12 instead.
# =============================================================================
#If maven:3.9-eclipse-temurin-21 is not reachable from your GitHub Actions runner, replace Stage 1 entirely with Wolfi
#FROM cgr.dev/chainguard/wolfi-base:latest AS maven-builder
#RUN apk update && apk add --no-cache openjdk-21-jdk maven
#ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
#WORKDIR /build
#COPY pom.xml ./
#RUN mvn dependency:go-offline --no-transfer-progress --quiet
#COPY src ./src
#RUN mvn package --no-transfer-progress -DskipTests && \
#    cp target/lexicon-compile-service-*.jar target/app.jar


# ── Stage 1: Maven build ──────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS maven-builder

WORKDIR /build
COPY pom.xml ./
RUN mvn dependency:go-offline --no-transfer-progress --quiet

COPY src ./src
RUN mvn package --no-transfer-progress -DskipTests && \
    cp target/lexicon-compile-service-*.jar target/app.jar

# ── Stage 2: Native lib extractor (wolfi / apk) ───────────────────────────────
FROM 21-wolfi AS lib-extractor

RUN apk upgrade --no-cache && \
    apk add --no-cache libstdc++ libgomp && \
    mkdir -p /extracted-libs && \
    cp -L /usr/lib/libstdc++.so.6 /extracted-libs/ && \
    cp -L /usr/lib/libgomp.so.1   /extracted-libs/ && \
    file /extracted-libs/libstdc++.so.6 | grep -q "ELF 64-bit" && \
    file /extracted-libs/libgomp.so.1   | grep -q "ELF 64-bit" && \
    echo "Both native libs verified"

# ── Stage 3: Final runtime — Chainguard JRE ───────────────────────────────────
# cgr.dev/chainguard/jre:openjdk-21
#   - Wolfi OS: glibc 2.39+, apk package manager
#   - Zero-CVE design (0 known vulnerabilities at build time)
#   - Chainguard releases security patches within hours of disclosure
#   - Runs as nonroot by default
#   - Compatible with Hyperscan native .so (glibc-based, same as Wolfi libs)
#
# ABI compatibility note: since both the extractor (21-wolfi) and the final
# stage (chainguard, also Wolfi) use the same glibc lineage, the .so files
# copied from Stage 2 are guaranteed ABI-compatible.
FROM cgr.dev/chainguard/jre:openjdk-21

COPY --from=lib-extractor /extracted-libs/libstdc++.so.6 /app/native-libs/libstdc++.so.6
COPY --from=lib-extractor /extracted-libs/libgomp.so.1   /app/native-libs/libgomp.so.1
COPY --from=maven-builder /build/target/app.jar /app/app.jar

EXPOSE 8080

ENV LD_LIBRARY_PATH=/app/native-libs

ENV JAVA_TOOL_OPTIONS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  --enable-preview \
  -Djava.io.tmpdir=/tmp \
  -Djava.library.path=/app/native-libs"

# Chainguard uses exec-form ENTRYPOINT (no shell available)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]