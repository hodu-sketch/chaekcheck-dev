# Backend image (cycle 17 test deployment). The jar is built on the PC first (`./gradlew bootJar` in backend/), so
# this file only copies it; the same file builds amd64 and arm64 (docker buildx --platform linux/arm64).
# Java 25 runtime to match the Gradle toolchain (backend/build.gradle).
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY build/libs/chaekcheck-0.0.1-SNAPSHOT.jar app.jar
# No RUN step, so building for another architecture needs no emulation; a plain unused uid instead of useradd.
USER 10001:10001

EXPOSE 8080
# JVM options come from JAVA_TOOL_OPTIONS in compose (for example the heap size).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
