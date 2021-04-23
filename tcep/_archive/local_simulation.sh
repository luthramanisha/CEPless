#!/usr/bin/env bash

find /path -name '*.csv' -delete

if [[ "$OSTYPE" == "darwin"* ]]; then

sbt one-jar
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.VivaldiApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2549'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.BenchmarkingApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2550'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.EmptyApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2557'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2551 --name A'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2552 --name B'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.machinenodes.PublisherApp -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar --ip 127.0.0.1 --port 2553 --name C'
ttab 'java -Dconfig.resource=/simulation.conf -Done-jar.main.class=adaptivecep.simulation.adaptive.cep.SimulationRunner -jar ./target/scala-2.12/adaptivecep_2.12-0.0.1-SNAPSHOT-one-jar.jar .'

fi