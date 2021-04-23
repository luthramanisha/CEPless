#!/usr/bin/env bash

echo "stop all docker containers with image-name adaptivecepapp "
docker stop $(docker ps --filter ancestor=adaptivecepapp -a -q)
docker stop nserver

echo "removing all docker containers before starting new ones"
docker rm -f $(docker ps --filter ancestor=adaptivecepapp -a -q)
docker rm -f nserver

echo "killing pumba"
pkill -f pumba

rm adaptiveCEP.log
sudo rm -r ~/logs/*.csv

#echo "removing older image"
#docker rmi adaptivecepapp -f

#echo "building image"
#docker build -t adaptivecepapp .

docker network rm isolated_nw
docker network create -d overlay --subnet=10.10.10.0/24 isolated_nw

pkill -f pumba

sleep 60

echo "running containers"
docker run --network=isolated_nw --name nserver -d -p 123:123  cloudwattfr/ntpserver:latest
docker run --privileged --network=isolated_nw --name viv -e MAIN="adaptivecep.machinenodes.VivaldiApp" -e ARGS="--port 2549 --ip viv" -d -p 2549:2549 adaptivecepapp
docker run --privileged --network=isolated_nw --name bench -e MAIN="adaptivecep.machinenodes.BenchmarkingApp" -e ARGS="--port 2550 --ip bench" -p 2550:2550 -d adaptivecepapp
sleep 10 #wait for seed nodes to be initialized

docker run --privileged --network=isolated_nw --name simpla -v ~/logs:/app/logs -e MAIN="adaptivecep.machinenodes.EmptyApp" -e ARGS="--port 2557 --ip simpla" -d -p 2557:2557 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplb -v ~/logs:/app/logs -e MAIN="adaptivecep.machinenodes.EmptyApp" -e ARGS="--port 2558 --ip simplb" -d -p 2558:2558 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplc -v ~/logs:/app/logs -e MAIN="adaptivecep.machinenodes.EmptyApp" -e ARGS="--port 2559 --ip simplc" -d -p 2559:2559 adaptivecepapp
docker run --privileged --network=isolated_nw --name simpld -v ~/logs:/app/logs -e MAIN="adaptivecep.machinenodes.EmptyApp" -e ARGS="--port 2560 --ip simpld" -d -p 2560:2560 adaptivecepapp
docker run --privileged --network=isolated_nw --name simple -v ~/logs:/app/logs -e MAIN="adaptivecep.machinenodes.EmptyApp" -e ARGS="--port 2562 --ip simple" -d -p 2562:2562 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplf -v ~/logs:/app/logs -e MAIN="adaptivecep.machinenodes.EmptyApp" -e ARGS="--port 2563 --ip simplf" -d -p 2563:2563 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplg -v ~/logs:/app/logs -e MAIN="adaptivecep.machinenodes.EmptyApp" -e ARGS="--port 2564 --ip simplg" -d -p 2564:2564 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplh -v ~/logs:/app/logs -e MAIN="adaptivecep.machinenodes.EmptyApp" -e ARGS="--port 2565 --ip simplh" -d -p 2565:2565 adaptivecepapp

docker run --privileged --network=isolated_nw --name puba -e MAIN="adaptivecep.machinenodes.PublisherApp" -e ARGS="--port 2551 --name DoorSensor --ip puba" -d -p 2551:2551 adaptivecepapp
docker run --privileged --network=isolated_nw --name pubb -e MAIN="adaptivecep.machinenodes.PublisherApp" -e ARGS="--port 2552 --name SanitizerSensor --ip pubb" -d -p 2552:2552 adaptivecepapp
#docker run --privileged --network=isolated_nw --name subs1 -v ~/logs:/app/logs -e MAIN="adaptivecep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 1 subs1 2566" -d -p 2566:2566 adaptivecepapp #pietzuch
#docker run --privileged --network=isolated_nw --name subs2 -v ~/logs:/app/logs -e MAIN="adaptivecep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 2 subs2 2567" -d -p 2567:2567 adaptivecepapp #starks
#docker run --privileged --network=isolated_nw --name subs3 -v ~/logs:/app/logs -e MAIN="adaptivecep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 3 subs3 2569" -d -p 2569:2569 adaptivecepapp #splittime
docker run --privileged --network=isolated_nw --name subs4 -v ~/logs:/app/logs -e MAIN="adaptivecep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 4 subs4 2570" -d -p 2570:2570 adaptivecepapp #flip

if [[ "$OSTYPE" == "darwin"* ]]; then
ttab 'docker exec -it viv bash'
ttab 'docker exec -it bench bash'

ttab 'docker exec -it puba bash'
ttab 'docker exec -it pubb bash'
ttab 'docker exec -it pubc bash'
ttab 'docker exec -it simpla bash'
ttab 'docker exec -it simplb bash'

ttab 'docker exec -it subs1 bash'
fi

sleep 20
#pumba netem --duration 25m delay --time 100 --jitter 30 puba &
#pumba netem --duration 25m delay --time 100 --jitter 30 pubb &

#pumba netem --duration 25m delay --time 100 --jitter 30 simpla &
#pumba netem --duration 25m delay --time 100 --jitter 30 simplb &
#pumba netem --duration 25m delay --time 100 --jitter 30 simplc &
#pumba netem --duration 25m delay --time 100 --jitter 30 simpld &
#pumba netem --duration 25m delay --time 100 --jitter 30 simplf &
#pumba netem --duration 25m delay --time 100 --jitter 30 simplg &
#pumba netem --duration 25m delay --time 100 --jitter 30 simplh &

#pumba netem --duration 25m delay --time 200 --jitter 30 subs1 &
#pumba netem --duration 25m delay --time 150 --jitter 30 subs2 &
#pumba netem --duration 25m delay --time 100 --jitter 30 subs3 &
#pumba netem --duration 25m delay --time 100 --jitter 30 subs4 &

#pumba run -c "re2:^simpl|5m|KILL:SIGTERM" --random & #after every 5 minutes random kill a container having name starting with simpl (EMPTY Apps)