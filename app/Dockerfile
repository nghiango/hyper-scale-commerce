FROM eclipse-temurin:21-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && mkdir -p /diagnostics && chown 10001:10001 /diagnostics
WORKDIR /app
COPY build/libs/app-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/diagnostics/heapdump.hprof -XX:NativeMemoryTracking=summary -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
