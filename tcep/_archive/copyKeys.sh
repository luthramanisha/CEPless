#!/usr/bin/env bash

work_dir="$(cd "$(dirname "$0")" ; pwd -P)/../"
source "$work_dir/docker-swarm.cfg"


#still the ssh server has to be registered for the respective user
#cat ~/.ssh/y.pub | ssh $user@$manager 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys'

for i in "${workers[@]}"
    do
	cat ~/.ssh/y.pub | ssh $user@$i 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys'
    done


