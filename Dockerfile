FROM eclipse-temurin:17-jre

ARG JAR_FILE=build/libs/web-0.0.1-SNAPSHOT.jar

WORKDIR /app
COPY ${JAR_FILE} app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
