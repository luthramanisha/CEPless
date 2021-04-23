#!/bin/bash

docker run -v $(pwd):/user-config -e CONFIG_PATH="/user-config/config.yaml" --net="node-manager-net" infinispan/server