#!/bin/bash

mvn compile assembly:single

docker build -t $1/$2 . 
docker push $1/$2
