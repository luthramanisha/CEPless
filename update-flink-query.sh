#!/bin/bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

bash $DIR/stop-flink.sh

echo "Starting updated Flink run"

bash $DIR/run-flink-updated.sh