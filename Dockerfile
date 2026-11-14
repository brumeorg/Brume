FROM eclipse-temurin:25-jre-alpine

# pg_dump 18 (dernière majeure) : lit toutes les sources supportées, 12 → 18 (ADR-0058).
RUN apk add --no-cache postgresql18-client

WORKDIR /app

COPY target/brume-*.jar app.jar

VOLUME ["/config"]

ENTRYPOINT ["java", "-Dbrume.config-path=/config/brume.yml", "-jar", "app.jar"]
CMD ["execute"]