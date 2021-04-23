FROM openjdk:8-jre-alpine

COPY target/java-example-operator-1.0-SNAPSHOT-jar-with-dependencies.jar /app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]