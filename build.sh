#!/bin/bash

echo "Building TCEP docker container..."
./tcep/scripts/build-local.sh
echo "Finished building TCEP docker container!"

echo "Building NodeManager docker container..."
docker build -t node-manager ./node-manager
echo "Finished building NodeManager docker container!"

