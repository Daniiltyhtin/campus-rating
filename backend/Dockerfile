FROM eclipse-temurin:25-jdk AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S campus && adduser -S -G campus campus \
    && mkdir -p /app/uploads && chown campus:campus /app/uploads
WORKDIR /app
COPY --from=build /app/target/campus-rating-*.jar app.jar
USER campus
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
