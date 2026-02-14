FROM eclipse-temurin:21-jdk AS builder

# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    git make g++ zlib1g-dev libmpg123-dev libboost-all-dev python3 xxd \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Build ProjectorRays from source
RUN git clone --depth 1 https://github.com/ProjectorRays/ProjectorRays.git \
    && cd ProjectorRays \
    && make -j$(nproc) \
    && cp projectorrays /usr/local/bin/

# Copy and build LibreShockwave SDK
COPY LibreShockwave/ /build/LibreShockwave/
RUN cd /build/LibreShockwave \
    && chmod +x gradlew \
    && ./gradlew :sdk:jar :cast-extractor:jar --no-daemon \
    && mkdir -p /opt/shockwave-extractor/lib \
    && cp sdk/build/libs/sdk-0.1.0.jar /opt/shockwave-extractor/lib/ \
    && cp cast-extractor/build/libs/cast-extractor-1.0.0.jar /opt/shockwave-extractor/lib/

# Compile Java tools
COPY tools/ /opt/shockwave-extractor/tools/
RUN javac -cp "/opt/shockwave-extractor/lib/sdk-0.1.0.jar:/opt/shockwave-extractor/lib/cast-extractor-1.0.0.jar" \
    /opt/shockwave-extractor/tools/ExtractAssets.java \
    /opt/shockwave-extractor/tools/DiagnoseBitmaps.java \
    /opt/shockwave-extractor/tools/PixelDiag.java \
    /opt/shockwave-extractor/tools/DumpRaw.java \
    /opt/shockwave-extractor/tools/DumpRaw2.java

# --- Runtime image ---
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 libmpg123-0 \
    && rm -rf /var/lib/apt/lists/*

# Copy built artifacts
COPY --from=builder /usr/local/bin/projectorrays /usr/local/bin/
COPY --from=builder /opt/shockwave-extractor/ /opt/shockwave-extractor/
COPY fix_sounds.py /opt/shockwave-extractor/
COPY extract-docker.sh /usr/local/bin/extract

RUN chmod +x /usr/local/bin/extract

WORKDIR /data

ENTRYPOINT ["extract"]
