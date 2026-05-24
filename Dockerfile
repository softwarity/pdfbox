# syntax=docker/dockerfile:1

### Build stage ###########################################################
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package

### CJK font stage ########################################################
# Downloaded at build time so the runtime image works fully offline.
FROM debian:bookworm-slim AS fonts
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /cjk
ARG NOTO_CJK_BASE=https://github.com/notofonts/noto-cjk/raw/main/Sans/SubsetOTF/JP
RUN curl -fsSL -o NotoSansJP-Regular.otf "${NOTO_CJK_BASE}/NotoSansJP-Regular.otf" \
 && curl -fsSL -o NotoSansJP-Bold.otf    "${NOTO_CJK_BASE}/NotoSansJP-Bold.otf"

### Runtime stage #########################################################
FROM eclipse-temurin:21-jre
# Bundled fonts for offline generation: Noto core (Latin, Vietnamese, Hebrew,
# Arabic, Thai, Devanagari, Greek, Cyrillic...) + Noto Sans JP (Japanese/CJK).
RUN apt-get update \
 && apt-get install -y --no-install-recommends fonts-noto-core fonts-noto-extra curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=fonts /cjk/*.otf /app/fonts/
COPY --from=build /src/target/pdfbox.jar /app/pdfbox.jar

EXPOSE 8080
ENV JAVA_OPTS=""
# Optional base path prefix for every route (must start with '/'); empty by default.
ENV PDFBOX_BASE_PATH=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS "http://localhost:8080${PDFBOX_BASE_PATH}/actuator/health" || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/pdfbox.jar"]
