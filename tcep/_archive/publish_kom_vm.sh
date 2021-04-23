#!/usr/bin/env bash

#Delete all local temp files
find . -type f -name "*.log" -exec rm -f {} \;
find . -type f -name "*.out" -exec rm -f {} \;
rm -r target/


ssh $1@$2 <<-'ENDSSH'
    #Stop docker & remove src folder from remote host
    docker stop $(docker ps --filter ancestor=adaptivecepapp -a -q)
    rm -f $(docker ps --filter ancestor=adaptivecepapp -a -q);
    rm -r ~/src
    rm flip.csv
    rm pietzuch.csv
    rm splittime.csv
    rm starks.csv
ENDSSH


scp -r . $1@$2:~/src
#ssh rarif@10.2.1.15 "cd ~/src && bash publish_docker.sh"

#other kom VMS
#10.2.1.40
#10.2.1.42
#10.2.1.43
#10.2.1.44
#10.2.1.45
#10.2.1.46
#10.2.1.47