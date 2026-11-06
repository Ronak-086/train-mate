# Build: docker build -t trainmate-api backend
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
# Tests need PostgreSQL and run in CI (they skip themselves when it is unreachable).
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Leave headroom for the JVM's own memory on a small (512 MB) instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
EXPOSE 8080
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]
