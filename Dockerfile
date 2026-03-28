# Stage 1: build
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime
FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build /app/target/moonlight-*.war app.war
EXPOSE 8078
ENTRYPOINT ["java", "-jar", "app.war"]
