FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache postgresql-client

WORKDIR /app

COPY target/brume-*.jar app.jar

VOLUME ["/config"]

ENTRYPOINT ["java", "-Dbrume.config-path=/config/brume.yml", "-jar", "app.jar"]
CMD ["execute"]