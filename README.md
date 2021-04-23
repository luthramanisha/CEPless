# CEPless: Stateful Serverless Complex Event Processing

CEPless is a unified serverless middleware for developing custom user-defined operators without any runtime or language dependence. It allows application developers to program operators without any knowledge of the underlying CEP system, equivalent to providing "operator-as-a-service" for Complex Event Processing. 

CEPless middleware makes the following novel contributions:

+ **In-memory queue management** and **batching** mechanisms that enables stateful processing and ensure correctness and fast delivery of events which are extremely important for CEP systems
+ **Programming interface** to develop CEP operators using the middleware
+ **User-defined operator interface** that allows the integration of highly diverse CEP runtime systems into CEPLESS system and allows runtime system independence.
+ We integrate CEPless into two CEP systems: Flink and TCEP as seen in the following. 

To run CEPless, see [here](#running-tcep)

[Prerequisites](#prerequisites)

[Running TCEP](#running-tcep)

[Running Flink](#running-flink)

[Evaluation](#evaluation)

[Publications](#publications)

[Acknowledgement](#acknowledgement)

[Contact](#contact)

### [Prerequisites](#prerequisites)
The following frameworks/tools need to be installed in order to be able to run the application: 

- Java 8+
- Docker 18.09+ [link](https://docs.docker.com/install/linux/docker-ce/ubuntu/)
- GoLang 1.12+ [link](https://tecadmin.net/install-go-on-ubuntu/)
- Maven 3.2.5 [link](http://basicgroundwork.blogspot.com/2014/07/installing-maven-322-on-ubuntu-1404.html)
- SDKMan [link](https://sdkman.io/install), Gradle 3.5+ [link](https://gradle.org/install/#with-a-package-manager) 
- sbt [link](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Linux.html)
- Docker-compose [link](https://docs.docker.com/compose/install/)
- Scala [link](https://www.scala-lang.org/download/)
- Apache Kafka [link](https://www.apache.org/dyn/closer.cgi?path=/kafka/2.4.0/kafka_2.12-2.4.0.tgz)

For CEPless-web following tools are required
 - Angular CLI version 10.0.3  [link](https://github.com/angular/angular-cli).
 - Node 10.13+ [link](https://nodejs.org/en/download/).
 - for Node see binary install instructions [here](https://medium.com/@tgmarinho/how-to-install-node-js-via-binary-archive-on-linux-ab9bbe1dd0c2)
 - ng as `npm install -g @angular/cli ng`

##### Setting up Kafka
For data generation we use Apache Kafka which can be downloded [here](https://www.apache.org/dyn/closer.cgi?path=/kafka/2.4.0/kafka_2.12-2.4.0.tgz). 
In the next steps we show how Kafka is set up properly to be able to communicate with CEP system containers.

**1.** Download and unpack the Kafka installation:
```
tar -xzf kafka_2.12-2.4.0.tgz
cd kafka_2.12-2.4.0
```

**2.** Kafka needs a Zookeeper instance running. This instance can be started as follows: 
```
bin/zookeeper-server-start.sh config/zookeeper.properties
```
**3.** For Kafka to be accessible by Docker containers, the configuration needs to be modified so Kafka binds the Docker accessible Gateway IP.
```
vi config/server.properties
```
Update the following line with the Docker Gateway IP:

```
listeners=PLAINTEXT://172.17.0.1:9092
```
**4.** Start the Kafka instance
```
bin/kafka-server-start.sh config/server.properties
```

#### Setting up NodeManager
For setting up the NodeManager component you will need to provide a config.yaml containing credentials to a Docker registry. Therefore copy the existing config.example.yaml:
```
cd node-manager
cp config.example.yaml config.yaml
```
Then edit the username and password with an accessible hub.docker.com account. Afterwards, the docker container needs to be rebuild: 
```
docker build -t node-manager . 
```

#### Building an example operator
The operators directory contains some example operators in different programming languages. To use one of the operators you can for example navigate to `operators/java-template/` and issue the following command: 
```
./build.sh DOCKER_REGISTRY_USER OPERATOR_NAME
```
This will build the corresponding operator and push it to the given docker registry account. 

### [Running TCEP](#running-tcep)

**1.** Build all the project parts using the build script provided:
```
./build.sh
```
**2.**  When the build from step 1 finished successfully, the application can be started by navigating into the tcep directory and issuing the following command. Please note to set `KAFKA_HOST`to refect your docker gateway IP in docker-compose.yml.
```
docker network create node-manager-net
docker-compose up
```
**3.** The CEP system is now starting on your local machine which takes about 1 minute to finish. You can open the logs of the node manager using this command: 
```
docker logs -f tcep_nodeManager_1
```
**4.** When the system placed the custom example query, you should see the following output which confirms that the custom operator is running: 
```
2019/06/23 09:30:32 Registering streaming node with name da366f626fd1
2019/06/23 09:30:34 edd08825ce642ddd2c7f8fd96759d1e62a7ed5c5004df1ed235ac7543881e712
``` 
**5.** You can now see the events handled by the custom example operator by showing the logs using this command: 
```
docker logs -f custom-test-{id}
```

The custom operator receives the event submitted from the CEP engine in the redis queue and answers with a simple string `foobar`

### [Running Flink](#running-flink)

**1.** Make sure to install the correct version of Maven on your machine (3.2.5 not higher)
```
cd /opt
wget https://www-us.apache.org/dist/maven/maven-3/3.2.5/binaries/apache-maven-3.2.5-bin.tar.gz
tar -xvzf apache-maven-3.2.5-bin.tar.gz
```
**2.** Add Maven to your ~/.bashrc
``` 
export PATH=/opt/apache-maven-3.2.5/bin:$PATH
```
**3.** Clone and build the Flink project using the following script
```
git clone git@github.com:apache/flink.git
cd ./flink
./build.sh
```
If the build should fail, because of missing dependencies try to switch into the subproject the build where the build is failing (for example flink-tables) and run the following command
```
mvn package -DskipTests -Dfast
```
After this subproject build succeeds you can run again the build script from above
**4.**  When the build from step 3 finished successfully, the application can be started by issuing the following command: 
```
docker network create node-manager-net
docker-compose up -d 
```
Flink is now starting on your local machine which takes about 30 seconds to finish. 
**5.** An example query is included in this project in the `cepless_flink` directory. To build the flink executable query jar issue the following commands
```
cd ./cepless_flink
./build.sh
```
**6.** After that you can start the example query using the following script from the root directory. Please note to set `--kafka.server`to refect your docker gateway IP.
```
./run-flink.sh
```

### [Evaluation](#evaluation)

To evaluate the system using the included financial transaction dataset, we added a throttled Kafka producer benchmark. This benchmark needs to be build with the following commands
```
cd kafka-producer
gradle build
```

Afterwards the benchmark can be started with the following command. Please note that the IP has to reflect your docker gateway IP and throughput gives the number of events emitted per second
```
java -jar build/libs/kafka-producer.jar --topic op-test --num-records 1000000000 --producer-props bootstrap.servers=172.17.0.1:9092 --payload-file ../evaluation/cardtransactions-reduced.csv --throughput 1000
```

## [Publications](#publications)
[1] Manisha Luthra, Sebastian Hennig, Kamran Razavi, Lin Wang, and Boris Koldehofe: Operator as a Service: Stateful Serverless Complex Event Processing. In: Proceedings of the IEEE International Conference on Big Data Workshop, pp. 1964-1973, IEEE, December 2020. <a href="https://doi.org/10.1109/BigData50022.2020.9378142" target="_blank">10.1109/BigData50022.2020.9378142</a> 

## [Acknowledgement](#acknowledgement)

This work has been co-funded by the German Research Foundation (DFG) within the <a href="https://www.maki.tu-darmstadt.de/sfb_maki/ueber_maki/index.en.jsp" target="_blank">Collaborative Research Center (CRC) 1053 -- MAKI</a>

## [Contact](#contact)

Feel free to contact <a href="https://www.kom.tu-darmstadt.de/kom-multimedia-communications-lab/people/staff/manisha-luthra/" target="_blank">Manisha Luthra</a> or <a href="https://www.rug.nl/staff/b.koldehofe/" target="_blank">Boris Koldehofe</a> for any questions. 
