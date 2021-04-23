#!/bin/bash

echo "Starting Flink run"

#docker cp ./evaluation/cardtransactions-reduced.csv flink_jobmanager_1:/opt/flink/
#docker cp ./evaluation/cardtransactions-reduced.csv flink_taskmanager_1:/opt/flink/

docker cp ./flink-test/build/libs/flink-test.jar flink_jobmanager_1:/opt/flink

if [ -z "$1" ]
then 
    echo "No custom operator name provided"
else
    docker exec flink_jobmanager_1 /bin/sh -c "./bin/flink run flink-test.jar --input cardtransactions-reduced.csv --sim.recordDelay 60000 --kafka.server 172.17.0.1:9092 --serverless true  --updatedOperator false --customOperator $1;" &
fi
