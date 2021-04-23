#!/usr/bin/env bash

rm changing-environment.csv

if [[ "$OSTYPE" == "darwin"* ]]; then
sbt one-jar
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.VivaldiApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2549'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.BenchmarkingApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2550'
sleep 10 # wait for seed nodes to be initialized

ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2570'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2571'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2572'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2573'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2574'

ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2551 --name A'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2552 --name B'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2553 --name C'

ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.simulation.trasitivecep.SimulationRunner -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar . 5 127.0.0.1 2566'

else
sbt one-jar
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.VivaldiApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2549 &> viv.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.BenchmarkingApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2550 &> bench.out&
sleep 10 # wait for seed nodes to be initialized

nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2570 &> empty1.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2571 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2572 &> empty1.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2573 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2574 &> empty1.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2575 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2576 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2577 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2578 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2579 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2580 &> empty2.out&

nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2551 --name DoorSensor &> puba.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2552 --name SanitizerSensor &> pubb.out&

nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.simulation.trasitivecep.SimulationRunner -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar  . 5 127.0.0.1 2570 &> subs1.out&
echo "printing logs in out files, use tail -f <file-name> to see log of particular process"

change_envrionment () {
echo "waiting in background"

while true
do
if [ ! -f changing-environment.csv ]; then
    echo "Simulation not started yet!"
    sleep 30
else break
fi

done

sleep 240

echo "creating 9 machines"
i="2591"

while [ $i -lt 2600 ]
do
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port $i &> ignore.out&
i=$[$i+1]
done
}

change_envrionment &
fi