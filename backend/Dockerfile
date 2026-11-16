# syntax=docker/dockerfile:1

FROM gradle:8.12-jdk21 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle backend/ ./
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 \
    gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy AS java-runtime

# Python 3.11 표준 라이브러리 파서와 Java 21을 같은 런타임에 둔다.
FROM python:3.11-slim-bookworm AS runtime
RUN apt-get update \
    && apt-get install --no-install-recommends -y libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:-UsePerfData -Djava.io.tmpdir=/tmp"
WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/*.jar /app/backend.jar
COPY parser/src/ /app/parser/src/
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
