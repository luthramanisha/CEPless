#!/bin/bash

mvn package -DskipTests -Dfast && \
docker build -t custom-flink .
