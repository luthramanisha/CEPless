#!/bin/bash

echo "Find JobID"

ipaddr=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'  flink_jobmanager_1 | sed 's/172.18.*//')
data=$(curl http://$ipaddr:8081/jobs)
job_id=${data#"{\"jobs\":[{\"id\":\""}
job_id=$(echo $job_id| cut -f1 -d"\"")
echo "JOBID: $job_id"

echo "Stopping Flink query"
docker exec flink_taskmanager_1 /bin/sh -c "./bin/flink cancel $job_id"