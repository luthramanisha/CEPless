#!/bin/bash

echo "Starting Flink run"

# docker cp ./flink-test/build/libs/flink-test.jar flink_jobmanager_1:/opt/flink

docker  exec flink_jobmanager_1 /bin/sh -c "./bin/flink run flink-test.jar --input cardtransactions-reduced.csv --sim.recordDelay 0 --kafka.server 172.18.0.1:9092 --serverless false --updatedOperator true;" &
