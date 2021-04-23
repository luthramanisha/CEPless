FROM openjdk:8-jdk-alpine

COPY . /app
WORKDIR /app

RUN wget http://mirrors.sonic.net/apache/maven/maven-3/3.2.5/binaries/apache-maven-3.2.5-bin.tar.gz && \
    tar -zxf apache-maven-3.2.5-bin.tar.gz && \
    cp -R apache-maven-3.2.5 /usr/local && \
    ln -s /usr/local/apache-maven-3.2.5/bin/mvn /usr/bin/mvn

RUN echo "Starting compilation"; (mvn -e compile assembly:single) && echo "Finished compilation: succedeed" || (echo "Finished compilation: failed" && exit 1) 

# COPY target/java-example-operator-1.0-SNAPSHOT-jar-with-dependencies.jar /app.jar

ENTRYPOINT ["java", "-jar", "target/java-example-operator-1.0-SNAPSHOT-jar-with-dependencies.jar"]