#!/usr/bin/env bash

#find . -name '*.csv' -delete

rm flip.csv
rm splittime.csv

if [[ "$OSTYPE" == "darwin"* ]]; then
echo "Please use alpine linux"
else
sbt one-jar
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.VivaldiApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2549 &> viv.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.BenchmarkingApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2550 &> bench.out&
sleep 10 # wait for seed nodes to be initialized

#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2570 &> empty1.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2571 &> empty2.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2572 &> empty1.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2573 &> empty2.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2574 &> empty1.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2575 &> empty2.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2576 &> empty2.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2577 &> empty2.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2578 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2579 &> empty2.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2580 &> empty2.out&

nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2551 --name DoorSensor &> puba.out&
nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2552 --name SanitizerSensor &> pubb.out&

nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.simulation.trasitivecep.SimulationRunner -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar  . 1 127.0.0.1 2566 &> subs1.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.simulation.trasitivecep.SimulationRunner -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar  . 2 127.0.0.1 2567 &> subs2.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.simulation.trasitivecep.SimulationRunner -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar  . 3 127.0.0.1 2568 &> subs3.out&
#nohup java -Dconfig.resource=/simulation.conf -Done-jar.main.class=tcep.simulation.trasitivecep.SimulationRunner -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar  . 4 127.0.0.1 2569 &> subs4.out&
echo "printing logs in out files, use tail -f <file-name> to see log of particular process. CSV files are available in ./logs directory"
fi
