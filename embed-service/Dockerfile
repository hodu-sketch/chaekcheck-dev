# Embedding service image. The jar is built on the PC first (`./gradlew bootJar` in embed-service/), so this file
# only copies it; the same file builds amd64 (the micro EC2) and arm64 (docker buildx --platform).
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY build/libs/chaekcheck-embed.jar app.jar
# No RUN step, so building for another architecture needs no emulation; a plain unused uid instead of useradd.
USER 10001:10001

EXPOSE 8090
# JVM options come from JAVA_TOOL_OPTIONS (for example the heap size); the model is mounted at EMBED_MODEL_DIR.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
