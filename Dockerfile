# Builds the service jar with the Maven wrapper, then runs it on a JRE-only image.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY service/pom.xml service/pom.xml
COPY demo/pom.xml demo/pom.xml
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl service -am dependency:go-offline
COPY service/src service/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl service -am package -DskipTests

FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 reservecore
USER reservecore
WORKDIR /app
COPY --from=build /src/service/target/reservecore-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
