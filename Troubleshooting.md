## Troubleshooting

(1) When building Flink, Maven sometimes is not able to shade away dependencies well. It is possible that `./build.sh` throws errors like:
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.8.0:compile (default-compile) on project flink-statebackend-rocksdb_2.11: Compilation failure: Compilation failure:
[ERROR] /home/shennig/cepless/flink/flink-state-backends/flink-statebackend-rocksdb/src/main/java/org/apache/flink/contrib/streaming/state/ttl/RocksDbTtlCompactFiltersManager.java:[86,24] cannot find symbol
```
The easiest way to resolve this is to navigate to the corresponding sub-project (in this case `flink-statebackend-rocksdb`) and issue the maven build command again with: `mvn package -DskipTests -Dfast`
After this completed successfully the build can be started again in the flink root director with `./build.sh`

(2) CEP engine or Kafka producer prints message: 
```
kafka cannot find topic "metadata"
```
This usually means that either CEP engine or Kafka producer is not able to access the Kafka server. To resolve this, verify that Kafka server and CEP engine/Kafka producer are communicating on the same IP address.

