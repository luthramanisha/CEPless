#!/usr/bin/env bash

echo "stop all docker containers with image-name adaptivecepapp "
docker stop $(docker ps --filter ancestor=adaptivecepapp -a -q)

echo "removing all docker containers before starting new ones"
docker rm -f $(docker ps --filter ancestor=adaptivecepapp -a -q)
docker rm -f nserver

echo "killing pumba"
pkill -f pumba

sudo rm -r ~/logs/*.csv

echo "removing older image"
docker rmi adaptivecepapp -f

echo "building image"
docker build -t adaptivecepapp .

docker network rm isolated_nw
docker network create isolated_nw

pkill -f pumba
echo "running containers"
docker run --network=isolated_nw --name nserver -d -p 123:123  cloudwattfr/ntpserver:latest
docker run --privileged --network=isolated_nw --name viv -e MAIN="tcep.machinenodes.VivaldiApp" -e ARGS="--port 2549 --ip viv" -d -p 2549:2549 adaptivecepapp
docker run --privileged --network=isolated_nw --name bench -e MAIN="tcep.machinenodes.BenchmarkingApp" -e ARGS="--port 2550 --ip bench" -p 2550:2550 -d adaptivecepapp
sleep 10 #wait for seed nodes to be initialized

docker run --privileged --network=isolated_nw --name simpla -e MAIN="tcep.machinenodes.EmptyApp" -e ARGS="--port 2557 --ip simpla" -d -p 2557:2557 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplb -e MAIN="tcep.machinenodes.EmptyApp" -e ARGS="--port 2558 --ip simplb" -d -p 2558:2558 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplc -e MAIN="tcep.machinenodes.EmptyApp" -e ARGS="--port 2559 --ip simplc" -d -p 2559:2559 adaptivecepapp
docker run --privileged --network=isolated_nw --name simpld -e MAIN="tcep.machinenodes.EmptyApp" -e ARGS="--port 2560 --ip simpld" -d -p 2560:2560 adaptivecepapp
docker run --privileged --network=isolated_nw --name simple -e MAIN="tcep.machinenodes.EmptyApp" -e ARGS="--port 2562 --ip simple" -d -p 2562:2562 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplf -e MAIN="tcep.machinenodes.EmptyApp" -e ARGS="--port 2563 --ip simplf" -d -p 2563:2563 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplg -e MAIN="tcep.machinenodes.EmptyApp" -e ARGS="--port 2564 --ip simplg" -d -p 2564:2564 adaptivecepapp
docker run --privileged --network=isolated_nw --name simplh -e MAIN="tcep.machinenodes.EmptyApp" -e ARGS="--port 2565 --ip simplh" -d -p 2565:2565 adaptivecepapp

docker run --privileged --network=isolated_nw --name puba -e MAIN="tcep.machinenodes.PublisherApp" -e ARGS="--port 2551 --name DoorSensor --ip puba" -d -p 2551:2551 adaptivecepapp
docker run --privileged --network=isolated_nw --name pubb -e MAIN="tcep.machinenodes.PublisherApp" -e ARGS="--port 2552 --name SanitizerSensor --ip pubb" -d -p 2552:2552 adaptivecepapp
docker run --privileged --network=isolated_nw --name changing -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 5 changing 2571" -d -p 2571:2571 adaptivecepapp #changing environment

change_envrionment () {
echo "waiting in background"

while true
do
if [ ! -f ~/logs/*.csv ]; then
    echo "Simulation not started yet!"
    sleep 10
else break
fi

done

docker run --privileged --network=isolated_nw --name emptySim1 -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 6 emptySim1 2572" -d -p 2572:2572 adaptivecepapp #emptySim1
docker run --privileged --network=isolated_nw --name emptySim2 -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 6 emptySim2 2573" -d -p 2573:2573 adaptivecepapp #emptySim2
docker run --privileged --network=isolated_nw --name emptySim3 -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 6 emptySim3 2574" -d -p 2574:2574 adaptivecepapp #emptySim4
docker run --privileged --network=isolated_nw --name emptySim4 -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 6 emptySim4 2575" -d -p 2575:2575 adaptivecepapp #emptySim4

docker run --privileged --network=isolated_nw --name emptySim5 -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 6 emptySim5 2566" -d -p 2566:2566 adaptivecepapp #emptySim5
docker run --privileged --network=isolated_nw --name emptySim6 -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 6 emptySim6 2567" -d -p 2567:2567 adaptivecepapp #emptySim6
docker run --privileged --network=isolated_nw --name emptySim7 -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 6 emptySim7 2569" -d -p 2569:2569 adaptivecepapp #emptySim7
docker run --privileged --network=isolated_nw --name emptySim9 -v ~/logs:/app/logs -e MAIN="tcep.simulation.trasitivecep.SimulationRunner" -e ARGS=" ./logs 6 emptySim8 2570" -d -p 2570:2570 adaptivecepapp #emptySim8

pumba run -c "re2:^emptySiml|5m|KILL:SIGTERM" --random &
}

change_envrionment &

sleep 20
pumba netem --duration 25m delay --time 100 --jitter 30 puba &
pumba netem --duration 25m delay --time 100 --jitter 30 pubb &

pumba netem --duration 25m delay --time 100 --jitter 30 simpla &
pumba netem --duration 25m delay --time 100 --jitter 30 simplb &
pumba netem --duration 25m delay --time 100 --jitter 30 simplc &
pumba netem --duration 25m delay --time 100 --jitter 30 simpld &
pumba netem --duration 25m delay --time 100 --jitter 30 simplf &
pumba netem --duration 25m delay --time 100 --jitter 30 simplg &
pumba netem --duration 25m delay --time 100 --jitter 30 simplh &

pumba netem --duration 25m delay --time 100 --jitter 30 changing &