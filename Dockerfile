# ============================================
# Dockerfile для Connection Manager
# ============================================
# Многоэтапная сборка для минимизации размера образа
#
# Этап 1: Сборка JAR через SBT
# Этап 2: Запуск на минимальном JRE
# ============================================

# Этап 1: Сборка
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Устанавливаем SBT
RUN apt-get update && \
    apt-get install -y curl && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt

# Копируем файлы сборки для кэширования зависимостей
COPY build.sbt .
COPY project/build.properties project/
COPY project/plugins.sbt project/

# Скачиваем зависимости (кэшируется Docker'ом)
RUN sbt update

# Копируем исходный код
COPY src/ src/

# Собираем fat JAR (assemblyJarName := "connection-manager.jar")
RUN sbt assembly

# Этап 2: Runtime
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="WayRecall Team"
LABEL description="Connection Manager - GPS трекер сервер"

WORKDIR /app

# Устанавливаем timezone и glibc для xerial-snappy
RUN apk add --no-cache tzdata glibc glibc-bin libstdc++ && \
    cp /usr/share/zoneinfo/Europe/Moscow /etc/localtime && \
    echo "Europe/Moscow" > /etc/timezone

# Создаём пользователя без root
RUN addgroup -S tracker && adduser -S tracker -G tracker
USER tracker

# Копируем JAR из builder (точное имя из build.sbt)
COPY --from=builder /app/target/scala-3.4.0/connection-manager.jar ./app.jar

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
