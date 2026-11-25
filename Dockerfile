# BLACK ICE v2 — Linux / Raspberry Pi headless survey node
#
# The dashboard is a plain HTTP server (no desktop / GUI). Raw 802.11 capture
# needs to see the host's wireless interfaces and requires packet-socket
# privileges, so run the image with host networking and extra capabilities:
#
#   docker build -t blackice .
#   docker run --rm --net=host --cap-add=NET_ADMIN --cap-add=NET_RAW blackice
#
# Build on the Pi itself (or with buildx --platform linux/arm64) for ARM.

# ---- build: compile the fat jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline || true

COPY src ./src
COPY prebuilt ./prebuilt

# WigleGeolocator.java is an empty placeholder in this tree; drop it so javac
# does not fail, then inject the prebuilt class before packaging.
RUN set -eu; \
    if [ ! -s src/main/java/WigleGeolocator.java ]; then \
      rm -f src/main/java/WigleGeolocator.java; \
    fi; \
    mvn -q -B -DskipTests compile; \
    if [ -f prebuilt/WigleGeolocator.class ]; then \
      cp prebuilt/WigleGeolocator*.class target/classes/; \
    fi; \
    mvn -q -B -DskipTests package

# ---- runtime: slim JRE + libpcap ----
FROM eclipse-temurin:17-jre

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        libpcap0.8 \
        iw \
        wireless-tools \
        fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /src/target/jwscan-2.0.0-jar-with-dependencies.jar /app/jwscan.jar
COPY pcap_config.txt hopper_config.txt export_config.txt session_config.txt /app/
# Offline OUI vendor cache (avoids a first-run download on a headless Pi).
COPY oui_cache.txt /app/oui_cache.txt

EXPOSE 8080

# Headless is also set in Rat.main(); this covers any AWT use before main().
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"

# Runs as root so NET_RAW / NET_ADMIN actually apply to the capture process.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "/app/jwscan.jar"]
CMD ["8080"]
