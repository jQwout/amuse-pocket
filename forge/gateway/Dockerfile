# The gateway alone: a fat jar on a JRE. No Android SDK here - bundles are built by the worker,
# wherever the SDK is. Build the jar first: ./gradlew :gateway:fatJar (from forge/).
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/libs/gateway-all.jar /app/gateway.jar
ENV PORT=8080
ENV FORGE_DATA_DIR=/data
EXPOSE 8080
CMD ["java", "-Xmx256m", "-jar", "/app/gateway.jar"]
