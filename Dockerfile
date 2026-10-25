# ============================================================
# Etapa 1 - BUILD
# Compila, corre las pruebas rapidas (no necesitan Oracle:
# usan la base simulada) y empaqueta el jar ejecutable.
# Las pruebas contra Oracle (./gradlew integrationTest) no corren aqui.
# ============================================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Primero el wrapper y los descriptores: las dependencias solo se
# vuelven a descargar si cambian estos archivos (cache de capas).
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon build

# ============================================================
# Etapa 2 - RUNTIME
# Solo el JRE y el jar, con usuario sin privilegios.
# ============================================================
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /app/build/libs/*.jar app.jar
USER spring

EXPOSE 8080

# Docker sabe si la API esta sana: consulta /actuator/health por TCP con bash (no depende de que la imagen traiga curl).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3' || exit 1

# Los secretos llegan por variables de entorno en tiempo de ejecucion
# (nunca se copian a la imagen: ver .dockerignore).
# Sin memoria, el proceso termina y Docker lo reinicia (en lugar de quedar vivo pero inservible).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
