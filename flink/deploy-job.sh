docker cp ./flink-examples/flink-examples-streaming/target/flink-examples-streaming_2.11-1.8.0-WordCount.jar "flink_jobmanager_1":/job.jar
docker exec -t -i "flink_jobmanager_1" flink run /job.jar
