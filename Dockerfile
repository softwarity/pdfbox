# syntax=docker/dockerfile:1

### Build stage ###########################################################
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -B -q -DskipTests package

### CJK fonts stage #######################################################
# The huge Noto CJK faces are kept out of the jar; they ship as filesystem fonts
# in the image instead. Noto CJK is only distributed as OpenType/CFF (which PDFBox
# cannot embed) or as variable TTF. PDFBox embeds a variable font's *default*
# master — which for these is Thin — so we freeze each one to a static Regular
# instance (wght=400) with fontTools before placing it on the image.
FROM python:3.12-slim AS cjk-fonts
RUN pip install --no-cache-dir fonttools
RUN python - <<'PY'
import os, subprocess, urllib.request
base = "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/Variable/TTF/Subset"
os.makedirs("/cjk", exist_ok=True)
for v in ("KR", "SC", "TC"):
    vf = f"/tmp/NotoSans{v}-VF.ttf"
    urllib.request.urlretrieve(f"{base}/NotoSans{v}-VF.ttf", vf)
    subprocess.run(["fonttools", "varLib.instancer", "--update-name-table",
                    vf, "wght=400", "-o", f"/cjk/NotoSans{v}-Regular.ttf"], check=True)
PY

### Runtime stage #########################################################
FROM eclipse-temurin:21-jre
# A broad Noto font set (Latin/Vietnamese, Hebrew, Arabic, Thai, Devanagari, all
# the Indic/SE-Asian/Caucasian scripts, symbols, mono emoji...) is bundled inside
# the jar; the big CJK faces (Korean, Chinese SC/TC) come from the cjk-fonts stage
# below. FontService registers, per render, only the scripts a document contains,
# so the wide coverage costs nothing on a Latin-only PDF. curl is for the HEALTHCHECK.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Optional drop-in directory for extra TrueType fonts (see PDFBOX_FONTS_DIRECTORIES).
RUN mkdir -p /app/fonts
# Bundled-in-the-image CJK (scanned via /usr/share/fonts, a default font directory).
COPY --from=cjk-fonts /cjk/ /usr/share/fonts/truetype/noto-cjk/
COPY --from=build /src/target/pdfbox.jar /app/pdfbox.jar

EXPOSE 8080
ENV JAVA_OPTS=""
# Optional base path prefix for every route (must start with '/'); empty by default.
ENV PDFBOX_BASE_PATH=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS "http://localhost:8080${PDFBOX_BASE_PATH}/actuator/health" || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/pdfbox.jar"]
