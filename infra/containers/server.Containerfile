FROM docker.io/eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x ./gradlew && ./gradlew --no-daemon bootJar

FROM docker.io/eclipse-temurin:21-jre

WORKDIR /app
RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /var/lib/seedshift-radio/config \
    && chown -R appuser:appuser /var/lib/seedshift-radio
COPY --from=build /workspace/build/libs/*.jar /app/seedshift-radio-server.jar

ENV JAVA_OPTS="" \
    SEEDSHIFT_RADIO_SERVER_PORT=8080

EXPOSE 8080
USER appuser
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/seedshift-radio-server.jar \"$@\"", "--"]
