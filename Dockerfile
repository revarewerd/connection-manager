# ============================================
# Dockerfile для Connection Manager
# ============================================
# Многоэтапная сборка для минимизации размера образа
#
# Этап 1: Сборка JAR через SBT
# Этап 2: Запуск на минимальном JRE
# ============================================

# Этап 1: Сборка
FROM sbtscala/scala-sbt:eclipse-temurin-17.0.8.1_1_1.9.7_3.3.1 AS builder

WORKDIR /app

# Копируем файлы сборки для кэширования зависимостей
COPY build.sbt .
COPY project/build.properties project/
COPY project/plugins.sbt project/ 2>/dev/null || true

# Скачиваем зависимости (кэшируется Docker'ом)
RUN sbt update

# Копируем исходный код
COPY src/ src/

# Собираем fat JAR
RUN sbt assembly || sbt "set assembly / assemblyMergeStrategy := { \
  case PathList(\"META-INF\", xs @ _*) => MergeStrategy.discard \
  case x => MergeStrategy.first \
}; assembly"

# Если assembly не настроен, собираем обычный JAR + зависимости
RUN sbt stage 2>/dev/null || sbt packageBin

# Этап 2: Runtime
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="WayRecall Team"
LABEL description="Connection Manager - GPS трекер сервер"

WORKDIR /app

# Устанавливаем timezone
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Europe/Moscow /etc/localtime && \
    echo "Europe/Moscow" > /etc/timezone

# Создаём пользователя без root
RUN addgroup -S tracker && adduser -S tracker -G tracker
USER tracker

# Копируем JAR из builder
COPY --from=builder /app/target/scala-*/connection-manager*.jar ./app.jar

# Копируем конфигурацию
COPY src/main/resources/application.conf ./
COPY src/main/resources/logback.xml ./

# Порты:
# 5001 - Teltonika
# 5002 - Wialon  
# 5003 - Ruptela
# 5004 - NavTelecom
# 8080 - HTTP API
EXPOSE 5001 5002 5003 5004 8080

# Healthcheck через HTTP API
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

# JVM параметры для контейнера
ENV JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -Dconfig.file=/app/application.conf \
  -Dlogback.configurationFile=/app/logback.xml"

# Запуск
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
