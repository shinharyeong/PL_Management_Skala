# syntax=docker/dockerfile:1
# 빌드 컨텍스트는 저장소 루트(docker build -f backend/Dockerfile .). 파서를 자식 프로세스로 실행하므로 parser/src도 함께 넣는다.

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar \
    && cp build/libs/backend-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-noble AS runtime
# 파서는 표준 라이브러리만 쓰는 Python 3.11+ 패키지다(noble 기본 python3 = 3.12).
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
COPY parser/src /app/parser/src
RUN mkdir -p /app/local-storage /app/templates /data/storage /data/templates \
    && chown -R app:app /app/local-storage /app/templates /data
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    PARSER_PYTHON=python3 \
    PARSER_SOURCE_DIRECTORY=/app/parser/src \
    PYTHONDONTWRITEBYTECODE=1
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
