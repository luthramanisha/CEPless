#!/bin/bash

mkdir -p libs/

cp ../flink/flink-dist/target/flink-dist_2.11-1.8.0.jar libs/
cp ../flink/flink-connectors/flink-connector-kafka-base/target/flink-connector-kafka-base_2.11-1.8.0.jar libs/
cp ../flink/flink-connectors/flink-connector-kafka/target/flink-connector-kafka_2.11-1.8.0.jar libs/

gradle build