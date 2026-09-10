FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --home-dir /app onyx
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
RUN mkdir -p /var/lib/onyx/files && chown -R onyx:onyx /app /var/lib/onyx
USER onyx
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
