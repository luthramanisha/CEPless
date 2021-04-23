#!/usr/bin/env bash

work_dir="$(cd "$(dirname "$0")" ; pwd -P)/../"
source $work_dir/docker-swarm.cfg

if [ -z $1 ]; then
	ssh $user@$manager
else
	ssh $user@${workers[$1]}
fi

