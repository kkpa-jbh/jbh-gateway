# Runtime image for jbh-gateway.
# CI builds the Spring Boot jar first (./gradlew bootJar), so this image only copies it.
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S jbh && adduser -S jbh -G jbh
WORKDIR /app

# *-SNAPSHOT.jar is the Boot jar; the *-plain.jar next to it is not runnable.
COPY --chown=jbh:jbh build/libs/*-SNAPSHOT.jar /app/app.jar

USER jbh
EXPOSE 8080

# JAVA_OPTS carries the heap cap (-Xmx) from compose. exec keeps java as PID 1 for clean shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
