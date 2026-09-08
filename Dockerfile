FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress clean verify

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/springboot-wxcloudrun-1.0.jar /app/app.jar
ENV PORT=80
EXPOSE 80
CMD ["java", "-jar", "/app/app.jar"]
