FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Copy the assembly JAR
COPY target/scala-3.7.3/distributed-clock-sync.jar /app/distributed-clock-sync.jar

# Copy WASM resources
COPY src/main/resources/wasm/ /app/wasm/

# Copy entrypoint
COPY local-dev/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Default environment
ENV NODE_TYPE=genesis
ENV HTTP_PORT=9000
ENV P2P_PORT=9001
ENV METRICS_PORT=9002
ENV OSC_PORT=9050
ENV GENESIS_HOST=localhost
ENV NODE_ID=node-genesis
ENV INITIAL_BPM=120.0
ENV JAVA_OPTS="-Xmx512m -XX:+UseG1GC"
ENV WASM_PATH=/app/wasm/clock_consensus.wasm
ENV PROOF_DIR=/app/proofs

# Create data directories
RUN mkdir -p /app/data /app/proofs

# Expose ports: HTTP, P2P, Metrics, OSC
EXPOSE 9000 9001 9002 9050/udp

ENTRYPOINT ["/app/entrypoint.sh"]
