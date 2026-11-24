# Runtime image for jbh-gateway.
# CI builds the Spring Boot jar first (./gradlew bootJar), so this image only copies it.
FROM eclipse-temurin:21-jre-alpine

# A fixed numeric user and group (10001). Kubernetes "runAsNonRoot" can only check a number:
# with a user name (USER jbh) the pod fails with "image has non-numeric user".
RUN addgroup -S -g 10001 jbh && adduser -S -u 10001 -G jbh jbh
WORKDIR /app

# *-SNAPSHOT.jar is the Boot jar; the *-plain.jar next to it is not runnable.
COPY --chown=10001:10001 build/libs/*-SNAPSHOT.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# JAVA_OPTS carries the heap cap (-Xmx) from compose. exec keeps java as PID 1 for clean shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
