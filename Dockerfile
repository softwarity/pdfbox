# syntax=docker/dockerfile:1

### Build stage ###########################################################
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package

### Runtime stage #########################################################
FROM eclipse-temurin:21-jre
# A broad Noto font set (Latin/Vietnamese, Hebrew, Arabic, Thai, Devanagari,
# Japanese/CJK...) is bundled inside the jar, so the image needs no system fonts
# for offline generation. curl is only here for the container HEALTHCHECK.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Optional drop-in directory for extra TrueType fonts (see PDFBOX_FONTS_DIRECTORIES).
RUN mkdir -p /app/fonts
COPY --from=build /src/target/pdfbox.jar /app/pdfbox.jar

EXPOSE 8080
ENV JAVA_OPTS=""
# Optional base path prefix for every route (must start with '/'); empty by default.
ENV PDFBOX_BASE_PATH=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS "http://localhost:8080${PDFBOX_BASE_PATH}/actuator/health" || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/pdfbox.jar"]
