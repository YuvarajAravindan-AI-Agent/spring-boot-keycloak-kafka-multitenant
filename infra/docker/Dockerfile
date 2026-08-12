# Runtime image for any of the three services.
#
# Expects `mvn package` to have run on the host (see the Makefile). Building Maven inside the
# image is tidier in principle, but it re-downloads the dependency tree on every cache miss and
# turns a two-minute demo into a fifteen-minute one. CI builds the jar once and reuses it here,
# which is what a real pipeline does anyway.

FROM eclipse-temurin:17-jre-alpine

ARG SERVICE
ENV SERVICE=${SERVICE}

# Run unprivileged. A JVM that never needs to write outside /tmp has no reason to be root,
# and this is the cheapest possible mitigation for a container escape.
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app
COPY ${SERVICE}/target/${SERVICE}-*.jar /app/application.jar
RUN chown -R app:app /app

USER app

# Container-aware heap sizing: without MaxRAMPercentage the JVM reads the host's memory, sizes
# a heap for it, and gets OOM-killed by a limit it never knew about.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
